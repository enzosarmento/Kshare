package br.edu.fileshare.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.edu.fileshare.android.di.AndroidAppContainer
import br.edu.fileshare.android.network.AndroidMulticastTransport
import br.edu.fileshare.android.network.AndroidNetworkInterfaceProvider
import br.edu.fileshare.android.storage.AndroidFileSink
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.DiscoveryCoordinator
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.ManualPeerConnector
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.network.transfer.TransferDirection
import br.edu.fileshare.network.transfer.TransferProgressSnapshot
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeEvent
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.LocalNodeStateMachine
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.format.ByteFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Abstracao testavel de `PowerManager.WakeLock` parcial (`TASK-AND-003`).
 */
interface WakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

fun interface WakeLockFactory {
    fun createWakeLock(tag: String): WakeLockHandle?
}

/**
 * Gerenciador seguro do ciclo de vida de `PARTIAL_WAKE_LOCK` com `setReferenceCounted(false)`.
 */
class AndroidPartialWakeLockManager(
    private val factory: WakeLockFactory,
    private val lockTag: String = DEFAULT_WAKE_LOCK_TAG,
    private val logger: FsapLogger? = null,
) {
    constructor(
        context: Context,
        lockTag: String = DEFAULT_WAKE_LOCK_TAG,
        logger: FsapLogger? = null,
    ) : this(
        factory = WakeLockFactory { tag ->
            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@WakeLockFactory null
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
            }
            object : WakeLockHandle {
                override val isHeld: Boolean get() = wakeLock.isHeld
                override fun acquire() {
                    if (!wakeLock.isHeld) {
                        // Limite de seguranca de 1 hora caso ocorra morte abrupta fora de onDestroy
                        wakeLock.acquire(60L * 60L * 1000L)
                    }
                }

                override fun release() {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }
        },
        lockTag = lockTag,
        logger = logger,
    )

    private val guard = Any()

    @Volatile
    private var activeHandle: WakeLockHandle? = null

    val isHeld: Boolean
        get() = synchronized(guard) { activeHandle?.isHeld == true }

    fun acquire(): Boolean = synchronized(guard) {
        val current = activeHandle
        if (current != null && current.isHeld) return@synchronized true

        val created = runCatching { factory.createWakeLock(lockTag) }.getOrNull()
            ?: return@synchronized false

        return@synchronized try {
            created.acquire()
            val held = created.isHeld
            activeHandle = if (held) created else null
            held
        } catch (err: Throwable) {
            runCatching { if (created.isHeld) created.release() }
            activeHandle = null
            logger?.warn(
                component = COMPONENT,
                message = "Erro ao adquirir WakeLock ($lockTag): ${err.message}",
                throwable = err,
            )
            false
        }
    }

    fun release() = synchronized(guard) {
        val handle = activeHandle ?: return@synchronized
        try {
            handle.release()
        } catch (err: Throwable) {
            logger?.warn(
                component = COMPONENT,
                message = "Erro ao liberar WakeLock ($lockTag): ${err.message}",
                throwable = err,
            )
        } finally {
            activeHandle = null
        }
    }

    companion object {
        const val DEFAULT_WAKE_LOCK_TAG: String = "FileShare:FsapDataSyncWakeLock"
        private const val COMPONENT = "AndroidPartialWakeLock"
    }
}

/**
 * Contrato entre o controlador puro de ciclo de vida FSAP e o `Service` Android (`TASK-AND-003`).
 */
interface FsapServiceHost {
    fun enterForeground(state: FsapNotificationState)
    fun updateNotification(state: FsapNotificationState)
    fun exitForegroundAndStopSelf()
}

/**
 * Registro reativo do controlador ativo do `FsapForegroundService` e coordenador compartilhado
 * de aprovacao explicita para consumo pela UI Android (`TASK-AND-003`, `TASK-AND-004`).
 */
object FsapServiceRegistry {
    private val _activeController = MutableStateFlow<FsapForegroundNodeController?>(null)
    val activeController: StateFlow<FsapForegroundNodeController?> = _activeController.asStateFlow()

    val sharedApprovalCoordinator = InteractiveTransferApprovalCoordinator()

    internal fun attach(controller: FsapForegroundNodeController) {
        _activeController.value = controller
    }

    internal fun detach(controller: FsapForegroundNodeController) {
        if (_activeController.value === controller) {
            _activeController.value = null
        }
    }
}

/**
 * Persistencia simples do `deviceId` UUID v4 e deteccao de alias/categoria Android (`docs/protocol-fsap.md`).
 */
object AndroidNodeIdentityHelper {
    private const val PREFS_NAME = "fileshare_fsap_prefs"
    private const val KEY_DEVICE_ID = "local_device_id"

    fun getOrCreateDeviceId(
        context: Context,
        uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    ): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)?.trim()
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val created = uuidGenerator.nextUuidV4()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun detectDeviceType(context: Context): DeviceType {
        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
        return if (smallestWidthDp >= 600) DeviceType.TABLET else DeviceType.MOBILE
    }

    fun defaultAlias(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val raw = if (model.isNotBlank()) model else "FileShare-Android"
        return raw.replace("\n", " ").replace("\r", " ").trim()
            .ifBlank { "FileShare-Android" }
            .take(FsapConstants.MAX_ALIAS_LENGTH)
    }
}

/**
 * Orquestrador completo do no FSAP em segundo plano no Android (`TASK-AND-003`):
 * controla [LocalNodeStateMachine], `FsapHttpServer`, `DiscoveryCoordinator` (`ANNOUNCE`/`DISCOVER`/`BYE`),
 * `WakeLock`, `MulticastLock`, atualizacoes de notificacao e encerramento automatico ao ficar ocioso.
 */
class FsapForegroundNodeController(
    val localDeviceId: String,
    initialAlias: String,
    private val deviceType: DeviceType = DeviceType.MOBILE,
    initialPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val fileSink: FileSink,
    private val multicastTransportFactory: () -> MulticastTransport,
    private val localAddressesProvider: () -> List<String>,
    private val wakeLockManager: AndroidPartialWakeLockManager,
    private val serviceHost: FsapServiceHost,
    val approvalHandler: TransferApprovalHandler = InteractiveTransferApprovalCoordinator(),
    val logger: FsapLogger,
    val clock: Clock = SystemClock,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownsScope: Boolean = true,
) {
    private val controllerScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val lifecycleMutex = Mutex()

    private val nodeFsm = LocalNodeStateMachine(
        nodeId = localDeviceId,
        logger = logger,
    )
    val nodeState: StateFlow<LocalNodeState> = nodeFsm.state

    val peerRegistry = PeerRegistry(clock = clock, logger = logger)
    val peers: StateFlow<List<DiscoveredPeer>> = peerRegistry.peers

    private val _boundPort = MutableStateFlow(initialPort)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _localAlias = MutableStateFlow(sanitizeAlias(initialAlias))
    val localAlias: StateFlow<String> = _localAlias.asStateFlow()

    private val _localAddresses = MutableStateFlow(localAddressesProvider())
    val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<StoredFileDescriptor>>(emptyList())
    val receivedFiles: StateFlow<List<StoredFileDescriptor>> = _receivedFiles.asStateFlow()

    private val _receptionEnabled = MutableStateFlow(true)
    val receptionEnabled: StateFlow<Boolean> = _receptionEnabled.asStateFlow()

    private val _activeTransfersCount = MutableStateFlow(0)
    val activeTransfersCount: StateFlow<Int> = _activeTransfersCount.asStateFlow()

    private val tokenManager = TransferTokenManager(
        clock = clock,
        logger = logger,
    )
    val sessionManager = ServerTransferSessionManager(
        tokenManager = tokenManager,
        fileSink = fileSink,
        scope = controllerScope,
        logger = logger,
        clock = clock,
    )

    private var activeIdentity: DeviceIdentity = buildIdentity(_localAlias.value, initialPort)
    val httpClient = FsapHttpClient(
        localIdentity = activeIdentity,
        logger = logger,
        clock = clock,
        uuidGenerator = uuidGenerator,
    )
    val transferCoordinator = TransferCoordinator(
        httpClient = httpClient,
        parentScope = controllerScope,
        serverSessionManager = sessionManager,
        logger = logger,
        clock = clock,
        uuidGenerator = uuidGenerator,
    )
    var manualConnector = createManualConnector(activeIdentity)
        private set

    private var httpServer: FsapHttpServer? = null
    private var discoveryCoordinator: DiscoveryCoordinator? = null
    private var lastNotificationState: FsapNotificationState? = null

    init {
        observeTransfersForNotificationAndIdleStop()
    }

    internal fun recordCommittedFile(descriptor: StoredFileDescriptor) {
        _receivedFiles.update { current -> listOf(descriptor) + current }
    }

    suspend fun startNode(
        alias: String = _localAlias.value,
        port: Int = _boundPort.value,
    ): Result<Int> = lifecycleMutex.withLock {
        val cleanAlias = sanitizeAlias(alias)
        val currentState = nodeFsm.currentState()

        if ((currentState == LocalNodeState.AVAILABLE || currentState == LocalNodeState.DISCOVERING) &&
            cleanAlias == _localAlias.value &&
            (port == _boundPort.value || port == 0)
        ) {
            refreshNotificationSnapshot()
            return@withLock Result.success(_boundPort.value)
        }

        if (currentState != LocalNodeState.STOPPED && currentState != LocalNodeState.FAULTED) {
            stopInternal(reason = "NODE_RESTART", notifyHostStop = false)
        }

        val startEvent = if (nodeFsm.currentState() == LocalNodeState.FAULTED) {
            LocalNodeEvent.CMD_RETRY_START
        } else {
            LocalNodeEvent.CMD_START_NODE
        }
        nodeFsm.transition(startEvent)

        _localAlias.value = cleanAlias
        activeIdentity = buildIdentity(cleanAlias, port)

        val startingState = FsapNotificationState(
            title = "Iniciando FileShare ($cleanAlias)",
            text = "Abrindo servidor FSAP na rede local...",
            ongoing = true,
        )
        lastNotificationState = startingState
        serviceHost.enterForeground(startingState)

        var startedServer: FsapHttpServer? = null
        return@withLock try {
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = activeIdentity,
                    host = "0.0.0.0",
                    port = port,
                ),
                logger = logger,
                clock = clock,
                uuidGenerator = uuidGenerator,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = fileSink,
                    approvalHandler = approvalHandler,
                ),
            )
            startedServer = server
            val actualPort = server.start()

            _boundPort.value = actualPort
            activeIdentity = activeIdentity.copy(httpPort = actualPort)
            manualConnector.close()
            manualConnector = createManualConnector(activeIdentity)
            httpServer = server
            _localAddresses.value = localAddressesProvider()

            wakeLockManager.acquire()
            nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)

            val coordinator = DiscoveryCoordinator(
                transport = multicastTransportFactory(),
                peerRegistry = peerRegistry,
                localIdentityProvider = { activeIdentity },
                scope = controllerScope,
                uuidGenerator = uuidGenerator,
                clock = clock,
                logger = logger,
            )
            discoveryCoordinator = coordinator
            coordinator.startBestEffort()

            nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
            refreshNotificationSnapshot()
            Result.success(actualPort)
        } catch (err: Throwable) {
            runCatching { discoveryCoordinator?.stop("START_FAILED") }
            discoveryCoordinator = null
            runCatching {
                startedServer?.stop(
                    gracePeriodMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                    timeoutMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                )
            }
            httpServer = null
            wakeLockManager.release()
            if (nodeFsm.canTransition(LocalNodeEvent.EVT_BIND_FAILED)) {
                nodeFsm.transition(LocalNodeEvent.EVT_BIND_FAILED)
            } else if (nodeFsm.canTransition(LocalNodeEvent.EVT_TRANSPORT_FATAL_ERROR)) {
                nodeFsm.transition(LocalNodeEvent.EVT_TRANSPORT_FATAL_ERROR)
            }
            val failedState = FsapNotificationState(
                title = "FileShare indisponivel",
                text = "Falha ao iniciar servidor FSAP: ${err.message ?: "erro desconhecido"}",
                ongoing = false,
            )
            lastNotificationState = failedState
            serviceHost.updateNotification(failedState)
            Result.failure(err)
        }
    }

    suspend fun setReceptionEnabled(enabled: Boolean) {
        _receptionEnabled.value = enabled
        if (!enabled) {
            val shouldStopNow = lifecycleMutex.withLock {
                _activeTransfersCount.value == 0 &&
                    nodeFsm.currentState() in listOf(
                        LocalNodeState.DISCOVERING,
                        LocalNodeState.AVAILABLE,
                        LocalNodeState.FAULTED,
                    )
            }
            if (shouldStopNow) {
                stopNode(reason = "RECEPTION_DISABLED")
                return
            }
        }
        refreshNotificationSnapshot()
    }

    suspend fun refreshDiscovery(): Result<Unit> {
        _localAddresses.value = localAddressesProvider()
        return discoveryCoordinator?.refresh() ?: Result.success(Unit)
    }

    suspend fun connectByManualIp(ipAddress: String, port: Int): ManualConnectResult =
        manualConnector.connectByManualIp(ipAddress, port)

    suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> {
        wakeLockManager.acquire()
        val transferId = uuidGenerator.nextUuidV4()
        transferCoordinator.launchSendFiles(
            peer = peer,
            sources = sources,
            transferId = transferId,
        )
        return Result.success(transferId)
    }

    suspend fun cancelActiveTransfers(reason: String = "USER_CANCELLED"): Int {
        val activeTransfers = transferCoordinator.transfers.value.filter { !it.state.isTerminal }
        var cancelledCount = 0
        for (snapshot in activeTransfers) {
            val cancelled = runCatching {
                transferCoordinator.cancelTransfer(
                    transferId = snapshot.transferId,
                    reason = reason,
                )
            }.getOrDefault(false)
            if (cancelled) cancelledCount++
        }
        return cancelledCount
    }

    suspend fun stopNode(reason: String = "USER_STOPPED") {
        lifecycleMutex.withLock {
            stopInternal(reason = reason, notifyHostStop = true)
        }
    }

    fun close() {
        runBlocking {
            withTimeoutOrNull(FsapConstants.T_SHUTDOWN_GRACE_MS) {
                stopNode(reason = "CONTROLLER_CLOSED")
                runCatching { transferCoordinator.close() }
                runCatching { sessionManager.close() }
            }
        }
        manualConnector.close()
        httpClient.close()
        wakeLockManager.release()
        if (ownsScope) {
            controllerScope.cancel("Controlador FSAP Android encerrado.")
        }
    }

    private suspend fun stopInternal(reason: String, notifyHostStop: Boolean) {
        val current = nodeFsm.currentState()
        if (current == LocalNodeState.STOPPED) {
            wakeLockManager.release()
            if (notifyHostStop) serviceHost.exitForegroundAndStopSelf()
            return
        }

        if (nodeFsm.canTransition(LocalNodeEvent.CMD_STOP_NODE)) {
            nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
        }

        runCatching {
            discoveryCoordinator?.stop(reason = reason)
        }
        discoveryCoordinator = null

        runCatching {
            withTimeoutOrNull(FsapConstants.T_SHUTDOWN_GRACE_MS) {
                val activeTransfers = transferCoordinator.transfers.value.filter { !it.state.isTerminal }
                for (snapshot in activeTransfers) {
                    runCatching {
                        transferCoordinator.cancelTransfer(
                            transferId = snapshot.transferId,
                            reason = reason,
                        )
                    }
                }
                sessionManager.close()
            }
        }

        runCatching {
            httpServer?.stop(
                gracePeriodMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                timeoutMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
            )
        }
        httpServer = null
        wakeLockManager.release()

        if (nodeFsm.canTransition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)) {
            nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
        }

        if (notifyHostStop) {
            lastNotificationState = null
            serviceHost.exitForegroundAndStopSelf()
        }
    }

    private fun observeTransfersForNotificationAndIdleStop() {
        controllerScope.launch {
            transferCoordinator.transfers.collect { snapshots ->
                val active = snapshots.filter { !it.state.isTerminal }
                _activeTransfersCount.value = active.size

                if (active.isNotEmpty()) {
                    wakeLockManager.acquire()
                }

                if (active.isEmpty() &&
                    !_receptionEnabled.value &&
                    nodeFsm.currentState() in listOf(LocalNodeState.DISCOVERING, LocalNodeState.AVAILABLE)
                ) {
                    stopNode(reason = "ALL_TRANSFERS_FINISHED_RECEPTION_OFF")
                } else if (nodeFsm.currentState() in listOf(LocalNodeState.DISCOVERING, LocalNodeState.AVAILABLE)) {
                    refreshNotificationSnapshot(active)
                }
            }
        }
    }

    private fun refreshNotificationSnapshot(
        activeTransfers: List<TransferProgressSnapshot> =
            transferCoordinator.transfers.value.filter { !it.state.isTerminal },
    ) {
        val state = if (activeTransfers.isNotEmpty()) {
            val primary = activeTransfers.first()
            val actionLabel = if (primary.direction == TransferDirection.SENDING) "Enviando" else "Recebendo"
            val itemLabel = primary.currentFileName ?: primary.peerAlias
            val suffix = if (activeTransfers.size > 1) " (+${activeTransfers.size - 1})" else ""
            val bytesSummary = "${ByteFormatter.formatBytes(primary.bytesTransferred)} / ${ByteFormatter.formatBytes(primary.totalBytes)}"
            FsapNotificationState(
                title = "$actionLabel: $itemLabel$suffix",
                text = "${primary.percentComplete}% ($bytesSummary)",
                progressPercent = primary.percentComplete,
                ongoing = true,
                hasActiveTransfers = true,
            )
        } else {
            FsapNotificationState(
                title = "FileShare ativo (${_localAlias.value})",
                text = "Visivel na rede local — porta ${_boundPort.value}",
                progressPercent = null,
                ongoing = true,
                hasActiveTransfers = false,
            )
        }
        if (state == lastNotificationState) return
        lastNotificationState = state
        serviceHost.updateNotification(state)
    }

    private fun buildIdentity(alias: String, port: Int): DeviceIdentity = DeviceIdentity(
        deviceId = localDeviceId,
        alias = sanitizeAlias(alias),
        deviceType = deviceType,
        platform = PlatformType.ANDROID,
        protocolVersion = FsapConstants.PROTOCOL_VERSION,
        httpPort = port,
    )

    private fun createManualConnector(identity: DeviceIdentity): ManualPeerConnector = ManualPeerConnector(
        localIdentity = identity,
        peerRegistry = peerRegistry,
        logger = logger,
        clock = clock,
        uuidGenerator = uuidGenerator,
    )

    private fun sanitizeAlias(raw: String): String =
        raw.replace("\n", " ").replace("\r", " ").trim()
            .ifBlank { "FileShare-Android" }
            .take(FsapConstants.MAX_ALIAS_LENGTH)
}

/**
 * `ForegroundService` (`dataSync`) que mantem o servidor HTTP/TCP FSAP, a descoberta UDP Multicast,
 * o `MulticastLock` e o `PartialWakeLock` ativos em segundo plano e com tela bloqueada (`TASK-AND-003`).
 */
class FsapForegroundService : Service(), FsapServiceHost {

    inner class LocalBinder : Binder() {
        val service: FsapForegroundService
            get() = this@FsapForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: FsapForegroundNodeController
    private var foregroundEntered: Boolean = false

    val nodeController: FsapForegroundNodeController
        get() = controller

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        val container = AndroidAppContainer()
        val logger = container.logger
        val interfaceProvider = AndroidNetworkInterfaceProvider()

        lateinit var createdController: FsapForegroundNodeController
        val sink = AndroidFileSink(
            context = appContext,
            onCommitted = { descriptor -> createdController.recordCommittedFile(descriptor) },
            logger = logger,
        )
        createdController = FsapForegroundNodeController(
            localDeviceId = AndroidNodeIdentityHelper.getOrCreateDeviceId(appContext),
            initialAlias = AndroidNodeIdentityHelper.defaultAlias(),
            deviceType = AndroidNodeIdentityHelper.detectDeviceType(appContext),
            initialPort = FsapConstants.DEFAULT_HTTP_PORT,
            fileSink = sink,
            multicastTransportFactory = {
                AndroidMulticastTransport(context = appContext, logger = logger)
            },
            localAddressesProvider = { interfaceProvider.listLocalIpv4Addresses() },
            wakeLockManager = AndroidPartialWakeLockManager(context = appContext, logger = logger),
            serviceHost = this,
            approvalHandler = FsapServiceRegistry.sharedApprovalCoordinator,
            logger = logger,
            clock = container.clock,
        )
        controller = createdController
        FsapServiceRegistry.attach(createdController)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureInitialForegroundNotification()

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                serviceScope.launch {
                    controller.stopNode(reason = "NOTIFICATION_STOP")
                }
                return START_NOT_STICKY
            }

            ACTION_CANCEL_TRANSFERS -> {
                serviceScope.launch {
                    controller.cancelActiveTransfers(reason = "NOTIFICATION_CANCELLED")
                }
                return START_NOT_STICKY
            }

            else -> {
                val autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, true) ?: true
                if (!autoStart) {
                    return START_NOT_STICKY
                }
                val alias = intent?.getStringExtra(EXTRA_ALIAS) ?: AndroidNodeIdentityHelper.defaultAlias()
                val port = intent?.getIntExtra(EXTRA_PORT, FsapConstants.DEFAULT_HTTP_PORT)
                    ?: FsapConstants.DEFAULT_HTTP_PORT
                val receptionEnabled = intent?.getBooleanExtra(EXTRA_RECEPTION_ENABLED, true) ?: true

                serviceScope.launch {
                    controller.setReceptionEnabled(receptionEnabled)
                    controller.startNode(alias = alias, port = port)
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        FsapServiceRegistry.detach(controller)
        controller.close()
        serviceScope.cancel("FsapForegroundService destruido.")
        foregroundEntered = false
        super.onDestroy()
    }

    override fun enterForeground(state: FsapNotificationState) {
        val notification = FsapServiceNotificationFactory.buildServiceNotification(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FsapServiceNotificationFactory.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(
                FsapServiceNotificationFactory.SERVICE_NOTIFICATION_ID,
                notification,
            )
        }
        foregroundEntered = true
    }

    override fun updateNotification(state: FsapNotificationState) {
        if (!foregroundEntered) {
            enterForeground(state)
        } else {
            FsapServiceNotificationFactory.updateNotification(this, state)
        }
    }

    override fun exitForegroundAndStopSelf() {
        foregroundEntered = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureInitialForegroundNotification() {
        if (!foregroundEntered) {
            enterForeground(
                FsapNotificationState(
                    title = "FileShare",
                    text = "Preparando servico FSAP na rede local...",
                    ongoing = true,
                ),
            )
        }
    }

    companion object {
        const val ACTION_START_SERVICE: String = "br.edu.fileshare.action.START_FSAP_SERVICE"
        const val ACTION_STOP_SERVICE: String = "br.edu.fileshare.action.STOP_FSAP_SERVICE"
        const val ACTION_CANCEL_TRANSFERS: String = "br.edu.fileshare.action.CANCEL_ACTIVE_TRANSFERS"
        const val EXTRA_ALIAS: String = "extra_alias"
        const val EXTRA_PORT: String = "extra_port"
        const val EXTRA_RECEPTION_ENABLED: String = "extra_reception_enabled"
        const val EXTRA_AUTO_START: String = "extra_auto_start"

        fun createStartIntent(
            context: Context,
            alias: String = AndroidNodeIdentityHelper.defaultAlias(),
            port: Int = FsapConstants.DEFAULT_HTTP_PORT,
            receptionEnabled: Boolean = true,
            autoStart: Boolean = true,
        ): Intent = Intent(context, FsapForegroundService::class.java).apply {
            action = ACTION_START_SERVICE
            putExtra(EXTRA_ALIAS, alias)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_RECEPTION_ENABLED, receptionEnabled)
            putExtra(EXTRA_AUTO_START, autoStart)
        }

        fun createStopIntent(context: Context): Intent =
            Intent(context, FsapForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }

        fun createCancelTransfersIntent(context: Context): Intent =
            Intent(context, FsapForegroundService::class.java).apply {
                action = ACTION_CANCEL_TRANSFERS
            }

        fun startForegroundNode(
            context: Context,
            alias: String = AndroidNodeIdentityHelper.defaultAlias(),
            port: Int = FsapConstants.DEFAULT_HTTP_PORT,
            receptionEnabled: Boolean = true,
            autoStart: Boolean = true,
        ) {
            val intent = createStartIntent(context, alias, port, receptionEnabled, autoStart)
            context.startForegroundService(intent)
        }

        fun stopForegroundNode(context: Context) {
            val stopIntent = createStopIntent(context)
            val sentToRunning = runCatching { context.startService(stopIntent) != null }.getOrDefault(false)
            if (!sentToRunning) {
                runCatching { context.stopService(Intent(context, FsapForegroundService::class.java)) }
            }
        }
    }
}

