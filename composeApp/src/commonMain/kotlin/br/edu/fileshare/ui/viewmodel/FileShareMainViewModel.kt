package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.statemachine.LocalNodeEvent
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.LocalNodeStateMachine
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MainTab(val title: String) {
    SEND("Enviar"),
    RECEIVED("Recebidos"),
    TRANSFERS("Transferencias"),
    SETTINGS("Configuracoes"),
}

/**
 * Porta multiplataforma de runtime consumida pela camada de UI sem acoplamento com APIs nativas (`TASK-DCLI-001`).
 */
interface FileShareRuntimePort {
    val localDeviceId: String
    val initialAlias: String
    val initialPort: Int
    val saveDirectoryLabel: String
    val peers: StateFlow<List<DiscoveredPeer>>
    val receivedFiles: StateFlow<List<StoredFileDescriptor>>
    val localAddresses: StateFlow<List<String>>
    val externalNodeState: StateFlow<LocalNodeState>?
        get() = null
    val externalBoundPort: StateFlow<Int>?
        get() = null

    val tlsEnabled: StateFlow<Boolean>?
        get() = null

    val localTlsFingerprint: StateFlow<String?>?
        get() = null

    suspend fun startNode(alias: String, port: Int): Result<Int>
    suspend fun startNode(alias: String, port: Int, tlsEnabled: Boolean): Result<Int> =
        startNode(alias = alias, port = port)

    suspend fun restartNode(alias: String, port: Int, tlsEnabled: Boolean): Result<Int> =
        startNode(alias = alias, port = port, tlsEnabled = tlsEnabled)

    suspend fun stopNode()
    suspend fun pickFilesToSend(): List<FileSource>
    suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String>
    suspend fun refreshDiscovery(): Result<Unit> = Result.success(Unit)
}

data class FileShareMainUiState(
    val localDeviceId: String = "",
    val localAlias: String = "",
    val boundHttpPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val saveDirectoryLabel: String = "",
    val localAddresses: List<String> = emptyList(),
    val nodeState: LocalNodeState = LocalNodeState.STOPPED,
    val activeTab: MainTab = MainTab.SEND,
    val selectedFiles: List<FileSource> = emptyList(),
    val peers: List<DiscoveredPeer> = emptyList(),
    val selectedPeerDeviceId: String? = null,
    val receivedFiles: List<StoredFileDescriptor> = emptyList(),
    val aliasDraft: String = "",
    val portDraft: String = FsapConstants.DEFAULT_HTTP_PORT.toString(),
    val tlsEnabled: Boolean = false,
    val tlsDraftEnabled: Boolean = false,
    val localTlsFingerprint: String? = null,
    val settingsError: String? = null,
    val statusBanner: String? = null,
    val isBusy: Boolean = false,
) {
    val totalSelectedBytes: Long
        get() = selectedFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    val selectedPeer: DiscoveredPeer?
        get() = peers.firstOrNull { it.identity.deviceId == selectedPeerDeviceId }

    val canSendFiles: Boolean
        get() = selectedFiles.isNotEmpty() && selectedPeer != null && nodeState != LocalNodeState.FAULTED && !isBusy
}

/**
 * ViewModel principal em Unidirectional Data Flow (UDF) compartilhada entre Desktop e Android (`TASK-DCLI-001`).
 */
class FileShareMainViewModel(
    private val runtime: FileShareRuntimePort,
    private val scope: CoroutineScope,
    logger: FsapLogger? = null,
) {
    private val nodeLifecycleMutex = Mutex()
    private val nodeFsm = LocalNodeStateMachine(
        nodeId = runtime.localDeviceId,
        logger = logger,
    )

    private val _uiState = MutableStateFlow(
        FileShareMainUiState(
            localDeviceId = runtime.localDeviceId,
            localAlias = runtime.initialAlias,
            boundHttpPort = runtime.initialPort,
            saveDirectoryLabel = runtime.saveDirectoryLabel,
            localAddresses = runtime.localAddresses.value,
            nodeState = nodeFsm.currentState(),
            aliasDraft = runtime.initialAlias,
            portDraft = runtime.initialPort.toString(),
            peers = runtime.peers.value,
            receivedFiles = runtime.receivedFiles.value,
        ),
    )
    val uiState: StateFlow<FileShareMainUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            runtime.peers.collectLatest { discovered ->
                _uiState.update { current ->
                    val preservedSelection = current.selectedPeerDeviceId
                        ?.takeIf { selected -> discovered.any { it.identity.deviceId == selected } }
                        ?: discovered.singleOrNull()?.identity?.deviceId
                    current.copy(
                        peers = discovered,
                        selectedPeerDeviceId = preservedSelection,
                    )
                }
            }
        }

        scope.launch {
            runtime.receivedFiles.collectLatest { files ->
                _uiState.update { it.copy(receivedFiles = files) }
            }
        }

        scope.launch {
            runtime.localAddresses.collectLatest { ips ->
                _uiState.update { it.copy(localAddresses = ips) }
            }
        }

        runtime.externalNodeState?.let { externalStateFlow ->
            scope.launch {
                var lastObserved = LocalNodeState.STOPPED
                externalStateFlow.collectLatest { externalState ->
                    val transitionDetected = externalState != lastObserved
                    lastObserved = externalState
                    if (transitionDetected || externalState == LocalNodeState.AVAILABLE) {
                        reconcileExternalNodeState(externalState)
                    }
                }
            }
        }

        runtime.externalBoundPort?.let { boundPortFlow ->
            scope.launch {
                boundPortFlow.collectLatest { port ->
                    if (port > 0) {
                        _uiState.update { current ->
                            if (current.boundHttpPort == port) {
                                current
                            } else {
                                val syncDraft = current.activeTab != MainTab.SETTINGS ||
                                    current.portDraft == current.boundHttpPort.toString()
                                current.copy(
                                    boundHttpPort = port,
                                    portDraft = if (syncDraft) port.toString() else current.portDraft,
                                )
                            }
                        }
                    }
                }
            }
        }

        runtime.tlsEnabled?.let { tlsEnabledFlow ->
            scope.launch {
                tlsEnabledFlow.collectLatest { enabled ->
                    _uiState.update { current ->
                        val syncDraft = current.activeTab != MainTab.SETTINGS ||
                            current.tlsDraftEnabled == current.tlsEnabled
                        current.copy(
                            tlsEnabled = enabled,
                            tlsDraftEnabled = if (syncDraft) enabled else current.tlsDraftEnabled,
                        )
                    }
                }
            }
        }

        runtime.localTlsFingerprint?.let { fingerprintFlow ->
            scope.launch {
                fingerprintFlow.collectLatest { fingerprint ->
                    _uiState.update { it.copy(localTlsFingerprint = fingerprint) }
                }
            }
        }
    }

    internal suspend fun reconcileExternalNodeState(externalState: LocalNodeState) {
        nodeLifecycleMutex.withLock {
            val effectiveExternal = runtime.externalNodeState?.value ?: externalState
            val current = nodeFsm.currentState()
            when {
                effectiveExternal == LocalNodeState.STOPPED &&
                    (current == LocalNodeState.AVAILABLE || current == LocalNodeState.DISCOVERING) -> {
                    nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
                    nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
                    _uiState.update {
                        it.copy(
                            nodeState = nodeFsm.currentState(),
                            statusBanner = "Servico FSAP encerrado em segundo plano.",
                        )
                    }
                }

                effectiveExternal == LocalNodeState.STOPPED && current == LocalNodeState.FAULTED -> {
                    nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
                    nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
                    _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
                }

                effectiveExternal == LocalNodeState.AVAILABLE && current == LocalNodeState.STOPPED -> {
                    nodeFsm.transition(LocalNodeEvent.CMD_START_NODE)
                    nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
                    nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
                    val syncedPort = runtime.externalBoundPort?.value?.takeIf { it > 0 }
                    _uiState.update {
                        it.copy(
                            nodeState = nodeFsm.currentState(),
                            boundHttpPort = syncedPort ?: it.boundHttpPort,
                            portDraft = (syncedPort ?: it.boundHttpPort).toString(),
                            statusBanner = null,
                        )
                    }
                }

                effectiveExternal == LocalNodeState.FAULTED && current == LocalNodeState.STOPPED -> {
                    nodeFsm.transition(LocalNodeEvent.CMD_START_NODE)
                    nodeFsm.transition(LocalNodeEvent.EVT_BIND_FAILED)
                    _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
                }
            }
        }
    }

    fun startNode() {
        scope.launch { startNodeInternal() }
    }

    internal suspend fun startNodeInternal() {
        nodeLifecycleMutex.withLock {
            val startedEvent = when (nodeFsm.currentState()) {
                LocalNodeState.STOPPED -> LocalNodeEvent.CMD_START_NODE
                LocalNodeState.FAULTED -> LocalNodeEvent.CMD_RETRY_START
                LocalNodeState.AVAILABLE,
                LocalNodeState.DISCOVERING,
                -> return@withLock
                LocalNodeState.INITIALIZING,
                LocalNodeState.SHUTTING_DOWN,
                -> return@withLock
            }
            executeNodeStartLocked(startedEvent) { alias, port, tlsEnabled ->
                runtime.startNode(alias = alias, port = port, tlsEnabled = tlsEnabled)
            }
        }
    }

    internal suspend fun restartNodeInternal() {
        nodeLifecycleMutex.withLock {
            val startedEvent = when (nodeFsm.currentState()) {
                LocalNodeState.STOPPED -> LocalNodeEvent.CMD_START_NODE
                LocalNodeState.FAULTED -> LocalNodeEvent.CMD_RETRY_START
                LocalNodeState.AVAILABLE,
                LocalNodeState.DISCOVERING,
                -> {
                    nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
                    nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
                    LocalNodeEvent.CMD_START_NODE
                }
                LocalNodeState.INITIALIZING,
                LocalNodeState.SHUTTING_DOWN,
                -> return@withLock
            }
            executeNodeStartLocked(startedEvent) { alias, port, tlsEnabled ->
                runtime.restartNode(alias = alias, port = port, tlsEnabled = tlsEnabled)
            }
        }
    }

    private suspend fun executeNodeStartLocked(
        startedEvent: LocalNodeEvent,
        invokeRuntimeStart: suspend (alias: String, port: Int, tlsEnabled: Boolean) -> Result<Int>,
    ) {
        val alias = _uiState.value.localAlias
        val port = _uiState.value.boundHttpPort
        val tlsEnabled = _uiState.value.tlsEnabled

        nodeFsm.transition(startedEvent)
        _uiState.update {
            it.copy(
                nodeState = nodeFsm.currentState(),
                statusBanner = null,
            )
        }

        val result = invokeRuntimeStart(alias, port, tlsEnabled)
        result.fold(
            onSuccess = { boundPort ->
                nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
                nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
                val fallbackBanner = if (port > 0 && boundPort != port) {
                    "Porta TCP $port em uso; alternado automaticamente para a porta livre $boundPort."
                } else {
                    null
                }
                _uiState.update {
                    it.copy(
                        nodeState = nodeFsm.currentState(),
                        boundHttpPort = boundPort,
                        portDraft = boundPort.toString(),
                        localTlsFingerprint = runtime.localTlsFingerprint?.value,
                        statusBanner = fallbackBanner,
                    )
                }
            },
            onFailure = { error ->
                nodeFsm.transition(LocalNodeEvent.EVT_BIND_FAILED)
                _uiState.update {
                    it.copy(
                        nodeState = nodeFsm.currentState(),
                        statusBanner = "Falha ao iniciar servico de rede: ${error.message ?: "erro desconhecido"}",
                    )
                }
            },
        )
    }

    fun stopNode() {
        scope.launch { stopNodeInternal() }
    }

    internal suspend fun stopNodeInternal() {
        nodeLifecycleMutex.withLock {
            val currentState = nodeFsm.currentState()
            if (currentState == LocalNodeState.STOPPED || currentState == LocalNodeState.SHUTTING_DOWN) {
                return@withLock
            }
            nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
            _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }

            runCatching { runtime.stopNode() }

            nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
            _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun pickFiles() {
        scope.launch { pickFilesInternal() }
    }

    internal suspend fun pickFilesInternal() {
        _uiState.update { it.copy(isBusy = true, statusBanner = null) }
        try {
            val picked = runtime.pickFilesToSend()
            if (picked.isNotEmpty()) {
                _uiState.update { state ->
                    val merged = (state.selectedFiles + picked).distinctBy { "${it.fileName}#${it.sizeBytes}" }
                    state.copy(selectedFiles = merged)
                }
            }
        } catch (ex: Throwable) {
            _uiState.update {
                it.copy(statusBanner = "Erro ao selecionar arquivos: ${ex.message ?: "falha de I/O"}")
            }
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun addSources(sources: List<FileSource>) {
        if (sources.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.selectedFiles + sources).distinctBy { "${it.fileName}#${it.sizeBytes}" }
            state.copy(selectedFiles = merged)
        }
    }

    fun removeSelectedFileAt(index: Int) {
        _uiState.update { state ->
            if (index !in state.selectedFiles.indices) return@update state
            val updated = state.selectedFiles.toMutableList().apply { removeAt(index) }
            state.copy(selectedFiles = updated)
        }
    }

    fun clearSelectedFiles() {
        _uiState.update { it.copy(selectedFiles = emptyList()) }
    }

    fun selectPeer(deviceId: String?) {
        _uiState.update { it.copy(selectedPeerDeviceId = deviceId) }
    }

    fun sendSelectedFiles() {
        scope.launch { sendSelectedFilesInternal() }
    }

    internal suspend fun sendSelectedFilesInternal() {
        val snapshot = _uiState.value
        val targetPeer = snapshot.selectedPeer
        if (targetPeer == null) {
            _uiState.update { it.copy(statusBanner = "Selecione ou conecte um dispositivo destinatario.") }
            return
        }
        if (snapshot.selectedFiles.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "Selecione ao menos um arquivo antes de enviar.") }
            return
        }

        _uiState.update { it.copy(isBusy = true, statusBanner = null) }
        try {
            val result = runtime.sendFiles(targetPeer, snapshot.selectedFiles)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            selectedFiles = emptyList(),
                            activeTab = MainTab.TRANSFERS,
                            statusBanner = "Envio iniciado para ${targetPeer.identity.alias}.",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(statusBanner = "Falha ao iniciar envio: ${error.message ?: "erro desconhecido"}")
                    }
                },
            )
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun refreshPeers() {
        scope.launch {
            if (nodeFsm.currentState() == LocalNodeState.AVAILABLE) {
                nodeFsm.transition(LocalNodeEvent.CMD_REFRESH_DISCOVERY)
                _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
            }
            runtime.refreshDiscovery()
            if (nodeFsm.currentState() == LocalNodeState.DISCOVERING) {
                nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
                _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
            }
        }
    }

    fun updateSettingsDraft(
        alias: String,
        portText: String,
        tlsEnabled: Boolean = _uiState.value.tlsDraftEnabled,
    ) {
        _uiState.update {
            it.copy(
                aliasDraft = alias,
                portDraft = portText,
                tlsDraftEnabled = tlsEnabled,
                settingsError = null,
            )
        }
    }

    fun applySettingsAndRestart() {
        scope.launch { applySettingsAndRestartInternal() }
    }

    internal suspend fun applySettingsAndRestartInternal() {
        val cleanAlias = _uiState.value.aliasDraft.trim()
        if (cleanAlias.isEmpty() || cleanAlias.length > FsapConstants.MAX_ALIAS_LENGTH) {
            _uiState.update {
                it.copy(settingsError = "Alias deve ter entre 1 e ${FsapConstants.MAX_ALIAS_LENGTH} caracteres.")
            }
            return
        }

        val parsedPort = _uiState.value.portDraft.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in FsapConstants.MIN_VALID_PORT..FsapConstants.MAX_VALID_PORT) {
            _uiState.update {
                it.copy(settingsError = "Porta HTTP deve estar entre ${FsapConstants.MIN_VALID_PORT} e ${FsapConstants.MAX_VALID_PORT}.")
            }
            return
        }

        val nextTlsEnabled = _uiState.value.tlsDraftEnabled
        _uiState.update {
            it.copy(
                localAlias = cleanAlias,
                boundHttpPort = parsedPort,
                aliasDraft = cleanAlias,
                portDraft = parsedPort.toString(),
                tlsEnabled = nextTlsEnabled,
                tlsDraftEnabled = nextTlsEnabled,
                settingsError = null,
            )
        }

        restartNodeInternal()
        if (nodeFsm.currentState() == LocalNodeState.AVAILABLE) {
            val modeLabel = if (nextTlsEnabled) "HTTPS/TLS" else "HTTP"
            val actualPort = _uiState.value.boundHttpPort
            val message = if (actualPort != parsedPort) {
                "Configuracoes aplicadas ($modeLabel). Porta $parsedPort em uso; alternado automaticamente para $actualPort."
            } else {
                "Configuracoes aplicadas e servidor reiniciado ($modeLabel)."
            }
            _uiState.update { it.copy(statusBanner = message) }
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }
}

