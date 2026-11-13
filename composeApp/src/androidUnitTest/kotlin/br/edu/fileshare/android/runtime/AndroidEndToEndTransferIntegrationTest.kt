package br.edu.fileshare.android.runtime

import br.edu.fileshare.android.network.AndroidMulticastLockManager
import br.edu.fileshare.android.network.AndroidMulticastTransport
import br.edu.fileshare.android.network.MulticastLockHandle
import br.edu.fileshare.android.service.AndroidPartialWakeLockManager
import br.edu.fileshare.android.service.FsapForegroundNodeController
import br.edu.fileshare.android.service.FsapNotificationState
import br.edu.fileshare.android.service.FsapServiceHost
import br.edu.fileshare.android.service.WakeLockHandle
import br.edu.fileshare.android.storage.AndroidContentAccess
import br.edu.fileshare.android.storage.AndroidDocumentMetadata
import br.edu.fileshare.android.storage.AndroidFileSink
import br.edu.fileshare.android.storage.AndroidSafFileSource
import br.edu.fileshare.android.storage.DirectoryDestinationPublisher
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.UdpDatagram
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.streaming.IncrementalSha256
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.StagedFileSession
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.viewmodel.FileShareMainViewModel
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.MainTab
import br.edu.fileshare.ui.viewmodel.ManualIpConnectViewModel
import br.edu.fileshare.ui.viewmodel.TransferProgressViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testes de integracao ponta a ponta do fluxo Android com notificacoes, progresso,
 * cancelamento e comunicacao bidirecional Desktop <-> Android e Android <-> Android (`TASK-AND-005`).
 */
class AndroidEndToEndTransferIntegrationTest {

    private class FakeMulticastLock : MulticastLockHandle {
        override var isHeld: Boolean = false
            private set

        override fun acquire() {
            isHeld = true
        }

        override fun release() {
            isHeld = false
        }
    }

    private class FakeWakeLock : WakeLockHandle {
        override var isHeld: Boolean = false
            private set

        override fun acquire() {
            isHeld = true
        }

        override fun release() {
            isHeld = false
        }
    }

    private class LoopbackIdleMulticastTransport : MulticastTransport {
        private var closedSignal = CompletableDeferred<Unit>()
        override var isOpen: Boolean = false
            private set
        override val boundPort: Int = FsapConstants.UDP_DISCOVERY_PORT

        override suspend fun open() {
            closedSignal = CompletableDeferred()
            isOpen = true
        }

        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun receive(): UdpDatagram? {
            closedSignal.await()
            return null
        }

        override fun close() {
            isOpen = false
            closedSignal.complete(Unit)
        }
    }

    private class RecordingServiceHost(
        private val onStopRequested: () -> Unit = {},
    ) : FsapServiceHost {
        private val history = mutableListOf<FsapNotificationState>()

        fun snapshotNotifications(): List<FsapNotificationState> = synchronized(history) { history.toList() }

        override fun enterForeground(state: FsapNotificationState) {
            synchronized(history) { history += state }
        }

        override fun updateNotification(state: FsapNotificationState) {
            synchronized(history) { history += state }
        }

        override fun exitForegroundAndStopSelf() {
            onStopRequested()
        }
    }

    private class FakeSafContentAccess(
        private val streamFactories: Map<String, Pair<AndroidDocumentMetadata, () -> InputStream>>,
    ) : AndroidContentAccess {
        override fun queryMetadata(uriString: String): AndroidDocumentMetadata? = streamFactories[uriString]?.first

        override fun openInputStream(uriString: String): InputStream? = streamFactories[uriString]?.second?.invoke()
    }

    /**
     * Implementacao NIO para simular o receptor Desktop em `androidUnitTest` (que nao enxerga `desktopMain`),
     * mantendo a mesma semantica de staging `.part`, checagem de espaco e promocao atomica.
     */
    private class TestDesktopNioFileSink(
        private val targetDirectory: Path,
        private val stagingDirectory: Path = targetDirectory.resolve(".fsap-staging"),
    ) : FileSink {
        init {
            Files.createDirectories(targetDirectory)
            Files.createDirectories(stagingDirectory)
        }

        override suspend fun availableStorageBytes(): Long = Long.MAX_VALUE

        override suspend fun openPartSession(
            transferId: String,
            fileId: String,
            requestedFileName: String,
            expectedSizeBytes: Long,
        ): StagedFileSession {
            val cleanName = FileNameSanitizer.requireValid(requestedFileName)
            val partPath = stagingDirectory.resolve("${transferId}_${fileId}.part")
            Files.deleteIfExists(partPath)
            val outputStream: OutputStream = Files.newOutputStream(partPath)
            return object : StagedFileSession {
                override val transferId: String = transferId
                override val fileId: String = fileId
                override val sanitizedFileName: String = cleanName
                override val partIdentifier: String = partPath.toAbsolutePath().toString()
                private var closed = false

                override suspend fun writeChunk(buffer: ByteArray, offset: Int, length: Int) {
                    outputStream.write(buffer, offset, length)
                }

                override suspend fun flushAndSync() {
                    outputStream.flush()
                }

                override suspend fun commitPart(
                    verifiedSha256: String,
                    totalBytesWritten: Long,
                ): StoredFileDescriptor {
                    if (!closed) {
                        outputStream.flush()
                        outputStream.close()
                        closed = true
                    }
                    val resolvedName = FileNameSanitizer.resolveNonOverwritingName(cleanName) { candidate ->
                        Files.exists(targetDirectory.resolve(candidate))
                    }
                    val finalPath = targetDirectory.resolve(resolvedName)
                    Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
                    return StoredFileDescriptor(
                        finalName = resolvedName,
                        locationUri = finalPath.toUri().toString(),
                        sizeBytes = totalBytesWritten,
                        sha256 = verifiedSha256,
                    )
                }

                override suspend fun discardPart() {
                    if (!closed) {
                        runCatching { outputStream.close() }
                        closed = true
                    }
                    Files.deleteIfExists(partPath)
                }
            }
        }

        override suspend fun cleanupTransferParts(transferId: String): Int {
            if (!Files.isDirectory(stagingDirectory)) return 0
            var deleted = 0
            Files.list(stagingDirectory).use { stream ->
                stream.filter { it.fileName.toString().startsWith("${transferId}_") && it.fileName.toString().endsWith(".part") }
                    .forEach {
                        if (Files.deleteIfExists(it)) deleted++
                    }
            }
            return deleted
        }

        fun listPartFiles(): List<Path> {
            if (!Files.isDirectory(stagingDirectory)) return emptyList()
            Files.list(stagingDirectory).use { stream ->
                return stream.filter { it.fileName.toString().endsWith(".part") }.toList()
            }
        }
    }

    private class AndroidTestNodeHarness(
        val alias: String,
        rootDir: File,
        val controllerFlow: MutableStateFlow<FsapForegroundNodeController?> = MutableStateFlow(null),
        val approvalCoordinator: InteractiveTransferApprovalCoordinator = InteractiveTransferApprovalCoordinator(),
        filePicker: suspend () -> List<br.edu.fileshare.storage.FileSource> = { emptyList() },
    ) {
        val logger = InMemoryFsapLogger()
        val stagingDir = File(rootDir, "$alias-staging")
        val downloadsDir = File(rootDir, "$alias-downloads")
        val wakeLock = FakeWakeLock()
        val host = RecordingServiceHost(
            onStopRequested = {
                if (controllerFlow.value === controller) {
                    controllerFlow.value = null
                }
            },
        )
        val sink = AndroidFileSink(
            stagingDirectory = stagingDir,
            publisher = DirectoryDestinationPublisher(downloadsDir),
            onCommitted = { descriptor -> controller.recordCommittedFile(descriptor) },
            logger = logger,
        )
        val controller: FsapForegroundNodeController = FsapForegroundNodeController(
            localDeviceId = PlatformUuidGenerator.nextUuidV4(),
            initialAlias = alias,
            deviceType = DeviceType.MOBILE,
            initialPort = 0,
            fileSink = sink,
            multicastTransportFactory = {
                AndroidMulticastTransport(
                    lockManager = AndroidMulticastLockManager(lockFactory = { FakeMulticastLock() }, logger = logger),
                    delegate = LoopbackIdleMulticastTransport(),
                    logger = logger,
                )
            },
            localAddressesProvider = { listOf("127.0.0.1") },
            wakeLockManager = AndroidPartialWakeLockManager(factory = { wakeLock }, logger = logger),
            serviceHost = host,
            approvalHandler = approvalCoordinator,
            logger = logger,
        )
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = AndroidFileShareRuntime(
            localDeviceId = controller.localDeviceId,
            initialAlias = alias,
            initialPort = 0,
            controllerFlow = controllerFlow,
            approvalCoordinator = approvalCoordinator,
            startServiceAndAwaitController = { _, _ ->
                controllerFlow.value = controller
                controller
            },
            filePicker = filePicker,
            initialAddressesProvider = { listOf("127.0.0.1") },
            ensureNotificationPermission = { true },
            logger = logger,
            parentScope = uiScope,
            ownsScope = false,
        )
        val mainVm = FileShareMainViewModel(runtime = runtime, scope = uiScope, logger = logger)
        val manualVm = ManualIpConnectViewModel(
            connectPort = { ip, port -> runtime.connectByManualIp(ip, port) },
            scope = uiScope,
            onConnected = { peer -> mainVm.selectPeer(peer.identity.deviceId) },
        )
        val approvalVm = IncomingApprovalViewModel(
            coordinator = runtime.approvalCoordinator,
            scope = uiScope,
            clock = runtime.clock,
            onAccepted = { mainVm.selectTab(MainTab.TRANSFERS) },
        )
        val progressVm = TransferProgressViewModel(
            snapshotsFlow = runtime.transferSnapshots,
            cancelPort = { id, reason -> runtime.cancelTransfer(id, reason) },
            scope = uiScope,
        )

        fun close() {
            runtime.close()
            uiScope.cancel()
            controller.close()
        }
    }

    @Test
    fun desktopToAndroidMultiChunkTransferUpdatesProgressNotificationsAndCommitsAtomically() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-and005-d2a-").toFile()
        val androidNode = AndroidTestNodeHarness(alias = "Moto-G54", rootDir = tempRoot)
        val desktopLogger = InMemoryFsapLogger()
        val desktopIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Desktop-Lab",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val desktopClient = FsapHttpClient(localIdentity = desktopIdentity, logger = desktopLogger)
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val desktopCoordinator = TransferCoordinator(
            httpClient = desktopClient,
            parentScope = desktopScope,
            logger = desktopLogger,
        )

        try {
            androidNode.mainVm.startNodeInternal()
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
            }
            val androidPort = androidNode.mainVm.uiState.value.boundHttpPort
            assertTrue(androidPort > 0)
            assertTrue(androidNode.wakeLock.isHeld)

            val payload = ByteArray(384 * 1024) { idx -> ((idx * 31) % 251).toByte() }
            val expectedSha = sha256Hex(payload)

            val sendJob = async {
                desktopCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint("127.0.0.1", androidPort),
                    peerAlias = "Moto-G54",
                    sources = listOf(InMemoryFileSource("relatorio-redes.bin", payload)),
                )
            }

            withTimeout(4_000) {
                while (androidNode.approvalVm.uiState.value.activePrompt == null) delay(10)
            }
            val prompt = androidNode.approvalVm.uiState.value.activePrompt
            assertNotNull(prompt)
            assertEquals("Desktop-Lab", prompt.senderAlias)
            androidNode.approvalVm.acceptActive()

            val outcome = withTimeout(10_000) { sendJob.await() }
            assertTrue(outcome is SendFilesOutcome.Completed, "Esperado Completed, obtido $outcome")

            withTimeout(5_000) {
                while (androidNode.mainVm.uiState.value.receivedFiles.isEmpty()) delay(10)
                while (androidNode.progressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) delay(10)
                while (androidNode.host.snapshotNotifications().lastOrNull()?.hasActiveTransfers == true) delay(10)
            }

            val completedItem = androidNode.progressVm.uiState.value.items.first { it.state == TransferState.COMPLETED }
            assertEquals("Recebendo", completedItem.directionLabel)
            assertEquals("100%", completedItem.percentLabel)

            val notifications = androidNode.host.snapshotNotifications()
            assertTrue(
                notifications.any { it.hasActiveTransfers && it.title.startsWith("Recebendo:") && it.progressPercent != null },
                "Deveria emitir notificacao de progresso ativa durante recebimento: $notifications",
            )
            assertFalse(notifications.last().hasActiveTransfers, "Notificacao final deve voltar ao estado ocioso")

            val savedFile = File(androidNode.downloadsDir, "relatorio-redes.bin")
            assertTrue(savedFile.exists())
            assertContentEquals(payload, savedFile.readBytes())
            assertEquals(expectedSha, androidNode.mainVm.uiState.value.receivedFiles.first().sha256)
            assertTrue(androidNode.stagingDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            desktopCoordinator.close()
            desktopClient.close()
            desktopScope.cancel()
            androidNode.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidSafToDesktopServerTransfersMultipleFilesAndPreservesHistoryAcrossRestart() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-and005-a2d-").toFile()
        val desktopDownloads = tempRoot.toPath().resolve("desktop-downloads")
        val desktopSink = TestDesktopNioFileSink(desktopDownloads)
        val desktopLogger = InMemoryFsapLogger()
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val desktopTokenManager = TransferTokenManager(logger = desktopLogger)
        val desktopSessionManager = ServerTransferSessionManager(
            tokenManager = desktopTokenManager,
            fileSink = desktopSink,
            scope = desktopScope,
            logger = desktopLogger,
        )
        val desktopApproval = InteractiveTransferApprovalCoordinator()
        val desktopIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Workstation-Desktop",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
        )
        val desktopServer = FsapHttpServer(
            config = FsapHttpServerConfig(identity = desktopIdentity, host = "127.0.0.1", port = 0),
            logger = desktopLogger,
            transferServices = FsapTransferServerServices(
                sessionManager = desktopSessionManager,
                fileSink = desktopSink,
                approvalHandler = desktopApproval,
            ),
        )
        val desktopApprovalVm = IncomingApprovalViewModel(coordinator = desktopApproval, scope = desktopScope)

        val videoBytes = ByteArray(220 * 1024) { idx -> ((idx * 17) % 239).toByte() }
        val slidesBytes = ByteArray(64 * 1024) { idx -> ((idx * 7) % 197).toByte() }
        val safAccess = FakeSafContentAccess(
            mapOf(
                "content://saf/1" to (
                    AndroidDocumentMetadata("apresentacao.mp4", videoBytes.size.toLong(), "video/mp4") to
                        { ByteArrayInputStream(videoBytes) }
                    ),
                "content://saf/2" to (
                    AndroidDocumentMetadata("roteiro.pdf", slidesBytes.size.toLong(), "application/pdf") to
                        { ByteArrayInputStream(slidesBytes) }
                    ),
            ),
        )
        val safSources = listOf(
            AndroidSafFileSource("content://saf/1", safAccess),
            AndroidSafFileSource("content://saf/2", safAccess),
        )

        val androidNode = AndroidTestNodeHarness(
            alias = "Pixel-Android",
            rootDir = tempRoot,
            filePicker = { safSources },
        )

        try {
            val desktopPort = desktopServer.start()
            androidNode.mainVm.startNodeInternal()
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
            }

            androidNode.manualVm.openDialog()
            androidNode.manualVm.onIpInputChange("127.0.0.1")
            androidNode.manualVm.onPortInputChange(desktopPort.toString())
            assertTrue(androidNode.manualVm.submitInternal())
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.peers.isEmpty()) delay(10)
            }

            androidNode.mainVm.pickFilesInternal()
            assertEquals(2, androidNode.mainVm.uiState.value.selectedFiles.size)
            androidNode.mainVm.sendSelectedFilesInternal()

            withTimeout(4_000) {
                while (desktopApprovalVm.uiState.value.activePrompt == null) delay(10)
            }
            assertEquals("Pixel-Android", desktopApprovalVm.uiState.value.activePrompt?.senderAlias)
            desktopApprovalVm.acceptActive()

            withTimeout(10_000) {
                while (androidNode.progressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) delay(10)
                while (androidNode.host.snapshotNotifications().lastOrNull()?.hasActiveTransfers == true) delay(10)
            }

            assertContentEquals(videoBytes, Files.readAllBytes(desktopDownloads.resolve("apresentacao.mp4")))
            assertContentEquals(slidesBytes, Files.readAllBytes(desktopDownloads.resolve("roteiro.pdf")))
            assertTrue(desktopSink.listPartFiles().isEmpty())

            val sentSnapshot = androidNode.progressVm.uiState.value.items.first { it.state == TransferState.COMPLETED }
            assertEquals("Enviando", sentSnapshot.directionLabel)
            assertEquals("2/2 arquivo(s)", sentSnapshot.filesSummary)
            assertEquals("100%", sentSnapshot.percentLabel)

            assertTrue(
                androidNode.host.snapshotNotifications().any {
                    it.hasActiveTransfers && it.title.startsWith("Enviando:") && it.progressPercent != null
                },
            )

            // Parar servico sincroniza UI e preserva historico em TransferProgressViewModel
            androidNode.controller.stopNode("NOTIFICATION_STOP")
            withTimeout(3_000) {
                while (androidNode.mainVm.uiState.value.nodeState != LocalNodeState.STOPPED) delay(10)
            }
            assertFalse(androidNode.progressVm.uiState.value.items.isEmpty())
        } finally {
            androidNode.close()
            desktopServer.stop(200, 500)
            desktopSessionManager.close()
            desktopScope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidToAndroidBidirectionalTransferBetweenTwoControllersSucceeds() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-and005-a2a-").toFile()
        val payload = ByteArray(192 * 1024) { idx -> ((idx * 13) % 253).toByte() }
        val safAccess = FakeSafContentAccess(
            mapOf(
                "content://saf/a2a" to (
                    AndroidDocumentMetadata("foto-campo.jpg", payload.size.toLong(), "image/jpeg") to
                        { ByteArrayInputStream(payload) }
                    ),
            ),
        )
        val sender = AndroidTestNodeHarness(
            alias = "Android-Remetente",
            rootDir = File(tempRoot, "sender"),
            filePicker = { listOf(AndroidSafFileSource("content://saf/a2a", safAccess)) },
        )
        val receiver = AndroidTestNodeHarness(
            alias = "Android-Destinatario",
            rootDir = File(tempRoot, "receiver"),
        )

        try {
            sender.mainVm.startNodeInternal()
            receiver.mainVm.startNodeInternal()
            withTimeout(4_000) {
                while (sender.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
                while (receiver.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
            }
            val receiverPort = receiver.mainVm.uiState.value.boundHttpPort

            sender.manualVm.openDialog()
            sender.manualVm.onIpInputChange("127.0.0.1")
            sender.manualVm.onPortInputChange(receiverPort.toString())
            assertTrue(sender.manualVm.submitInternal())
            withTimeout(4_000) {
                while (sender.mainVm.uiState.value.peers.isEmpty()) delay(10)
            }

            sender.mainVm.pickFilesInternal()
            sender.mainVm.sendSelectedFilesInternal()

            withTimeout(4_000) {
                while (receiver.approvalVm.uiState.value.activePrompt == null) delay(10)
            }
            assertEquals("Android-Remetente", receiver.approvalVm.uiState.value.activePrompt?.senderAlias)
            receiver.approvalVm.acceptActive()

            withTimeout(10_000) {
                while (sender.progressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) delay(10)
                while (receiver.mainVm.uiState.value.receivedFiles.isEmpty()) delay(10)
            }

            val saved = File(receiver.downloadsDir, "foto-campo.jpg")
            assertTrue(saved.exists())
            assertContentEquals(payload, saved.readBytes())
            assertEquals(sha256Hex(payload), receiver.mainVm.uiState.value.receivedFiles.first().sha256)
        } finally {
            sender.close()
            receiver.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun notificationCancelActionAbortsActiveTransferAndCleansPartFiles() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-and005-cancel-").toFile()
        val desktopDownloads = tempRoot.toPath().resolve("desktop-downloads")
        val desktopSink = TestDesktopNioFileSink(desktopDownloads)
        val desktopLogger = InMemoryFsapLogger()
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val desktopSessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = desktopLogger),
            fileSink = desktopSink,
            scope = desktopScope,
            logger = desktopLogger,
        )
        val desktopApproval = InteractiveTransferApprovalCoordinator()
        val desktopServer = FsapHttpServer(
            config = FsapHttpServerConfig(
                identity = DeviceIdentity(
                    deviceId = PlatformUuidGenerator.nextUuidV4(),
                    alias = "Desktop-Destino",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.LINUX,
                    protocolVersion = FsapConstants.PROTOCOL_VERSION,
                    httpPort = 0,
                ),
                host = "127.0.0.1",
                port = 0,
            ),
            logger = desktopLogger,
            transferServices = FsapTransferServerServices(
                sessionManager = desktopSessionManager,
                fileSink = desktopSink,
                approvalHandler = desktopApproval,
            ),
        )
        val desktopApprovalVm = IncomingApprovalViewModel(coordinator = desktopApproval, scope = desktopScope)

        val slowBytes = ByteArray(256 * 1024) { idx -> (idx % 127).toByte() }
        val safAccess = FakeSafContentAccess(
            mapOf(
                "content://saf/slow" to (
                    AndroidDocumentMetadata("arquivo-lento.iso", slowBytes.size.toLong(), "application/octet-stream") to {
                        object : InputStream() {
                            private var offset = 0
                            override fun read(): Int {
                                val single = ByteArray(1)
                                val r = read(single, 0, 1)
                                return if (r < 0) -1 else (single[0].toInt() and 0xFF)
                            }

                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (offset >= slowBytes.size) return -1
                                Thread.sleep(20)
                                val toRead = minOf(len, 8 * 1024, slowBytes.size - offset)
                                System.arraycopy(slowBytes, offset, b, off, toRead)
                                offset += toRead
                                return toRead
                            }
                        }
                    }
                    ),
            ),
        )
        val androidNode = AndroidTestNodeHarness(
            alias = "Android-Cancel",
            rootDir = tempRoot,
            filePicker = { listOf(AndroidSafFileSource("content://saf/slow", safAccess)) },
        )

        try {
            val desktopPort = desktopServer.start()
            androidNode.mainVm.startNodeInternal()
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
            }

            androidNode.manualVm.openDialog()
            androidNode.manualVm.onIpInputChange("127.0.0.1")
            androidNode.manualVm.onPortInputChange(desktopPort.toString())
            assertTrue(androidNode.manualVm.submitInternal())
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.peers.isEmpty()) delay(10)
            }

            androidNode.mainVm.pickFilesInternal()
            androidNode.mainVm.sendSelectedFilesInternal()

            withTimeout(4_000) {
                while (desktopApprovalVm.uiState.value.activePrompt == null) delay(10)
            }
            desktopApprovalVm.acceptActive()

            withTimeout(5_000) {
                while (androidNode.progressVm.uiState.value.items.none { it.state == TransferState.TRANSFERRING }) delay(10)
                while (androidNode.host.snapshotNotifications().none { it.hasActiveTransfers && it.progressPercent != null }) {
                    delay(10)
                }
            }

            val cancelledCount = androidNode.controller.cancelActiveTransfers("NOTIFICATION_CANCELLED")
            assertEquals(1, cancelledCount)

            withTimeout(6_000) {
                while (androidNode.progressVm.uiState.value.items.none { it.state == TransferState.CANCELLED }) delay(10)
                while (androidNode.host.snapshotNotifications().lastOrNull()?.hasActiveTransfers == true) delay(10)
            }

            assertFalse(Files.exists(desktopDownloads.resolve("arquivo-lento.iso")))
            withTimeout(3_000) {
                while (desktopSink.listPartFiles().isNotEmpty()) delay(20)
            }
            assertTrue(desktopSink.listPartFiles().isEmpty())
            assertFalse(androidNode.host.snapshotNotifications().last().hasActiveTransfers)
        } finally {
            androidNode.close()
            desktopServer.stop(200, 500)
            desktopSessionManager.close()
            desktopScope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun explicitRejectionOnAndroidPreventsDiskWriteAndMarksTransferRejected() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-and005-reject-").toFile()
        val androidNode = AndroidTestNodeHarness(alias = "Moto-Recusa", rootDir = tempRoot)
        val desktopLogger = InMemoryFsapLogger()
        val desktopClient = FsapHttpClient(
            localIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Desconhecido",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.WINDOWS,
                protocolVersion = FsapConstants.PROTOCOL_VERSION,
                httpPort = FsapConstants.DEFAULT_HTTP_PORT,
            ),
            logger = desktopLogger,
        )
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val desktopCoordinator = TransferCoordinator(
            httpClient = desktopClient,
            parentScope = desktopScope,
            logger = desktopLogger,
        )

        try {
            androidNode.mainVm.startNodeInternal()
            withTimeout(4_000) {
                while (androidNode.mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
            }
            val androidPort = androidNode.mainVm.uiState.value.boundHttpPort

            val payload = "nao-autorizado".encodeToByteArray()
            val sendJob = async {
                desktopCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint("127.0.0.1", androidPort),
                    peerAlias = "Moto-Recusa",
                    sources = listOf(InMemoryFileSource("suspeito.exe", payload)),
                )
            }

            withTimeout(4_000) {
                while (androidNode.approvalVm.uiState.value.activePrompt == null) delay(10)
            }
            androidNode.approvalVm.declineActive()

            val outcome = withTimeout(5_000) { sendJob.await() }
            assertTrue(outcome is SendFilesOutcome.Rejected, "Esperado Rejected, obtido $outcome")

            withTimeout(3_000) {
                while (androidNode.progressVm.uiState.value.items.none { it.state == TransferState.REJECTED }) delay(10)
            }
            assertTrue(androidNode.mainVm.uiState.value.receivedFiles.isEmpty())
            assertFalse(File(androidNode.downloadsDir, "suspeito.exe").exists())
            assertTrue(androidNode.stagingDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            desktopCoordinator.close()
            desktopClient.close()
            desktopScope.cancel()
            androidNode.close()
            tempRoot.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = IncrementalSha256()
        digest.update(bytes, 0, bytes.size)
        return digest.digestHex()
    }
}

