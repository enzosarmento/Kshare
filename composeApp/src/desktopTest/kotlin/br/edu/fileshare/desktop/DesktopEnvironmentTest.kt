package br.edu.fileshare.desktop

import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.desktop.runtime.DesktopAppRuntime
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.TransferProgressViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Teste estrutural e de runtime do sourceSet `desktopTest` (Milestone M0).
 */
class DesktopEnvironmentTest {
    @Test
    fun verifyDesktopSourceSetConfiguration() {
        assertTrue(ProjectInfo.PROTOCOL_VERSION.isNotBlank())
    }

    @Test
    fun `DesktopAppRuntime atualiza alias do httpClient ao reiniciar servidor`() = runBlocking {
        val downloadDir = Files.createTempDirectory("kshare-desktop-alias-test-")
        val runtime = DesktopAppRuntime(
            initialAlias = "Desktop-Original",
            initialPort = 0,
            downloadDirectory = downloadDir,
        )

        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiverSink = InMemoryFileSink()
        val receiverApproval = InteractiveTransferApprovalCoordinator()
        val receiverIdentity = DeviceIdentity(
            deviceId = "99999999-9999-4999-8999-999999999991",
            alias = "Receptor-Desktop",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            httpPort = 0,
        )
        val receiverSessions = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = runtime.logger),
            fileSink = receiverSink,
            scope = receiverScope,
            logger = runtime.logger,
        )
        val receiverClient = br.edu.fileshare.network.client.FsapHttpClient(
            localIdentity = receiverIdentity,
            logger = runtime.logger,
        )
        val receiverCoordinator = TransferCoordinator(
            httpClient = receiverClient,
            parentScope = receiverScope,
            serverSessionManager = receiverSessions,
            logger = runtime.logger,
        )
        val approvalVm = IncomingApprovalViewModel(
            coordinator = receiverApproval,
            scope = receiverScope,
            countdownTickMs = 0L,
        )
        val progressVm = TransferProgressViewModel(
            snapshotsFlow = receiverCoordinator.progressTracker.transfers,
            cancelPort = { id, reason -> receiverCoordinator.cancelTransfer(id, reason) },
            scope = receiverScope,
        )
        val receiverServer = FsapHttpServer(
            config = FsapHttpServerConfig(identity = receiverIdentity, host = "127.0.0.1", port = 0),
            logger = runtime.logger,
            transferServices = FsapTransferServerServices(
                sessionManager = receiverSessions,
                fileSink = receiverSink,
                approvalHandler = receiverApproval,
            ),
        )

        try {
            val receiverPort = receiverServer.start()
            assertTrue(runtime.startNode(alias = "Desktop-Original", port = 0).isSuccess)
            assertTrue(runtime.startNode(alias = "Desktop-Renomeado", port = 0).isSuccess)

            runtime.transferCoordinator.launchSendFiles(
                peer = br.edu.fileshare.network.discovery.DiscoveredPeer(
                    identity = receiverIdentity.copy(httpPort = receiverPort),
                    ipAddress = "127.0.0.1",
                    port = receiverPort,
                    connectionState = br.edu.fileshare.protocol.statemachine.PeerConnectionState.CONNECTED,
                    origin = br.edu.fileshare.network.discovery.PeerOrigin.MANUAL_IP,
                    lastSeenEpochMillis = 0L,
                    firstSeenEpochMillis = 0L,
                ),
                sources = listOf(InMemoryFileSource("desktop.txt", "ola mundo".encodeToByteArray())),
            )

            withTimeout(5_000) {
                while (approvalVm.uiState.value.activePrompt == null) delay(10)
            }
            val prompt = assertNotNull(approvalVm.uiState.value.activePrompt)
            assertEquals("Desktop-Renomeado", prompt.senderAlias)

            approvalVm.acceptActive()
            withTimeout(5_000) {
                while (progressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) delay(10)
            }
            assertEquals("Desktop-Renomeado", progressVm.uiState.value.items.first().peerAlias)
        } finally {
            receiverCoordinator.close()
            receiverClient.close()
            receiverServer.stop()
            receiverSessions.close()
            receiverScope.cancel()
            runtime.close()
            downloadDir.toFile().deleteRecursively()
        }
    }
}

