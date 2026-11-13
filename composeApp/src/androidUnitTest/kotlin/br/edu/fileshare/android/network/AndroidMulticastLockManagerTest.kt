package br.edu.fileshare.android.network

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.network.discovery.DiscoveryCoordinator
import br.edu.fileshare.network.discovery.LanInterfaceInfo
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.NetworkInterfaceProvider
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.discovery.UdpDatagram
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMulticastLockManagerTest {

    private class FakeMulticastLockHandle(
        private val throwOnAcquire: Throwable? = null,
        private val throwOnRelease: Throwable? = null,
        private val refuseToHold: Boolean = false,
    ) : MulticastLockHandle {
        var acquireCalls: Int = 0
            private set
        var releaseCalls: Int = 0
            private set

        private var held: Boolean = false
        override val isHeld: Boolean
            get() = held

        override fun acquire() {
            acquireCalls++
            throwOnAcquire?.let { throw it }
            if (!refuseToHold) {
                held = true
            }
        }

        override fun release() {
            releaseCalls++
            held = false
            throwOnRelease?.let { throw it }
        }
    }

    private class FakeMulticastTransport(
        private val throwOnOpen: Throwable? = null,
        private val throwOnClose: Throwable? = null,
        private val lockObserver: AndroidMulticastLockManager? = null,
    ) : MulticastTransport {
        var openCalls: Int = 0
        var closeCalls: Int = 0
        var lockWasHeldDuringOpen: Boolean? = null
        val sentPackets = mutableListOf<ByteArray>()
        private val closedSignal = CompletableDeferred<Unit>()
        private var openState: Boolean = false

        override val isOpen: Boolean
            get() = openState

        override val boundPort: Int
            get() = 53318

        override suspend fun open() {
            openCalls++
            lockWasHeldDuringOpen = lockObserver?.isHeld
            throwOnOpen?.let { throw it }
            openState = true
        }

        override suspend fun send(bytes: ByteArray) {
            sentPackets += bytes.copyOf()
        }

        override suspend fun receive(): UdpDatagram? {
            closedSignal.await()
            return null
        }

        override fun close() {
            closeCalls++
            openState = false
            closedSignal.complete(Unit)
            throwOnClose?.let { throw it }
        }
    }

    private class RecordingLogger : FsapLogger {
        val records = mutableListOf<Pair<LogLevel, String>>()
        override fun log(
            level: LogLevel,
            component: String,
            message: String,
            context: LogContext,
            throwable: Throwable?,
        ) {
            records += level to "[$component] $message"
        }
    }

    @Test
    fun acquireAndReleaseAreIdempotentAndGuaranteeIsHeldFalse() {
        val handle = FakeMulticastLockHandle()
        var createdCount = 0
        val manager = AndroidMulticastLockManager(
            lockFactory = {
                createdCount++
                handle
            },
        )

        assertFalse(manager.isHeld)
        assertTrue(manager.acquire())
        assertTrue(manager.isHeld)
        assertTrue(manager.acquire(), "Segunda chamada deve ser idempotente")
        assertEquals(1, createdCount)
        assertEquals(1, handle.acquireCalls)

        manager.release()
        assertFalse(manager.isHeld)
        assertEquals(1, handle.releaseCalls)

        manager.release()
        assertEquals(1, handle.releaseCalls, "Segunda liberacao nao deve chamar release novamente")
    }

    @Test
    fun releaseLeavesIsHeldFalseEvenWhenPlatformReleaseThrows() {
        val logger = RecordingLogger()
        val handle = FakeMulticastLockHandle(throwOnRelease = RuntimeException("under-locked"))
        val manager = AndroidMulticastLockManager(
            lockFactory = { handle },
            logger = logger,
        )

        assertTrue(manager.acquire())
        assertTrue(manager.isHeld)

        manager.release()
        assertFalse(manager.isHeld)
        assertEquals(1, handle.releaseCalls)
        assertTrue(logger.records.any { it.first == LogLevel.WARN })
    }

    @Test
    fun acquireHandlesMissingServiceOrSecurityExceptionGracefully() {
        val missingManager = AndroidMulticastLockManager(lockFactory = { null })
        assertFalse(missingManager.acquire())
        assertFalse(missingManager.isHeld)

        val deniedHandle = FakeMulticastLockHandle(throwOnAcquire = SecurityException("CHANGE_WIFI_MULTICAST_STATE missing"))
        val deniedManager = AndroidMulticastLockManager(lockFactory = { deniedHandle })
        assertFalse(deniedManager.acquire())
        assertFalse(deniedManager.isHeld)

        val unheldHandle = FakeMulticastLockHandle(refuseToHold = true)
        val unheldManager = AndroidMulticastLockManager(lockFactory = { unheldHandle })
        assertFalse(unheldManager.acquire())
        assertFalse(unheldManager.isHeld)
    }

    @Test
    fun transportAcquiresLockBeforeOpenAndReleasesOnCloseOrFailure() = runTest {
        val handle = FakeMulticastLockHandle()
        val manager = AndroidMulticastLockManager(lockFactory = { handle })
        val delegate = FakeMulticastTransport(lockObserver = manager)
        val transport = AndroidMulticastTransport(lockManager = manager, delegate = delegate)

        transport.open()
        assertTrue(transport.isOpen)
        assertTrue(manager.isHeld)
        assertEquals(true, delegate.lockWasHeldDuringOpen)

        transport.send("ping".encodeToByteArray())
        assertEquals(1, delegate.sentPackets.size)

        transport.close()
        assertFalse(transport.isOpen)
        assertFalse(manager.isHeld)
        assertEquals(1, delegate.closeCalls)

        val failingOpenHandle = FakeMulticastLockHandle()
        val failingOpenManager = AndroidMulticastLockManager(lockFactory = { failingOpenHandle })
        val failingTransport = AndroidMulticastTransport(
            lockManager = failingOpenManager,
            delegate = FakeMulticastTransport(throwOnOpen = IOException("bind failed")),
        )
        assertFailsWith<IOException> { failingTransport.open() }
        assertFalse(failingOpenManager.isHeld, "Lock deve ser liberado se open() falhar")

        val failingCloseHandle = FakeMulticastLockHandle()
        val failingCloseManager = AndroidMulticastLockManager(lockFactory = { failingCloseHandle })
        val failingCloseTransport = AndroidMulticastTransport(
            lockManager = failingCloseManager,
            delegate = FakeMulticastTransport(throwOnClose = IOException("close error")),
        )
        failingCloseTransport.open()
        assertTrue(failingCloseManager.isHeld)
        assertFailsWith<IOException> { failingCloseTransport.close() }
        assertFalse(failingCloseManager.isHeld, "Lock deve ser liberado mesmo se close() lancar erro")
    }

    @Test
    fun discoveryCoordinatorLifecycleAcquiresAndReleasesAndroidMulticastLock() = runTest {
        val handle = FakeMulticastLockHandle()
        val manager = AndroidMulticastLockManager(lockFactory = { handle })
        val delegate = FakeMulticastTransport(lockObserver = manager)
        val transport = AndroidMulticastTransport(lockManager = manager, delegate = delegate)

        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val identity = DeviceIdentity(
                deviceId = "11111111-1111-4111-8111-111111111111",
                alias = "Pixel-Test",
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                protocolVersion = "1.0",
                httpPort = 53317,
            )
            val coordinator = DiscoveryCoordinator(
                transport = transport,
                peerRegistry = PeerRegistry(),
                localIdentityProvider = { identity },
                scope = coordScope,
            )

            coordinator.start()
            assertTrue(coordinator.isRunning)
            assertTrue(manager.isHeld)

            coordinator.stop(reason = "TEST_STOP")
            assertFalse(coordinator.isRunning)
            assertFalse(manager.isHeld, "MulticastLock deve estar liberado apos stop() da descoberta")
        } finally {
            coordScope.cancel()
        }
    }

    @Test
    fun androidInterfaceHelperFiltersCellularAndPrioritizesWlan() {
        val rawProvider = object : NetworkInterfaceProvider {
            override fun listLanInterfaces(): List<LanInterfaceInfo> = listOf(
                LanInterfaceInfo("eth0", "Ethernet", "192.168.1.10"),
                LanInterfaceInfo("rmnet_data0", "Cellular", "10.0.0.5"),
                LanInterfaceInfo("dummy0", "Dummy", "10.1.0.1"),
                LanInterfaceInfo("wlan0", "Wi-Fi", "192.168.1.44"),
            )
        }

        val androidProvider = AndroidNetworkInterfaceProvider(rawProvider)
        val selected = androidProvider.listLanInterfaces()

        assertEquals(listOf("wlan0", "eth0"), selected.map { it.name })
    }
}

