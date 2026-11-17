package br.edu.fileshare.android.service

import br.edu.fileshare.android.network.AndroidMulticastLockManager
import br.edu.fileshare.android.network.AndroidMulticastTransport
import br.edu.fileshare.android.network.MulticastLockHandle
import br.edu.fileshare.android.storage.AndroidFileSink
import br.edu.fileshare.android.storage.DirectoryDestinationPublisher
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.discovery.DiscoveryDefaults
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.UdpDatagram
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsapForegroundServiceTest {

    private class FakeMulticastLockHandle : MulticastLockHandle {
        private var held: Boolean = false
        override val isHeld: Boolean
            get() = held

        override fun acquire() {
            held = true
        }

        override fun release() {
            held = false
        }
    }

    private class FakeWakeLockHandle : WakeLockHandle {
        var acquireCalls: Int = 0
            private set
        var releaseCalls: Int = 0
            private set
        private var held: Boolean = false
        override val isHeld: Boolean
            get() = held

        override fun acquire() {
            acquireCalls++
            held = true
        }

        override fun release() {
            releaseCalls++
            held = false
        }
    }

    private class FakeMulticastTransport : MulticastTransport {
        private var openState: Boolean = false
        private var closedSignal = CompletableDeferred<Unit>()
        val sentPackets = mutableListOf<String>()

        override val isOpen: Boolean
            get() = openState

        override val boundPort: Int
            get() = DiscoveryDefaults.UDP_PORT

        override suspend fun open() {
            closedSignal = CompletableDeferred()
            openState = true
        }

        override suspend fun send(bytes: ByteArray) {
            synchronized(sentPackets) {
                sentPackets += bytes.decodeToString()
            }
        }

        override suspend fun receive(): UdpDatagram? {
            closedSignal.await()
            return null
        }

        override fun close() {
            openState = false
            closedSignal.complete(Unit)
        }

        fun sentMessagesSnapshot(): List<String> = synchronized(sentPackets) { sentPackets.toList() }
    }

    private class RecordingServiceHost : FsapServiceHost {
        val foregroundEntries = mutableListOf<FsapNotificationState>()
        val notificationUpdates = mutableListOf<FsapNotificationState>()
        var exitStopCalls: Int = 0
            private set

        override fun enterForeground(state: FsapNotificationState) {
            synchronized(foregroundEntries) {
                foregroundEntries += state
            }
        }

        override fun updateNotification(state: FsapNotificationState) {
            synchronized(notificationUpdates) {
                notificationUpdates += state
            }
        }

        override fun exitForegroundAndStopSelf() {
            exitStopCalls++
        }
    }

    private class InMemoryByteArrayFileSource(
        override val fileName: String,
        private val bytes: ByteArray,
        override val mimeType: String = "application/octet-stream",
    ) : FileSource {
        override val sizeBytes: Long
            get() = bytes.size.toLong()

        override suspend fun openReadSession(): FileReadSession = object : FileReadSession {
            private var offset: Int = 0

            override suspend fun readChunk(buffer: ByteArray): Int {
                if (offset >= bytes.size) return -1
                val length = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + length)
                offset += length
                return length
            }

            override suspend fun close() = Unit
        }
    }

    @Test
    fun lifecycleStartIdentifyReceiveTransferAndStopEmitsByeAndReleasesLocks() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-service-test-").toFile()
        val stagingDir = File(tempRoot, "staging")
        val downloadsDir = File(tempRoot, "downloads")
        val logger = InMemoryFsapLogger()

        val multicastLockHandle = FakeMulticastLockHandle()
        val multicastLockManager = AndroidMulticastLockManager(lockFactory = { multicastLockHandle }, logger = logger)
        val rawMulticastTransport = FakeMulticastTransport()
        val androidMulticastTransport = AndroidMulticastTransport(
            lockManager = multicastLockManager,
            delegate = rawMulticastTransport,
            logger = logger,
        )

        val wakeLockHandle = FakeWakeLockHandle()
        val wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLockHandle }, logger = logger)
        val host = RecordingServiceHost()

        lateinit var controller: FsapForegroundNodeController
        val fileSink = AndroidFileSink(
            stagingDirectory = stagingDir,
            publisher = DirectoryDestinationPublisher(downloadsDir),
            onCommitted = { descriptor -> controller.recordCommittedFile(descriptor) },
            logger = logger,
        )

        controller = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = "Pixel-Lab",
            deviceType = DeviceType.MOBILE,
            initialPort = 0,
            fileSink = fileSink,
            multicastTransportFactory = { androidMulticastTransport },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = wakeLockManager,
            serviceHost = host,
            approvalHandler = AutoAcceptTransferApprovalHandler,
            logger = logger,
        )

        val clientIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Desktop-Sender",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val clientHttp = FsapHttpClient(localIdentity = clientIdentity, logger = logger)
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clientCoordinator = TransferCoordinator(
            httpClient = clientHttp,
            parentScope = clientScope,
            logger = logger,
        )

        try {
            assertEquals(LocalNodeState.STOPPED, controller.nodeState.value)

            val startResult = controller.startNode(alias = "Pixel-Lab", port = 0)
            assertTrue(startResult.isSuccess, "startNode deve iniciar com sucesso: ${startResult.exceptionOrNull()}")
            val boundPort = startResult.getOrThrow()
            assertTrue(boundPort > 0)
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
            assertTrue(multicastLockHandle.isHeld, "MulticastLock deve estar retido enquanto servidor roda")
            assertTrue(wakeLockHandle.isHeld, "PartialWakeLock deve estar retido enquanto servidor roda")
            assertTrue(host.foregroundEntries.isNotEmpty(), "Host deve entrar em foreground com notificacao")

            val sentAfterStart = rawMulticastTransport.sentMessagesSnapshot()
            assertTrue(
                sentAfterStart.any { it.contains("\"ANNOUNCE\"") },
                "Inicio do FsapForegroundService deve anunciar presenca na rede local.",
            )

            val identifyResult = controller.connectByManualIp("127.0.0.1", boundPort)
            assertIs<ManualConnectResult.Connected>(identifyResult)
            assertEquals("Pixel-Lab", identifyResult.peer.alias)
            assertEquals(PlatformType.ANDROID, identifyResult.peer.identity.platform)

            val payload = ByteArray(128 * 1024 + 53) { index -> ((index * 31) % 251).toByte() }
            val expectedSha = sha256Hex(payload)
            val outcome = clientCoordinator.sendFiles(
                endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = boundPort),
                peerAlias = "Pixel-Lab",
                sources = listOf(InMemoryByteArrayFileSource("foto-aula.bin", payload)),
            )

            assertIs<SendFilesOutcome.Completed>(outcome)
            assertEquals(1, controller.receivedFiles.value.size)
            val committed = controller.receivedFiles.value.first()
            assertEquals("foto-aula.bin", committed.finalName)
            assertEquals(expectedSha, committed.sha256)

            val savedFile = File(downloadsDir, "foto-aula.bin")
            assertTrue(savedFile.exists())
            assertContentEquals(payload, savedFile.readBytes())
            assertTrue(
                stagingDir.listFiles()?.none { it.name.endsWith(".part") } ?: true,
                "Nenhum .part deve restar apos o commit.",
            )

            controller.stopNode(reason = "NOTIFICATION_STOP")

            assertEquals(LocalNodeState.STOPPED, controller.nodeState.value)
            assertFalse(multicastLockHandle.isHeld, "MulticastLock deve ser liberado no stopNode")
            assertFalse(wakeLockHandle.isHeld, "PartialWakeLock deve ser liberado no stopNode")
            assertEquals(1, host.exitStopCalls)

            val sentAfterStop = rawMulticastTransport.sentMessagesSnapshot()
            assertTrue(
                sentAfterStop.any { it.contains("\"BYE\"") && it.contains("NOTIFICATION_STOP") },
                "Parar servico via notificacao deve emitir BYE antes de encerrar.",
            )
        } finally {
            clientCoordinator.close()
            clientHttp.close()
            clientScope.cancel()
            controller.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun disablingReceptionWhenIdleStopsServiceAutomatically() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-idle-stop-").toFile()
        val logger = InMemoryFsapLogger()
        val multicastLockHandle = FakeMulticastLockHandle()
        val wakeLockHandle = FakeWakeLockHandle()
        val rawMulticastTransport = FakeMulticastTransport()
        val host = RecordingServiceHost()

        val controller = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = "Tablet-Ocioso",
            deviceType = DeviceType.TABLET,
            initialPort = 0,
            fileSink = AndroidFileSink(
                stagingDirectory = File(tempRoot, "staging"),
                publisher = DirectoryDestinationPublisher(File(tempRoot, "downloads")),
                logger = logger,
            ),
            multicastTransportFactory = {
                AndroidMulticastTransport(
                    lockManager = AndroidMulticastLockManager(lockFactory = { multicastLockHandle }, logger = logger),
                    delegate = rawMulticastTransport,
                    logger = logger,
                )
            },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLockHandle }, logger = logger),
            serviceHost = host,
            approvalHandler = AutoAcceptTransferApprovalHandler,
            logger = logger,
        )

        try {
            assertTrue(controller.startNode(port = 0).isSuccess)
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
            assertEquals(0, host.exitStopCalls)

            controller.setReceptionEnabled(false)

            assertEquals(LocalNodeState.STOPPED, controller.nodeState.value)
            assertEquals(1, host.exitStopCalls)
            assertFalse(multicastLockHandle.isHeld)
            assertFalse(wakeLockHandle.isHeld)
            assertTrue(
                rawMulticastTransport.sentMessagesSnapshot().any {
                    it.contains("\"BYE\"") && it.contains("RECEPTION_DISABLED")
                },
            )
        } finally {
            controller.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun disablingReceptionDuringActiveTransferWaitsUntilCompletionBeforeAutoStop() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-active-stop-").toFile()
        val downloadsDir = File(tempRoot, "downloads")
        val logger = InMemoryFsapLogger()
        val multicastLockHandle = FakeMulticastLockHandle()
        val wakeLockHandle = FakeWakeLockHandle()
        val rawMulticastTransport = FakeMulticastTransport()
        val host = RecordingServiceHost()

        val approvalRequested = CompletableDeferred<ApprovalRequestContext>()
        val releaseApproval = CompletableDeferred<TransferApprovalDecision>()
        val controlledApproval = TransferApprovalHandler { ctx ->
            approvalRequested.complete(ctx)
            releaseApproval.await()
        }

        val controller = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = "Celular-Ativo",
            initialPort = 0,
            fileSink = AndroidFileSink(
                stagingDirectory = File(tempRoot, "staging"),
                publisher = DirectoryDestinationPublisher(downloadsDir),
                logger = logger,
            ),
            multicastTransportFactory = {
                AndroidMulticastTransport(
                    lockManager = AndroidMulticastLockManager(lockFactory = { multicastLockHandle }, logger = logger),
                    delegate = rawMulticastTransport,
                    logger = logger,
                )
            },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLockHandle }, logger = logger),
            serviceHost = host,
            approvalHandler = controlledApproval,
            logger = logger,
        )

        val clientIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Notebook-Envio",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val clientHttp = FsapHttpClient(localIdentity = clientIdentity, logger = logger)
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clientCoordinator = TransferCoordinator(
            httpClient = clientHttp,
            parentScope = clientScope,
            logger = logger,
        )

        try {
            val boundPort = controller.startNode(port = 0).getOrThrow()

            val sendJob = async {
                clientCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint("127.0.0.1", boundPort),
                    peerAlias = "Celular-Ativo",
                    sources = listOf(InMemoryByteArrayFileSource("notas.txt", "conteudo teste".encodeToByteArray())),
                )
            }

            val pendingContext = withTimeout(5_000) { approvalRequested.await() }
            withTimeout(5_000) {
                while (controller.activeTransfersCount.value < 1) {
                    delay(10)
                }
            }

            // Desabilita modo receptor durante transferencia ativa -> NAO pode encerrar ainda
            controller.setReceptionEnabled(false)
            delay(50)
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
            assertEquals(0, host.exitStopCalls)

            // Libera aprovacao e conclui envio -> controlador deve detectar ociosidade e parar
            releaseApproval.complete(
                TransferApprovalDecision.Approved(
                    acceptedFileIds = pendingContext.request.files.map { it.fileId },
                ),
            )
            val outcome = withTimeout(5_000) { sendJob.await() }
            assertIs<SendFilesOutcome.Completed>(outcome)

            withTimeout(5_000) {
                while (controller.nodeState.value != LocalNodeState.STOPPED) {
                    delay(10)
                }
            }

            assertEquals(1, host.exitStopCalls)
            assertFalse(multicastLockHandle.isHeld)
            assertFalse(wakeLockHandle.isHeld)
            assertTrue(File(downloadsDir, "notas.txt").exists())
            assertTrue(
                rawMulticastTransport.sentMessagesSnapshot().any {
                    it.contains("\"BYE\"") && it.contains("ALL_TRANSFERS_FINISHED_RECEPTION_OFF")
                },
            )
        } finally {
            clientCoordinator.close()
            clientHttp.close()
            clientScope.cancel()
            controller.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun bindFailureTransitionsToFaultedAndRecoversOnRetryStart() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-bind-fault-").toFile()
        val logger = InMemoryFsapLogger()
        val wakeLockHandle = FakeWakeLockHandle()
        val multicastLockHandle = FakeMulticastLockHandle()
        val host = RecordingServiceHost()

        val controller = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = "Pixel-Retry",
            initialPort = 0,
            fileSink = AndroidFileSink(
                stagingDirectory = File(tempRoot, "staging"),
                publisher = DirectoryDestinationPublisher(File(tempRoot, "downloads")),
                logger = logger,
            ),
            multicastTransportFactory = {
                AndroidMulticastTransport(
                    lockManager = AndroidMulticastLockManager(lockFactory = { multicastLockHandle }, logger = logger),
                    delegate = FakeMulticastTransport(),
                    logger = logger,
                )
            },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLockHandle }, logger = logger),
            serviceHost = host,
            logger = logger,
            autoSelectPortOnConflict = false,
        )

        val blockerSocket = ServerSocket(0)
        try {
            val occupiedPort = blockerSocket.localPort
            val failResult = controller.startNode(alias = "Pixel-Retry", port = occupiedPort)

            assertTrue(failResult.isFailure)
            assertEquals(LocalNodeState.FAULTED, controller.nodeState.value)
            assertFalse(wakeLockHandle.isHeld)
            assertFalse(multicastLockHandle.isHeld)
            assertTrue(
                host.notificationUpdates.any { it.title == "FileShare indisponível" },
                "Falha de bind deve sinalizar estado na notificacao.",
            )

            blockerSocket.close()

            val retryResult = controller.startNode(alias = "Pixel-Retry", port = 0)
            assertTrue(retryResult.isSuccess, "Retry apos FAULTED deve recuperar o no FSAP")
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
            assertTrue(wakeLockHandle.isHeld)
            assertTrue(multicastLockHandle.isHeld)
        } finally {
            runCatching { blockerSocket.close() }
            controller.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun occupiedPortAutomaticallySelectsNextAvailablePortOnStart() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-autoport-").toFile()
        val logger = InMemoryFsapLogger()
        val wakeLockHandle = FakeWakeLockHandle()
        val multicastLockHandle = FakeMulticastLockHandle()
        val host = RecordingServiceHost()

        val controller = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = "Pixel-AutoPort",
            initialPort = 0,
            fileSink = AndroidFileSink(
                stagingDirectory = File(tempRoot, "staging"),
                publisher = DirectoryDestinationPublisher(File(tempRoot, "downloads")),
                logger = logger,
            ),
            multicastTransportFactory = {
                AndroidMulticastTransport(
                    lockManager = AndroidMulticastLockManager(lockFactory = { multicastLockHandle }, logger = logger),
                    delegate = FakeMulticastTransport(),
                    logger = logger,
                )
            },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLockHandle }, logger = logger),
            serviceHost = host,
            logger = logger,
        )

        val blockerSocket = ServerSocket(0)
        try {
            val occupiedPort = blockerSocket.localPort
            val startResult = controller.startNode(alias = "Pixel-AutoPort", port = occupiedPort)

            assertTrue(startResult.isSuccess, "Fallback de porta deveria iniciar com sucesso: ${startResult.exceptionOrNull()}")
            val boundPort = startResult.getOrThrow()
            assertTrue(boundPort != occupiedPort, "Porta selecionada ($boundPort) deve diferir da porta ocupada ($occupiedPort).")
            assertEquals(boundPort, controller.boundPort.value)
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)

            // Chamada subsequente com a mesma porta configurada (ex: onStartCommand) preserva porta efetiva sem reiniciar
            val idempotentResult = controller.startNode(alias = "Pixel-AutoPort", port = occupiedPort)
            assertEquals(boundPort, idempotentResult.getOrThrow())
            assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
        } finally {
            runCatching { blockerSocket.close() }
            controller.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun notificationPermissionHelperChecksApi33ThresholdAndRegistryLifecycle() {
        assertFalse(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 26))
        assertFalse(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 29))
        assertFalse(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 32))
        assertTrue(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 33))
        assertTrue(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 34))
        assertTrue(AndroidNotificationPermissionHelper.requiresRuntimeNotificationPermission(sdkInt = 35))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }
}

