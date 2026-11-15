package br.edu.fileshare.network.client

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.RejectAllTransferApprovalHandler
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.configureFsapServer
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.TransferProgressViewModel
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsapHttpClientPrepareTest {

    private companion object {
        val SENDER_IDENTITY = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Remetente Desktop",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
        )
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Destinatario Desktop",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
        const val SAMPLE_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test
    fun `TC-FSAP-008 negociacao bem-sucedida avanca FSM local para ACCEPTED e recebe transferToken`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            lateinit var manager: ServerTransferSessionManager

            application {
                manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = AutoAcceptTransferApprovalHandler,
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )

            val transferId = "11111111-1111-4111-8111-111111111111"
            val fsm = TransferStateMachine(transferId = transferId, logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "vazio.txt", 0L, SAMPLE_SHA)),
            )

            val accepted = assertIs<PrepareTransferResult.Accepted>(result)
            assertTrue(accepted.session.transferToken.isNotBlank())
            assertEquals(setOf("f1"), accepted.session.acceptedFileIds)
            assertEquals(TransferState.ACCEPTED, fsm.currentState())
            assertEquals(TransferState.ACCEPTED, manager.findSnapshot(transferId)?.state)
        }

    @Test
    fun `TC-FSAP-011 recusa explicita do destinatario transita FSM local para REJECTED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = RejectAllTransferApprovalHandler(
                            reasonCode = RejectReasonCode.USER_DECLINED,
                            reasonMessage = "Usuario clicou em Recusar.",
                        ),
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )
            val fsm = TransferStateMachine(transferId = "22222222-2222-4222-8222-222222222222", logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = fsm.transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "foto.jpg", 0L, SAMPLE_SHA)),
            )

            val rejected = assertIs<PrepareTransferResult.Rejected>(result)
            assertEquals(RejectReasonCode.USER_DECLINED, rejected.reject.reasonCode)
            assertEquals(TransferState.REJECTED, fsm.currentState())
        }

    @Test
    fun `TC-FSAP-012 timeout de aprovacao retorna APPROVAL_TIMEOUT e transita FSM local para FAILED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val stalledHandler = TransferApprovalHandler { _: ApprovalRequestContext ->
                delay(500)
                TransferApprovalDecision.Approved()
            }

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                    approvalTimeoutMs = 120L,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = stalledHandler,
                        approvalTimeoutMs = 120L,
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                approvalTimeoutMs = 200L,
                providedHttpClient = client,
            )
            val fsm = TransferStateMachine(transferId = "33333333-3333-4333-8333-333333333333", logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = fsm.transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "lento.bin", 0L, SAMPLE_SHA)),
            )

            val failed = assertIs<PrepareTransferResult.Failed>(result)
            assertEquals(FsapErrorCode.APPROVAL_TIMEOUT, failed.errorCode)
            assertEquals(TransferState.FAILED, fsm.currentState())
        }

    @Test
    fun `espaco insuficiente no servidor e nome invalido levam FSM local para FAILED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val smallSink = InMemoryFileSink(availableBytes = 1024L)
            val tokens = TransferTokenManager(logger = logger)

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = smallSink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = smallSink,
                        approvalHandler = AutoAcceptTransferApprovalHandler,
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )

            // Validacao local de Path Traversal falha antes mesmo de transmitir
            val badNameFsm = TransferStateMachine(transferId = "44444444-4444-4444-8444-444444444444")
            val badNameRes = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint("localhost", 80),
                transferId = badNameFsm.transferId,
                stateMachine = badNameFsm,
                files = listOf(FileItemDto("f1", "../segredo.txt", 10L, SAMPLE_SHA)),
            )
            val badNameFailed = assertIs<PrepareTransferResult.Failed>(badNameRes)
            assertEquals(FsapErrorCode.INVALID_FILENAME, badNameFailed.errorCode)
            assertEquals(TransferState.FAILED, badNameFsm.currentState())

            // Rejeicao 507 Insufficient Storage no servidor
            val noSpaceFsm = TransferStateMachine(transferId = "55555555-5555-4555-8555-555555555555")
            val noSpaceRes = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint("localhost", 80),
                transferId = noSpaceFsm.transferId,
                stateMachine = noSpaceFsm,
                files = listOf(FileItemDto("f1", "grande.iso", 500_000L, SAMPLE_SHA)),
            )
            val noSpaceFailed = assertIs<PrepareTransferResult.Failed>(noSpaceRes)
            assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, noSpaceFailed.errorCode)
            assertEquals(TransferState.FAILED, noSpaceFsm.currentState())
        }

    @Test
    fun `alterar alias propaga novo nome do remetente no modal de aprovacao, aba Transferencias e PeerRegistry`() =
        runBlocking {
            val logger = InMemoryFsapLogger()
            val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val approvalCoordinator = InteractiveTransferApprovalCoordinator()
            val receiverPeerRegistry = PeerRegistry(logger = logger)
            val serverSessionManager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = receiverScope,
                logger = logger,
            )
            val receiverClient = FsapHttpClient(localIdentity = SERVER_IDENTITY, logger = logger)
            val receiverCoordinator = TransferCoordinator(
                httpClient = receiverClient,
                parentScope = receiverScope,
                serverSessionManager = serverSessionManager,
                logger = logger,
                peerRegistry = receiverPeerRegistry,
            )
            val approvalVm = IncomingApprovalViewModel(
                coordinator = approvalCoordinator,
                scope = receiverScope,
                countdownTickMs = 0L,
            )
            val receiverProgressVm = TransferProgressViewModel(
                snapshotsFlow = receiverCoordinator.progressTracker.transfers,
                cancelPort = { id, reason -> receiverCoordinator.cancelTransfer(id, reason) },
                scope = receiverScope,
            )

            val server = FsapHttpServer(
                config = FsapHttpServerConfig(identity = SERVER_IDENTITY, host = "127.0.0.1", port = 0),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = serverSessionManager,
                    fileSink = sink,
                    approvalHandler = approvalCoordinator,
                ),
            )

            var currentSenderIdentity = SENDER_IDENTITY.copy(alias = "Alias-Antigo")
            receiverPeerRegistry.registerManualPeer(currentSenderIdentity, ipAddress = "127.0.0.1")

            val senderClientWithProvider = FsapHttpClient(
                localIdentity = currentSenderIdentity,
                logger = logger,
                localIdentityProvider = { currentSenderIdentity },
            )
            val senderCoordinatorWithProvider = TransferCoordinator(
                httpClient = senderClientWithProvider,
                parentScope = senderScope,
                logger = logger,
            )

            val senderClientUpdatedExplicitly = FsapHttpClient(
                localIdentity = SENDER_IDENTITY.copy(alias = "Alias-Inicial-Explicit"),
                logger = logger,
            )
            val senderCoordinatorExplicit = TransferCoordinator(
                httpClient = senderClientUpdatedExplicitly,
                parentScope = senderScope,
                logger = logger,
            )

            try {
                val boundPort = server.start()
                val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = boundPort)

                // 1. Primeira transferencia com o alias antigo
                val tx1 = "66666666-6666-4666-8666-666666666661"
                val sendJob1 = async {
                    senderCoordinatorWithProvider.sendFiles(
                        endpoint = endpoint,
                        peerAlias = SERVER_IDENTITY.alias,
                        sources = listOf(InMemoryFileSource("doc1.txt", "primeiro envio".encodeToByteArray())),
                        transferId = tx1,
                    )
                }
                withTimeout(3_000) {
                    while (approvalVm.uiState.value.activePrompt?.transferId != tx1) delay(10)
                }
                assertEquals("Alias-Antigo", approvalVm.uiState.value.activePrompt?.senderAlias)
                approvalVm.acceptActive()
                assertIs<SendFilesOutcome.Completed>(withTimeout(3_000) { sendJob1.await() })
                withTimeout(3_000) {
                    while (receiverProgressVm.uiState.value.items.none { it.transferId == tx1 && it.state == TransferState.COMPLETED }) {
                        delay(10)
                    }
                }
                assertEquals(
                    "Alias-Antigo",
                    receiverProgressVm.uiState.value.items.first { it.transferId == tx1 }.peerAlias,
                )

                // 2. Usuario altera o nome do dispositivo via localIdentityProvider e envia novamente
                currentSenderIdentity = currentSenderIdentity.copy(alias = "Tablet-Renomeado")
                val tx2 = "66666666-6666-4666-8666-666666666662"
                val sendJob2 = async {
                    senderCoordinatorWithProvider.sendFiles(
                        endpoint = endpoint,
                        peerAlias = SERVER_IDENTITY.alias,
                        sources = listOf(InMemoryFileSource("doc2.txt", "segundo envio".encodeToByteArray())),
                        transferId = tx2,
                    )
                }
                withTimeout(3_000) {
                    while (approvalVm.uiState.value.activePrompt?.transferId != tx2) delay(10)
                }
                assertEquals("Tablet-Renomeado", approvalVm.uiState.value.activePrompt?.senderAlias)
                approvalVm.acceptActive()
                assertIs<SendFilesOutcome.Completed>(withTimeout(3_000) { sendJob2.await() })
                withTimeout(3_000) {
                    while (receiverProgressVm.uiState.value.items.none { it.transferId == tx2 && it.state == TransferState.COMPLETED }) {
                        delay(10)
                    }
                }
                assertEquals(
                    "Tablet-Renomeado",
                    receiverProgressVm.uiState.value.items.first { it.transferId == tx2 }.peerAlias,
                )
                assertEquals("Tablet-Renomeado", receiverPeerRegistry.findById(SENDER_IDENTITY.deviceId)?.alias)

                // 3. Atualizacao explicita via updateLocalIdentity sem localIdentityProvider
                senderClientUpdatedExplicitly.updateLocalIdentity(
                    SENDER_IDENTITY.copy(alias = "Desktop-Final"),
                )
                val tx3 = "66666666-6666-4666-8666-666666666663"
                val sendJob3 = async {
                    senderCoordinatorExplicit.sendFiles(
                        endpoint = endpoint,
                        peerAlias = SERVER_IDENTITY.alias,
                        sources = listOf(InMemoryFileSource("doc3.txt", "terceiro envio".encodeToByteArray())),
                        transferId = tx3,
                    )
                }
                withTimeout(3_000) {
                    while (approvalVm.uiState.value.activePrompt?.transferId != tx3) delay(10)
                }
                assertEquals("Desktop-Final", approvalVm.uiState.value.activePrompt?.senderAlias)
                approvalVm.acceptActive()
                assertIs<SendFilesOutcome.Completed>(withTimeout(3_000) { sendJob3.await() })
                withTimeout(3_000) {
                    while (receiverProgressVm.uiState.value.items.none { it.transferId == tx3 && it.state == TransferState.COMPLETED }) {
                        delay(10)
                    }
                }
                assertEquals(
                    "Desktop-Final",
                    receiverProgressVm.uiState.value.items.first { it.transferId == tx3 }.peerAlias,
                )
            } finally {
                senderCoordinatorWithProvider.close()
                senderCoordinatorExplicit.close()
                senderClientWithProvider.close()
                senderClientUpdatedExplicitly.close()
                receiverCoordinator.close()
                receiverClient.close()
                server.stop()
                serverSessionManager.close()
                senderScope.cancel()
                receiverScope.cancel()
            }
        }
}
