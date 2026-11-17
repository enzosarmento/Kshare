package br.edu.fileshare.android.runtime

import br.edu.fileshare.android.network.AndroidMulticastLockManager
import br.edu.fileshare.android.network.AndroidMulticastTransport
import br.edu.fileshare.android.network.MulticastLockHandle
import br.edu.fileshare.android.service.AndroidNodeIdentityHelper
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
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.discovery.UdpDatagram
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
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
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFileShareRuntimeTest {

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

    private class InMemoryMulticastTransport : MulticastTransport {
        private var closedSignal = CompletableDeferred<Unit>()
        override var isOpen: Boolean = false
            private set
        override val boundPort: Int = 53318

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

    private class RecordingHost(
        private val onStopped: () -> Unit = {},
    ) : FsapServiceHost {
        val notifications = mutableListOf<FsapNotificationState>()

        override fun enterForeground(state: FsapNotificationState) {
            synchronized(notifications) { notifications += state }
        }

        override fun updateNotification(state: FsapNotificationState) {
            synchronized(notifications) { notifications += state }
        }

        override fun exitForegroundAndStopSelf() {
            onStopped()
        }
    }

    private class FakeSafContentAccess(
        private val entries: Map<String, Pair<AndroidDocumentMetadata, ByteArray>>,
    ) : AndroidContentAccess {
        override fun queryMetadata(uriString: String): AndroidDocumentMetadata? = entries[uriString]?.first
        override fun openInputStream(uriString: String): InputStream? =
            entries[uriString]?.second?.let { ByteArrayInputStream(it) }
    }

    @Test
    fun runtimeIntegratesViewModelsManualIpSafPickerAndApprovalPromptWithoutBlockingWhenPermissionDenied() =
        runBlocking {
            val tempRoot = Files.createTempDirectory("fsap-android-runtime-").toFile()
            val stagingDir = File(tempRoot, "staging")
            val downloadsDir = File(tempRoot, "downloads")
            val logger = InMemoryFsapLogger()

            val controllerFlow = MutableStateFlow<FsapForegroundNodeController?>(null)
            val approvalCoordinator = InteractiveTransferApprovalCoordinator()
            lateinit var controller: FsapForegroundNodeController
            val host = RecordingHost(
                onStopped = {
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

            controller = FsapForegroundNodeController(
                localDeviceId = PlatformUuidGenerator.nextUuidV4(),
                initialAlias = "Moto-G54",
                deviceType = DeviceType.MOBILE,
                initialPort = 0,
                fileSink = sink,
                multicastTransportFactory = {
                    AndroidMulticastTransport(
                        lockManager = AndroidMulticastLockManager(lockFactory = { FakeMulticastLock() }, logger = logger),
                        delegate = InMemoryMulticastTransport(),
                        logger = logger,
                    )
                },
                localAddressesProvider = { listOf("127.0.0.1") },
                wakeLockManager = AndroidPartialWakeLockManager(factory = { FakeWakeLock() }, logger = logger),
                serviceHost = host,
                approvalHandler = approvalCoordinator,
                logger = logger,
            )

            val safUri = "content://com.android.providers.downloads.documents/document/77"
            val safPayload = "conteudo-via-saf-android".encodeToByteArray()
            val safSource = AndroidSafFileSource(
                uriString = safUri,
                contentAccess = FakeSafContentAccess(
                    mapOf(
                        safUri to (
                            AndroidDocumentMetadata(
                                displayName = "slides-redes.txt",
                                sizeBytes = safPayload.size.toLong(),
                                mimeType = "text/plain",
                            ) to safPayload
                        ),
                    ),
                ),
            )

            var serviceStartCalls = 0
            var permissionChecks = 0
            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val runtime = AndroidFileShareRuntime(
                localDeviceId = controller.localDeviceId,
                initialAlias = "Moto-G54",
                initialPort = 0,
                controllerFlow = controllerFlow,
                approvalCoordinator = approvalCoordinator,
                startServiceAndAwaitController = { _, _ ->
                    serviceStartCalls++
                    controllerFlow.value = controller
                    controller
                },
                filePicker = { listOf(safSource) },
                initialAddressesProvider = { listOf("127.0.0.1") },
                ensureNotificationPermission = {
                    permissionChecks++
                    false // Negacao de POST_NOTIFICATIONS nao pode impedir inicializacao da rede
                },
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

            val senderIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Notebook-Professor",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                protocolVersion = FsapConstants.PROTOCOL_VERSION,
                httpPort = FsapConstants.DEFAULT_HTTP_PORT,
            )
            val senderClient = FsapHttpClient(localIdentity = senderIdentity, logger = logger)
            val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val senderCoordinator = TransferCoordinator(httpClient = senderClient, parentScope = senderScope, logger = logger)

            try {
                // 1. Inicia no Android via ViewModel -> deve acionar serviceStart + seguir sem notificacao
                mainVm.startNodeInternal()
                withTimeout(3_000) {
                    while (mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
                }
                assertEquals(1, permissionChecks)
                assertEquals(1, serviceStartCalls)
                val boundPort = mainVm.uiState.value.boundHttpPort
                assertTrue(boundPort > 0)

                // 2. Conexao manual por IP valida self/loopback e seleciona par na lista Android
                manualVm.openDialog()
                manualVm.onIpInputChange("127.0.0.1")
                manualVm.onPortInputChange(boundPort.toString())
                assertTrue(manualVm.submitInternal())
                withTimeout(3_000) {
                    while (mainVm.uiState.value.peers.isEmpty()) delay(10)
                }
                assertEquals(controller.localDeviceId, mainVm.uiState.value.selectedPeerDeviceId)

                // 3. Selecao via SAF popula fila de arquivos prontos na UI
                mainVm.pickFilesInternal()
                assertEquals(1, mainVm.uiState.value.selectedFiles.size)
                assertEquals("slides-redes.txt", mainVm.uiState.value.selectedFiles.first().fileName)

                // 4. Desktop envia arquivo -> Modal de confirmacao aparece na UI Android e aceita explicitamente
                val incomingPayload = ByteArray(96 * 1024) { idx -> (idx % 199).toByte() }
                val sendJob = async {
                    senderCoordinator.sendFiles(
                        endpoint = FsapPeerEndpoint("127.0.0.1", boundPort),
                        peerAlias = "Moto-G54",
                        sources = listOf(InMemoryFileSource("trabalho-final.bin", incomingPayload)),
                    )
                }

                withTimeout(5_000) {
                    while (approvalVm.uiState.value.activePrompt == null) delay(10)
                }
                val prompt = approvalVm.uiState.value.activePrompt
                assertNotNull(prompt)
                assertEquals("Notebook-Professor", prompt.senderAlias)
                assertEquals(listOf("trabalho-final.bin"), prompt.files.map { it.fileName })

                approvalVm.acceptActive()
                withTimeout(5_000) {
                    val outcome = sendJob.await()
                    assertTrue(outcome is SendFilesOutcome.Completed, "Esperado Completed, obtido $outcome")
                }

                withTimeout(5_000) {
                    while (mainVm.uiState.value.receivedFiles.isEmpty()) delay(10)
                    while (progressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) delay(10)
                }
                assertEquals(MainTab.TRANSFERS, mainVm.uiState.value.activeTab)
                assertEquals("trabalho-final.bin", mainVm.uiState.value.receivedFiles.first().finalName)
                assertContentEquals(incomingPayload, File(downloadsDir, "trabalho-final.bin").readBytes())

                // 5. Parada externa via notificacao sincroniza ViewModel para STOPPED preservando historico!
                controller.stopNode(reason = "NOTIFICATION_STOP")
                withTimeout(3_000) {
                    while (mainVm.uiState.value.nodeState != LocalNodeState.STOPPED) delay(10)
                }
                assertNull(controllerFlow.value)
                assertFalse(mainVm.uiState.value.receivedFiles.isEmpty(), "Arquivos recebidos devem permanecer visiveis.")
                assertFalse(progressVm.uiState.value.items.isEmpty(), "Historico de transferencia deve permanecer visivel.")
            } finally {
                senderCoordinator.close()
                senderClient.close()
                senderScope.cancel()
                runtime.close()
                uiScope.cancel()
                controller.close()
                tempRoot.deleteRecursively()
            }
        }

    @Test
    fun applySettingsAndRestartKeepsForegroundControllerAliveAndServesUpdatedAliasPortAndTls() =
        runBlocking {
            val tempRoot = Files.createTempDirectory("fsap-android-restart-").toFile()
            val stagingDir = File(tempRoot, "staging")
            val downloadsDir = File(tempRoot, "downloads")
            val logger = InMemoryFsapLogger()

            val initialPort = ServerSocket(0).use { it.localPort }
            val reconfiguredPort = ServerSocket(0).use { it.localPort }

            val controllerFlow = MutableStateFlow<FsapForegroundNodeController?>(null)
            val approvalCoordinator = InteractiveTransferApprovalCoordinator()
            var stopCalls = 0
            lateinit var controller: FsapForegroundNodeController
            val host = RecordingHost(
                onStopped = {
                    stopCalls++
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

            controller = FsapForegroundNodeController(
                localDeviceId = PlatformUuidGenerator.nextUuidV4(),
                initialAlias = "Moto-G54",
                deviceType = DeviceType.MOBILE,
                initialPort = initialPort,
                fileSink = sink,
                multicastTransportFactory = {
                    AndroidMulticastTransport(
                        lockManager = AndroidMulticastLockManager(lockFactory = { FakeMulticastLock() }, logger = logger),
                        delegate = InMemoryMulticastTransport(),
                        logger = logger,
                    )
                },
                localAddressesProvider = { listOf("127.0.0.1") },
                wakeLockManager = AndroidPartialWakeLockManager(factory = { FakeWakeLock() }, logger = logger),
                serviceHost = host,
                approvalHandler = approvalCoordinator,
                logger = logger,
            )

            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = AndroidFileShareRuntime(
                localDeviceId = controller.localDeviceId,
                initialAlias = "Moto-G54",
                initialPort = initialPort,
                controllerFlow = controllerFlow,
                approvalCoordinator = approvalCoordinator,
                startServiceAndAwaitController = { _, _ ->
                    controllerFlow.value = controller
                    controller
                },
                filePicker = { emptyList() },
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

            try {
                mainVm.startNodeInternal()
                withTimeout(3_000) {
                    while (mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
                }
                assertEquals(0, stopCalls)

                // 1. Renomear alias na mesma porta HTTP deve manter servico vivo e atualizar IDENTIFY
                mainVm.updateSettingsDraft(
                    alias = "Galaxy-Renomeado",
                    portText = initialPort.toString(),
                    tlsEnabled = false,
                )
                mainVm.applySettingsAndRestartInternal()

                assertEquals(0, stopCalls)
                assertEquals(controller, controllerFlow.value)
                assertEquals(LocalNodeState.AVAILABLE, mainVm.uiState.value.nodeState)
                assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
                assertEquals("Galaxy-Renomeado", controller.localAlias.value)

                manualVm.openDialog()
                manualVm.onIpInputChange("127.0.0.1")
                manualVm.onPortInputChange(initialPort.toString())
                assertTrue(manualVm.submitInternal())
                withTimeout(3_000) {
                    while (mainVm.uiState.value.peers.none { it.identity.alias == "Galaxy-Renomeado" }) delay(10)
                }

                // 2. Alterar alias + porta + habilitar HTTPS/TLS deve reiniciar servidor na nova porta
                mainVm.updateSettingsDraft(
                    alias = "Galaxy-TLS",
                    portText = reconfiguredPort.toString(),
                    tlsEnabled = true,
                )
                mainVm.applySettingsAndRestartInternal()

                assertEquals(0, stopCalls)
                assertEquals(LocalNodeState.AVAILABLE, mainVm.uiState.value.nodeState)
                assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
                assertEquals(reconfiguredPort, mainVm.uiState.value.boundHttpPort)
                assertTrue(mainVm.uiState.value.tlsEnabled)
                val fingerprint = assertNotNull(mainVm.uiState.value.localTlsFingerprint)

                val tlsConnect = runtime.connectByManualIp(
                    ipAddress = "127.0.0.1",
                    port = reconfiguredPort,
                    useTls = true,
                    expectedTlsFingerprintSha256 = fingerprint,
                )
                assertTrue(tlsConnect is ManualConnectResult.Connected, "Esperado Connected via TLS, obtido $tlsConnect")
                assertEquals("Galaxy-TLS", tlsConnect.peer.identity.alias)

                // 3. Novo ajuste rapido mantendo a mesma porta e TLS ligado
                mainVm.updateSettingsDraft(
                    alias = "Galaxy-Final",
                    portText = reconfiguredPort.toString(),
                    tlsEnabled = true,
                )
                mainVm.applySettingsAndRestartInternal()

                assertEquals(0, stopCalls)
                assertEquals(LocalNodeState.AVAILABLE, mainVm.uiState.value.nodeState)
                assertEquals(LocalNodeState.AVAILABLE, controller.nodeState.value)
                assertEquals("Galaxy-Final", controller.localAlias.value)
                val finalConnect = runtime.connectByManualIp(
                    ipAddress = "127.0.0.1",
                    port = reconfiguredPort,
                    useTls = true,
                    expectedTlsFingerprintSha256 = assertNotNull(mainVm.uiState.value.localTlsFingerprint),
                )
                assertTrue(finalConnect is ManualConnectResult.Connected, "Esperado Connected final via TLS, obtido $finalConnect")
                assertEquals("Galaxy-Final", finalConnect.peer.identity.alias)
            } finally {
                runtime.close()
                uiScope.cancel()
                controller.close()
                tempRoot.deleteRecursively()
            }
        }

    @Test
    fun renamingAliasPropagatesNewSenderNameToReceiverModalAndTransfersTab() =
        runBlocking {
            val tempRoot = Files.createTempDirectory("fsap-android-alias-xfer-").toFile()
            val logger = InMemoryFsapLogger()
            val controllerFlow = MutableStateFlow<FsapForegroundNodeController?>(null)
            val senderApproval = InteractiveTransferApprovalCoordinator()
            val host = RecordingHost()

            val controller = FsapForegroundNodeController(
                localDeviceId = PlatformUuidGenerator.nextUuidV4(),
                initialAlias = "Galaxy-Tab-Antigo",
                deviceType = DeviceType.TABLET,
                initialPort = 0,
                fileSink = AndroidFileSink(
                    stagingDirectory = File(tempRoot, "staging"),
                    publisher = DirectoryDestinationPublisher(File(tempRoot, "downloads")),
                    logger = logger,
                ),
                multicastTransportFactory = {
                    AndroidMulticastTransport(
                        lockManager = AndroidMulticastLockManager(lockFactory = { FakeMulticastLock() }, logger = logger),
                        delegate = InMemoryMulticastTransport(),
                        logger = logger,
                    )
                },
                localAddressesProvider = { listOf("127.0.0.1") },
                wakeLockManager = AndroidPartialWakeLockManager(factory = { FakeWakeLock() }, logger = logger),
                serviceHost = host,
                approvalHandler = senderApproval,
                logger = logger,
            )

            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = AndroidFileShareRuntime(
                localDeviceId = controller.localDeviceId,
                initialAlias = "Galaxy-Tab-Antigo",
                initialPort = 0,
                controllerFlow = controllerFlow,
                approvalCoordinator = senderApproval,
                startServiceAndAwaitController = { _, _ ->
                    controllerFlow.value = controller
                    controller
                },
                filePicker = { emptyList() },
                initialAddressesProvider = { listOf("127.0.0.1") },
                ensureNotificationPermission = { true },
                logger = logger,
                parentScope = uiScope,
                ownsScope = false,
            )

            val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val receiverSink = InMemoryFileSink()
            val receiverApproval = InteractiveTransferApprovalCoordinator()
            val receiverIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Desktop-Receptor",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                httpPort = 0,
            )
            val receiverSessions = ServerTransferSessionManager(
                tokenManager = TransferTokenManager(logger = logger),
                fileSink = receiverSink,
                scope = receiverScope,
                logger = logger,
            )
            val receiverRegistry = PeerRegistry(logger = logger)
            val receiverClient = FsapHttpClient(localIdentity = receiverIdentity, logger = logger)
            val receiverCoordinator = TransferCoordinator(
                httpClient = receiverClient,
                parentScope = receiverScope,
                serverSessionManager = receiverSessions,
                logger = logger,
                peerRegistry = receiverRegistry,
            )
            val receiverApprovalVm = IncomingApprovalViewModel(
                coordinator = receiverApproval,
                scope = receiverScope,
                countdownTickMs = 0L,
            )
            val receiverProgressVm = TransferProgressViewModel(
                snapshotsFlow = receiverCoordinator.progressTracker.transfers,
                cancelPort = { id, reason -> receiverCoordinator.cancelTransfer(id, reason) },
                scope = receiverScope,
            )
            val receiverServer = FsapHttpServer(
                config = FsapHttpServerConfig(identity = receiverIdentity, host = "127.0.0.1", port = 0),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = receiverSessions,
                    fileSink = receiverSink,
                    approvalHandler = receiverApproval,
                ),
            )

            try {
                val receiverPort = receiverServer.start()
                val mainVm = FileShareMainViewModel(runtime = runtime, scope = uiScope, logger = logger)
                mainVm.startNodeInternal()
                withTimeout(3_000) {
                    while (mainVm.uiState.value.nodeState != LocalNodeState.AVAILABLE) delay(10)
                }

                // Conecta no servidor receptor e seleciona-o na UI Android
                val connectRes = runtime.connectByManualIp("127.0.0.1", receiverPort)
                assertTrue(connectRes is ManualConnectResult.Connected)
                withTimeout(3_000) {
                    while (mainVm.uiState.value.peers.isEmpty()) delay(10)
                }

                // Renomeia o alias na aba Configuracoes e aplica reinicio
                mainVm.updateSettingsDraft(
                    alias = "Tablet-S6-Enzo",
                    portText = mainVm.uiState.value.boundHttpPort.toString(),
                    tlsEnabled = false,
                )
                mainVm.applySettingsAndRestartInternal()
                assertEquals("Tablet-S6-Enzo", controller.localAlias.value)

                // Envia arquivo do Android para Desktop apos renomear
                mainVm.addSources(listOf(InMemoryFileSource("foto.jpg", ByteArray(4096) { 7 })))
                mainVm.sendSelectedFilesInternal()

                withTimeout(5_000) {
                    while (receiverApprovalVm.uiState.value.activePrompt == null) delay(10)
                }
                val prompt = assertNotNull(receiverApprovalVm.uiState.value.activePrompt)
                assertEquals("Tablet-S6-Enzo", prompt.senderAlias)

                receiverApprovalVm.acceptActive()
                withTimeout(5_000) {
                    while (receiverProgressVm.uiState.value.items.none { it.state == TransferState.COMPLETED }) {
                        delay(10)
                    }
                }
                val transferItem = receiverProgressVm.uiState.value.items.first()
                assertEquals("Tablet-S6-Enzo", transferItem.peerAlias)
            } finally {
                receiverCoordinator.close()
                receiverClient.close()
                receiverServer.stop()
                receiverSessions.close()
                receiverScope.cancel()
                runtime.close()
                uiScope.cancel()
                controller.close()
                tempRoot.deleteRecursively()
            }
        }

    @Test
    fun formatSaveDirectoryLabelAndPickSaveDirectorySwitchDestinationPublisher() =
        runBlocking {
            assertEquals(
                "Downloads/Kshare (MediaStore)",
                AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL,
            )
            assertEquals(
                AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL,
                AndroidNodeIdentityHelper.formatSaveDirectoryLabel(null),
            )
            assertEquals(
                "Armazenamento Interno/Download/MinhaPasta",
                AndroidNodeIdentityHelper.formatSaveDirectoryLabel(
                    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMinhaPasta",
                ),
            )
            assertEquals(
                "1A2B-3C4D/Arquivos",
                AndroidNodeIdentityHelper.formatSaveDirectoryLabel(
                    "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AArquivos",
                ),
            )

            val tempRoot = Files.createTempDirectory("fsap-android-dir-switch-").toFile()
            val stagingDir = File(tempRoot, "staging")
            val defaultDir = File(tempRoot, "downloads-default")
            val customDir = File(tempRoot, "downloads-custom")
            val logger = InMemoryFsapLogger()
            val controllerFlow = MutableStateFlow<FsapForegroundNodeController?>(null)
            val approvalCoordinator = InteractiveTransferApprovalCoordinator()
            lateinit var sink: AndroidFileSink

            val controller = FsapForegroundNodeController(
                localDeviceId = PlatformUuidGenerator.nextUuidV4(),
                initialAlias = "Galaxy-Destino",
                deviceType = DeviceType.TABLET,
                initialPort = 0,
                fileSink = AndroidFileSink(
                    stagingDirectory = stagingDir,
                    publisher = DirectoryDestinationPublisher(defaultDir),
                    logger = logger,
                ).also { sink = it },
                multicastTransportFactory = {
                    AndroidMulticastTransport(
                        lockManager = AndroidMulticastLockManager(lockFactory = { FakeMulticastLock() }, logger = logger),
                        delegate = InMemoryMulticastTransport(),
                        logger = logger,
                    )
                },
                localAddressesProvider = { listOf("127.0.0.1") },
                wakeLockManager = AndroidPartialWakeLockManager(factory = { FakeWakeLock() }, logger = logger),
                serviceHost = RecordingHost(),
                approvalHandler = approvalCoordinator,
                logger = logger,
            )
            controllerFlow.value = controller

            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = AndroidFileShareRuntime(
                localDeviceId = controller.localDeviceId,
                initialAlias = "Galaxy-Destino",
                initialPort = 0,
                controllerFlow = controllerFlow,
                approvalCoordinator = approvalCoordinator,
                startServiceAndAwaitController = { _, _ -> controller },
                filePicker = { emptyList() },
                directoryPicker = {
                    sink.updatePublisher(DirectoryDestinationPublisher(customDir))
                    AndroidNodeIdentityHelper.formatSaveDirectoryLabel(
                        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FFileshareCustom",
                    )
                },
                directoryReset = {
                    sink.updatePublisher(DirectoryDestinationPublisher(defaultDir))
                    AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL
                },
                initialAddressesProvider = { listOf("127.0.0.1") },
                ensureNotificationPermission = { true },
                logger = logger,
                parentScope = uiScope,
                ownsScope = false,
            )

            val mainVm = FileShareMainViewModel(runtime = runtime, scope = uiScope, logger = logger)
            try {
                assertEquals(AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL, mainVm.uiState.value.saveDirectoryLabel)
                assertFalse(mainVm.uiState.value.hasCustomSaveDirectory)

                mainVm.pickSaveDirectoryInternal()
                val expectedLabel = "Armazenamento Interno/Documents/FileshareCustom"
                assertEquals(expectedLabel, mainVm.uiState.value.saveDirectoryLabel)
                assertEquals(expectedLabel, controller.saveDirectoryLabel.value)
                assertTrue(mainVm.uiState.value.hasCustomSaveDirectory)

                val data = "novo destino saf".encodeToByteArray()
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data)
                    .joinToString("") { "%02x".format(it) }
                val session = sink.openPartSession("tx-custom", "f1", "destino.txt", data.size.toLong())
                session.writeChunk(data, 0, data.size)
                session.commitPart(verifiedSha256 = sha, totalBytesWritten = data.size.toLong())

                assertTrue(File(customDir, "destino.txt").exists())
                assertFalse(File(defaultDir, "destino.txt").exists())

                mainVm.resetSaveDirectoryInternal()
                assertEquals(AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL, mainVm.uiState.value.saveDirectoryLabel)
                assertEquals(AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL, controller.saveDirectoryLabel.value)
                assertFalse(mainVm.uiState.value.hasCustomSaveDirectory)

                val sessionReset = sink.openPartSession("tx-reset", "f1", "padrao.txt", data.size.toLong())
                sessionReset.writeChunk(data, 0, data.size)
                sessionReset.commitPart(verifiedSha256 = sha, totalBytesWritten = data.size.toLong())

                assertTrue(File(defaultDir, "padrao.txt").exists())
            } finally {
                runtime.close()
                uiScope.cancel()
                controller.close()
                tempRoot.deleteRecursively()
            }
        }
}

