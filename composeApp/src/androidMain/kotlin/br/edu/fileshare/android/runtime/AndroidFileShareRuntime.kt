package br.edu.fileshare.android.runtime

import android.content.Context
import br.edu.fileshare.android.di.AndroidAppContainer
import br.edu.fileshare.android.network.AndroidNetworkInterfaceProvider
import br.edu.fileshare.android.service.AndroidNodeIdentityHelper
import br.edu.fileshare.android.service.AndroidNotificationPermissionRequester
import br.edu.fileshare.android.service.FsapForegroundNodeController
import br.edu.fileshare.android.service.FsapForegroundService
import br.edu.fileshare.android.service.FsapServiceRegistry
import br.edu.fileshare.android.storage.AndroidFileSink
import br.edu.fileshare.android.storage.AndroidSafFilePicker
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.transfer.TransferProgressSnapshot
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.security.tls.FsapTlsCapabilities
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.viewmodel.FileShareRuntimePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Implementacao Android de [FileShareRuntimePort] conectada ao ciclo de vida reativo
 * do [FsapForegroundService], seletor SAF e permissoes de notificacao (`TASK-AND-004`, `TASK-AND-005`).
 */
class AndroidFileShareRuntime(
    override val localDeviceId: String,
    override val initialAlias: String,
    override val initialPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    override val saveDirectoryLabel: String = DEFAULT_SAVE_DIRECTORY_LABEL,
    private val controllerFlow: StateFlow<FsapForegroundNodeController?> = FsapServiceRegistry.activeController,
    val approvalCoordinator: InteractiveTransferApprovalCoordinator = FsapServiceRegistry.sharedApprovalCoordinator,
    private val startServiceAndAwaitController: suspend (alias: String, port: Int) -> FsapForegroundNodeController,
    private val filePicker: suspend () -> List<FileSource>,
    private val directoryPicker: suspend () -> String? = { null },
    private val initialAddressesProvider: () -> List<String> = { emptyList() },
    private val ensureNotificationPermission: suspend () -> Boolean = { true },
    val logger: FsapLogger,
    val clock: Clock = SystemClock,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownsScope: Boolean = true,
    private val onAliasApplied: (String) -> Unit = {},
) : FileShareRuntimePort {

    constructor(
        context: Context,
        filePicker: AndroidSafFilePicker,
        permissionRequester: AndroidNotificationPermissionRequester,
        container: AndroidAppContainer = AndroidAppContainer(),
        saveDirectoryLabel: String = FsapServiceRegistry.activeController.value?.saveDirectoryLabel?.value
            ?: AndroidNodeIdentityHelper.formatSaveDirectoryLabel(
                AndroidNodeIdentityHelper.getSavedTreeUri(context.applicationContext),
            ),
    ) : this(
        localDeviceId = AndroidNodeIdentityHelper.getOrCreateDeviceId(context.applicationContext),
        initialAlias = FsapServiceRegistry.activeController.value?.localAlias?.value
            ?: AndroidNodeIdentityHelper.getSavedAliasOrDefault(context.applicationContext),
        initialPort = FsapServiceRegistry.activeController.value?.boundPort?.value?.takeIf { it > 0 }
            ?: FsapConstants.DEFAULT_HTTP_PORT,
        saveDirectoryLabel = saveDirectoryLabel,
        controllerFlow = FsapServiceRegistry.activeController,
        approvalCoordinator = FsapServiceRegistry.sharedApprovalCoordinator,
        startServiceAndAwaitController = { alias, port ->
            val appContext = context.applicationContext
            FsapForegroundService.startForegroundNode(
                context = appContext,
                alias = alias,
                port = port,
                receptionEnabled = true,
                autoStart = false,
            )
            withTimeoutOrNull(SERVICE_ATTACH_TIMEOUT_MS) {
                FsapServiceRegistry.activeController.filterNotNull().first()
            } ?: throw IllegalStateException("Timeout ao iniciar e vincular FsapForegroundService.")
        },
        filePicker = { filePicker.pickMultipleFiles() },
        directoryPicker = {
            val pickedUri = filePicker.pickDestinationDirectory()
            if (pickedUri == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    val appContext = context.applicationContext
                    val activeController = FsapServiceRegistry.activeController.value
                    val label = if (activeController != null) {
                        activeController.updateDestinationDirectory(appContext, pickedUri)
                    } else {
                        AndroidFileSink.createDefaultPublisher(appContext, pickedUri)
                        AndroidNodeIdentityHelper.formatSaveDirectoryLabel(pickedUri.toString())
                    }
                    AndroidNodeIdentityHelper.saveTreeUri(appContext, pickedUri.toString())
                    label
                }
            }
        },
        initialAddressesProvider = { AndroidNetworkInterfaceProvider().listLocalIpv4Addresses() },
        ensureNotificationPermission = { permissionRequester.ensureNotificationPermission() },
        logger = container.logger,
        clock = container.clock,
        onAliasApplied = { alias ->
            AndroidNodeIdentityHelper.saveAlias(context.applicationContext, alias)
        },
    )

    private val runtimeScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(),
    )

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<StoredFileDescriptor>>(emptyList())
    override val receivedFiles: StateFlow<List<StoredFileDescriptor>> = _receivedFiles.asStateFlow()

    private val _localAddresses = MutableStateFlow(initialAddressesProvider())
    override val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    private val _externalNodeState = MutableStateFlow(
        controllerFlow.value?.nodeState?.value ?: LocalNodeState.STOPPED,
    )
    override val externalNodeState: StateFlow<LocalNodeState> = _externalNodeState.asStateFlow()

    private val _externalBoundPort = MutableStateFlow(
        controllerFlow.value?.boundPort?.value ?: initialPort,
    )
    override val externalBoundPort: StateFlow<Int> = _externalBoundPort.asStateFlow()

    private val _externalLocalAlias = MutableStateFlow(
        controllerFlow.value?.localAlias?.value ?: initialAlias,
    )
    override val externalLocalAlias: StateFlow<String> = _externalLocalAlias.asStateFlow()

    private val _externalSaveDirectoryLabel = MutableStateFlow(
        controllerFlow.value?.saveDirectoryLabel?.value ?: saveDirectoryLabel,
    )
    override val externalSaveDirectoryLabel: StateFlow<String> = _externalSaveDirectoryLabel.asStateFlow()

    private val _tlsEnabled = MutableStateFlow(
        controllerFlow.value?.tlsEnabled?.value ?: false,
    )
    override val tlsEnabled: StateFlow<Boolean> = _tlsEnabled.asStateFlow()

    private val _localTlsFingerprint = MutableStateFlow<String?>(
        controllerFlow.value?.localTlsFingerprint?.value,
    )
    override val localTlsFingerprint: StateFlow<String?> = _localTlsFingerprint.asStateFlow()

    private val _transferSnapshots = MutableStateFlow<List<TransferProgressSnapshot>>(emptyList())
    val transferSnapshots: StateFlow<List<TransferProgressSnapshot>> = _transferSnapshots.asStateFlow()

    init {
        observeControllerStream()
    }

    override suspend fun startNode(alias: String, port: Int): Result<Int> =
        startNode(alias = alias, port = port, tlsEnabled = false)

    override suspend fun startNode(alias: String, port: Int, tlsEnabled: Boolean): Result<Int> =
        startOrRestartNode(alias = alias, port = port, tlsEnabled = tlsEnabled, forceRestart = false)

    override suspend fun restartNode(alias: String, port: Int, tlsEnabled: Boolean): Result<Int> =
        startOrRestartNode(alias = alias, port = port, tlsEnabled = tlsEnabled, forceRestart = true)

    private suspend fun startOrRestartNode(
        alias: String,
        port: Int,
        tlsEnabled: Boolean,
        forceRestart: Boolean,
    ): Result<Int> {
        runCatching { ensureNotificationPermission() }.onFailure { err ->
            if (err is CancellationException) throw err
            logger.warn(
                component = COMPONENT,
                message = "Nao foi possivel concluir solicitacao de POST_NOTIFICATIONS: ${err.message}",
                throwable = err,
            )
        }

        onAliasApplied(alias)

        val controller = try {
            controllerFlow.value ?: startServiceAndAwaitController(alias, port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Throwable) {
            return Result.failure(err)
        }

        controller.setReceptionEnabled(true)
        return if (forceRestart) {
            controller.restartNode(alias = alias, port = port, tlsEnabled = tlsEnabled)
        } else {
            controller.startNode(alias = alias, port = port, tlsEnabled = tlsEnabled)
        }
    }

    override suspend fun stopNode() {
        controllerFlow.value?.stopNode(reason = "USER_STOPPED")
    }

    override suspend fun pickFilesToSend(): List<FileSource> = filePicker()

    override suspend fun pickSaveDirectory(): String? {
        val selectedLabel = directoryPicker()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        _externalSaveDirectoryLabel.value = selectedLabel
        controllerFlow.value?.updateSaveDirectoryLabel(selectedLabel)
        return selectedLabel
    }

    override suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> {
        val controller = controllerFlow.value
            ?: return Result.failure(IllegalStateException("Inicie o servico FileShare antes de enviar arquivos."))
        return controller.sendFiles(peer = peer, sources = sources)
    }

    override suspend fun refreshDiscovery(): Result<Unit> {
        val controller = controllerFlow.value
        if (controller == null) {
            _localAddresses.value = initialAddressesProvider()
            return Result.success(Unit)
        }
        return controller.refreshDiscovery()
    }

    suspend fun connectByManualIp(
        ipAddress: String,
        port: Int,
        useTls: Boolean = false,
        expectedTlsFingerprintSha256: String? = null,
    ): ManualConnectResult {
        val controller = controllerFlow.value
            ?: return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.CONNECTION_LOST,
                message = "Inicie o servico FileShare antes de conectar manualmente.",
            )
        return controller.connectByManualIp(
            ipAddress = ipAddress,
            port = port,
            useTls = useTls,
            expectedTlsFingerprintSha256 = expectedTlsFingerprintSha256,
        )
    }

    fun lookupPeerTlsFingerprint(deviceId: String): String? =
        controllerFlow.value?.lookupPeerTlsFingerprint(deviceId)
            ?: peers.value.firstOrNull { it.identity.deviceId == deviceId }
                ?.identity
                ?.capabilities
                ?.let(FsapTlsCapabilities::extractSha256Fingerprint)

    suspend fun cancelTransfer(transferId: String, reason: String = "USER_CANCELLED"): Boolean {
        val controller = controllerFlow.value ?: return false
        return controller.transferCoordinator.cancelTransfer(
            transferId = transferId,
            reason = reason,
        )
    }

    fun clearCompletedTransfers() {
        _transferSnapshots.update { current -> current.filter { !it.state.isTerminal } }
        controllerFlow.value?.transferCoordinator?.progressTracker?.clearCompleted()
    }

    suspend fun setReceptionEnabled(enabled: Boolean) {
        controllerFlow.value?.setReceptionEnabled(enabled)
    }

    /**
     * Desconecta observadores de UI sem interromper transferencias em andamento no [FsapForegroundService].
     */
    fun close() {
        if (ownsScope) {
            runtimeScope.cancel("Interface Android desanexada.")
        } else {
            runtimeScope.cancel()
        }
    }

    private fun observeControllerStream() {
        runtimeScope.launch {
            controllerFlow.collectLatest { controller ->
                if (controller == null) {
                    _peers.value = emptyList()
                    _localAddresses.value = initialAddressesProvider()
                    _externalNodeState.value = LocalNodeState.STOPPED
                    _localTlsFingerprint.value = null
                    return@collectLatest
                }

                coroutineScope {
                    launch {
                        controller.peers.collect { discovered ->
                            _peers.value = discovered
                        }
                    }
                    launch {
                        controller.localAddresses.collect { addresses ->
                            _localAddresses.value = addresses
                        }
                    }
                    launch {
                        controller.receivedFiles.collect { filesFromController ->
                            if (filesFromController.isNotEmpty()) {
                                _receivedFiles.update { prior ->
                                    mergeReceivedFiles(filesFromController, prior)
                                }
                            }
                        }
                    }
                    launch {
                        controller.transferCoordinator.transfers.collect { snapshots ->
                            _transferSnapshots.update { prior ->
                                mergeTransferSnapshots(snapshots, prior)
                            }
                        }
                    }
                    launch {
                        controller.nodeState.collect { state ->
                            _externalNodeState.value = state
                        }
                    }
                    launch {
                        controller.boundPort.collect { port ->
                            if (port > 0) {
                                _externalBoundPort.value = port
                            }
                        }
                    }
                    launch {
                        controller.localAlias.collect { alias ->
                            if (alias.isNotBlank()) {
                                _externalLocalAlias.value = alias
                            }
                        }
                    }
                    launch {
                        controller.saveDirectoryLabel.collect { label ->
                            if (label.isNotBlank()) {
                                _externalSaveDirectoryLabel.value = label
                            }
                        }
                    }
                    launch {
                        controller.tlsEnabled.collect { enabled ->
                            _tlsEnabled.value = enabled
                        }
                    }
                    launch {
                        controller.localTlsFingerprint.collect { fingerprint ->
                            _localTlsFingerprint.value = fingerprint
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SAVE_DIRECTORY_LABEL: String = AndroidNodeIdentityHelper.DEFAULT_SAVE_DIRECTORY_LABEL
        private const val SERVICE_ATTACH_TIMEOUT_MS: Long = 5_000L
        private const val COMPONENT = "AndroidFileShareRuntime"

        internal fun mergeReceivedFiles(
            latest: List<StoredFileDescriptor>,
            previous: List<StoredFileDescriptor>,
        ): List<StoredFileDescriptor> =
            (latest + previous).distinctBy { it.locationUri }

        internal fun mergeTransferSnapshots(
            latest: List<TransferProgressSnapshot>,
            previous: List<TransferProgressSnapshot>,
        ): List<TransferProgressSnapshot> {
            val latestById = latest.associateBy { it.transferId }
            val preserved = previous.filter { it.transferId !in latestById }
            return latest + preserved
        }
    }
}

