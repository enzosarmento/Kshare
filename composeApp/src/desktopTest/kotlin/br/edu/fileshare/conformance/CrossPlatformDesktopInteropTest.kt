package br.edu.fileshare.conformance

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.runtime.DesktopAppRuntime
import br.edu.fileshare.desktop.server.ServerCliConfig
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.desktop.storage.DesktopNioFileSource
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.discovery.ManualConnectResult
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
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testes automatizados de interoperabilidade multi-no para a Milestone M5 (`TASK-TEST-005`),
 * cobrindo Cliente Desktop (`DesktopAppRuntime`), Servidor Desktop Independente (`ServerMainKt`)
 * e pares configurados com perfil `ANDROID`/`MOBILE`.
 */
@OptIn(ExperimentalPathApi::class)
class CrossPlatformDesktopInteropTest {

    @Test
    fun desktopAppRuntimeToHeadlessServerInteropTransferSucceeds() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-interop-d2s-")
        val clientDownloads = tempRoot.resolve("client-downloads")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val sourceDir = tempRoot.resolve("sources")
        Files.createDirectories(sourceDir)

        val logger = InMemoryFsapLogger()
        val headlessServer = createDesktopHeadlessServer(
            config = ServerCliConfig(
                port = 0,
                alias = "Lab-Headless-Server",
                downloadDir = serverDownloads,
                autoAcceptTest = true,
            ),
            container = DesktopServerContainer(logger = logger),
            enableDiscovery = false,
        )
        val desktopRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Desktop-UI-Sender",
            initialPort = 0,
            downloadDirectory = clientDownloads,
        )

        try {
            val serverPort = headlessServer.start()
            val runtimeResult = desktopRuntime.startNode(alias = "Desktop-UI-Sender", port = 0)
            assertTrue(runtimeResult.isSuccess)

            val connectResult = desktopRuntime.connectByManualIp("127.0.0.1", serverPort)
            assertTrue(connectResult is ManualConnectResult.Connected, "Falha em IDENTIFY manual: $connectResult")
            val serverPeer = connectResult.peer
            assertEquals(DeviceType.SERVER, serverPeer.identity.deviceType)

            val docPayload = ByteArray(180 * 1024) { i -> ((i * 29) % 251).toByte() }
            val imagePayload = ByteArray(95 * 1024) { i -> ((i * 11) % 199).toByte() }
            val docPath = sourceDir.resolve("especificacao.bin").also { Files.write(it, docPayload) }
            val imgPath = sourceDir.resolve("captura.png").also { Files.write(it, imagePayload) }

            val sendResult = desktopRuntime.sendFiles(
                peer = serverPeer,
                sources = listOf(
                    DesktopNioFileSource(docPath),
                    DesktopNioFileSource(imgPath),
                ),
            )
            assertTrue(sendResult.isSuccess)
            val transferId = sendResult.getOrThrow()

            withTimeout(10_000) {
                while (
                    desktopRuntime.transferCoordinator.transfers.value
                        .firstOrNull { it.transferId == transferId }
                        ?.state != TransferState.COMPLETED
                ) {
                    delay(20)
                }
            }

            assertContentEquals(docPayload, Files.readAllBytes(serverDownloads.resolve("especificacao.bin")))
            assertContentEquals(imagePayload, Files.readAllBytes(serverDownloads.resolve("captura.png")))
            val stagingDir = serverDownloads.resolve(".fsap-staging")
            if (Files.isDirectory(stagingDir)) {
                Files.list(stagingDir).use { stream ->
                    assertTrue(stream.noneMatch { it.fileName.toString().endsWith(".part") })
                }
            }
        } finally {
            desktopRuntime.close()
            headlessServer.stopGracefully()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidProfilePeerAndDesktopAppRuntimeBidirectionalApprovalAndRejection() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-interop-a2d-")
        val desktopDownloads = tempRoot.resolve("desktop-downloads")
        val androidDownloads = tempRoot.resolve("android-downloads")
        val logger = InMemoryFsapLogger()

        val desktopRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Desktop-Receptor",
            initialPort = 0,
            downloadDirectory = desktopDownloads,
        )
        val desktopApprovalVm = IncomingApprovalViewModel(
            coordinator = desktopRuntime.approvalCoordinator,
            scope = desktopRuntime.scope,
        )

        val androidScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val androidIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Pixel-8-LAN",
            deviceType = DeviceType.MOBILE,
            platform = PlatformType.ANDROID,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
        )
        val androidSink = DesktopNioFileSink(androidDownloads)
        val androidSessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = logger),
            fileSink = androidSink,
            scope = androidScope,
            logger = logger,
        )
        val androidApprovalCoordinator = InteractiveTransferApprovalCoordinator()
        val androidServer = FsapHttpServer(
            config = FsapHttpServerConfig(identity = androidIdentity, host = "127.0.0.1", port = 0),
            logger = logger,
            transferServices = FsapTransferServerServices(
                sessionManager = androidSessionManager,
                fileSink = androidSink,
                approvalHandler = androidApprovalCoordinator,
            ),
        )
        val androidClient = FsapHttpClient(localIdentity = androidIdentity, logger = logger)
        val androidCoordinator = TransferCoordinator(
            httpClient = androidClient,
            parentScope = androidScope,
            logger = logger,
        )

        try {
            val desktopPort = desktopRuntime.startNode(alias = "Desktop-Receptor", port = 0).getOrThrow()
            val androidPort = androidServer.start()

            // 1. Par Android envia arquivo -> Desktop mostra prompt e aceita
            val acceptedPayload = ByteArray(256 * 1024) { i -> ((i * 7) % 241).toByte() }
            val sendToDesktopJob = async {
                androidCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint("127.0.0.1", desktopPort),
                    peerAlias = "Desktop-Receptor",
                    sources = listOf(InMemoryFileSource("video-aula.bin", acceptedPayload)),
                )
            }

            withTimeout(4_000) {
                while (desktopApprovalVm.uiState.value.activePrompt == null) delay(10)
            }
            assertEquals("Pixel-8-LAN", desktopApprovalVm.uiState.value.activePrompt?.senderAlias)
            desktopApprovalVm.acceptActive()

            val outcome1 = withTimeout(10_000) { sendToDesktopJob.await() }
            assertTrue(outcome1 is SendFilesOutcome.Completed)
            assertContentEquals(acceptedPayload, Files.readAllBytes(desktopDownloads.resolve("video-aula.bin")))
            withTimeout(3_000) {
                while (desktopRuntime.receivedFiles.value.none { it.finalName == "video-aula.bin" }) delay(10)
            }

            // 2. Par Android envia arquivo recusado explicitamente pelo Desktop
            val declinedJob = async {
                androidCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint("127.0.0.1", desktopPort),
                    peerAlias = "Desktop-Receptor",
                    sources = listOf(InMemoryFileSource("indesejado.txt", "recusar".encodeToByteArray())),
                )
            }
            withTimeout(4_000) {
                while (desktopApprovalVm.uiState.value.activePrompt == null) delay(10)
            }
            desktopApprovalVm.declineActive()

            val outcome2 = withTimeout(5_000) { declinedJob.await() }
            assertTrue(outcome2 is SendFilesOutcome.Rejected)
            assertFalse(Files.exists(desktopDownloads.resolve("indesejado.txt")))

            // 3. Sentido inverso Desktop -> Android via IDENTIFY manual + aprovacao no par Android
            val connectResult = desktopRuntime.connectByManualIp("127.0.0.1", androidPort)
            assertTrue(connectResult is ManualConnectResult.Connected)
            assertEquals(PlatformType.ANDROID, connectResult.peer.identity.platform)
            assertEquals(DeviceType.MOBILE, connectResult.peer.identity.deviceType)

            val desktopFilePath = tempRoot.resolve("material-redes.pdf")
            val pdfBytes = ByteArray(128 * 1024) { i -> (i % 193).toByte() }
            Files.write(desktopFilePath, pdfBytes)

            val reverseTransferId = desktopRuntime.sendFiles(
                peer = connectResult.peer,
                sources = listOf(DesktopNioFileSource(desktopFilePath)),
            ).getOrThrow()

            val androidApprovalVm = IncomingApprovalViewModel(
                coordinator = androidApprovalCoordinator,
                scope = androidScope,
            )
            withTimeout(4_000) {
                while (androidApprovalVm.uiState.value.activePrompt == null) delay(10)
            }
            assertEquals("Desktop-Receptor", androidApprovalVm.uiState.value.activePrompt?.senderAlias)
            androidApprovalVm.acceptActive()

            withTimeout(10_000) {
                while (
                    desktopRuntime.transferCoordinator.transfers.value
                        .firstOrNull { it.transferId == reverseTransferId }
                        ?.state != TransferState.COMPLETED
                ) {
                    delay(20)
                }
            }
            assertContentEquals(pdfBytes, Files.readAllBytes(androidDownloads.resolve("material-redes.pdf")))
        } finally {
            androidCoordinator.close()
            androidClient.close()
            androidServer.stop(200, 500)
            androidSessionManager.close()
            androidScope.cancel()
            desktopRuntime.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun largeFileStreamingFromDesktopRuntimeToHeadlessServerPreservesIntegrity() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-interop-large-")
        val clientDownloads = tempRoot.resolve("client-downloads")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val sourceFile = tempRoot.resolve("dataset-8mb.bin")

        val totalSize = 8 * 1024 * 1024
        val sourceSha = writeDeterministicFileAndComputeSha(sourceFile, totalSize)

        val logger = InMemoryFsapLogger()
        val headlessServer = createDesktopHeadlessServer(
            config = ServerCliConfig(
                port = 0,
                alias = "Large-File-Server",
                downloadDir = serverDownloads,
                autoAcceptTest = true,
            ),
            container = DesktopServerContainer(logger = logger),
            enableDiscovery = false,
        )
        val desktopRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Large-File-Client",
            initialPort = 0,
            downloadDirectory = clientDownloads,
        )

        try {
            val serverPort = headlessServer.start()
            desktopRuntime.startNode(alias = "Large-File-Client", port = 0).getOrThrow()

            val connect = desktopRuntime.connectByManualIp("127.0.0.1", serverPort)
            assertTrue(connect is ManualConnectResult.Connected)

            val transferId = desktopRuntime.sendFiles(
                peer = connect.peer,
                sources = listOf(DesktopNioFileSource(sourceFile)),
            ).getOrThrow()

            withTimeout(20_000) {
                while (
                    desktopRuntime.transferCoordinator.transfers.value
                        .firstOrNull { it.transferId == transferId }
                        ?.state != TransferState.COMPLETED
                ) {
                    delay(25)
                }
            }

            val finalFile = serverDownloads.resolve("dataset-8mb.bin")
            assertTrue(Files.exists(finalFile))
            assertEquals(totalSize.toLong(), Files.size(finalFile))
            assertEquals(sourceSha, computeShaStreaming(finalFile))
            val snap = desktopRuntime.transferCoordinator.transfers.value.firstOrNull { it.transferId == transferId }
            assertNotNull(snap)
            assertEquals(totalSize.toLong(), snap.bytesTransferred)
        } finally {
            desktopRuntime.close()
            headlessServer.stopGracefully()
            tempRoot.deleteRecursively()
        }
    }

    private fun writeDeterministicFileAndComputeSha(path: Path, totalBytes: Int): String {
        val digest = IncrementalSha256()
        val block = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
        Files.newOutputStream(path).use { out ->
            var remaining = totalBytes
            var seed = 0
            while (remaining > 0) {
                val len = minOf(block.size, remaining)
                for (i in 0 until len) {
                    block[i] = (((seed + i) * 37) % 251).toByte()
                }
                out.write(block, 0, len)
                digest.update(block, 0, len)
                remaining -= len
                seed += len
            }
        }
        return digest.digestHex()
    }

    private fun computeShaStreaming(path: Path): String {
        val digest = IncrementalSha256()
        val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
        Files.newInputStream(path).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digestHex()
    }
}

