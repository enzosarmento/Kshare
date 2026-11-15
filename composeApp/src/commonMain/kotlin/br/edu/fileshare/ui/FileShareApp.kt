package br.edu.fileshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.security.tls.FsapTlsCapabilities
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.format.ByteFormatter
import br.edu.fileshare.ui.theme.FileShareTheme
import br.edu.fileshare.ui.viewmodel.FileShareMainUiState
import br.edu.fileshare.ui.viewmodel.FileShareMainViewModel
import br.edu.fileshare.ui.viewmodel.MainTab

/**
 * Pontos de quebra responsivos entre layout vertical (smartphones) e painel duplo + NavigationRail
 * (tablets e Desktop) (`TASK-AND-004`).
 */
object FileShareResponsiveBreakpoints {
    const val WIDE_LAYOUT_THRESHOLD_DP: Int = 600

    fun isWideLayout(widthDp: Int): Boolean = widthDp >= WIDE_LAYOUT_THRESHOLD_DP
}

/**
 * Raiz visual Compose Multiplatform (Desktop e Android) em fluxo unidirecional (`TASK-DCLI-001`, `TASK-AND-004`).
 */
@Composable
fun FileShareApp(
    viewModel: FileShareMainViewModel,
    manualConnectTrigger: @Composable () -> Unit = {},
    transfersContent: @Composable () -> Unit = { DefaultTransfersPlaceholder() },
    overlayDialogs: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    FileShareTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= FileShareResponsiveBreakpoints.WIDE_LAYOUT_THRESHOLD_DP.dp

            Scaffold(
                topBar = {
                    NodeStatusHeader(
                        state = state,
                        isCompactWidth = !useNavigationRail,
                        onDismissBanner = viewModel::dismissBanner,
                        onRetryStart = viewModel::startNode,
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = { Text(tabBadgeText(tab), style = MaterialTheme.typography.labelLarge) },
                                    label = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (useNavigationRail) {
                        NavigationRail(
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            MainTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = { Text(tabBadgeText(tab), fontWeight = FontWeight.Bold) },
                                    label = { Text(tab.title) },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(16.dp),
                    ) {
                        when (state.activeTab) {
                            MainTab.SEND -> SendTabContent(
                                state = state,
                                isWideLayout = useNavigationRail,
                                onPickFiles = viewModel::pickFiles,
                                onRemoveFile = viewModel::removeSelectedFileAt,
                                onClearFiles = viewModel::clearSelectedFiles,
                                onSelectPeer = viewModel::selectPeer,
                                onRefreshPeers = viewModel::refreshPeers,
                                onSendFiles = viewModel::sendSelectedFiles,
                                manualConnectTrigger = manualConnectTrigger,
                            )

                            MainTab.RECEIVED -> ReceivedTabContent(
                                saveDirectoryLabel = state.saveDirectoryLabel,
                                files = state.receivedFiles,
                            )

                            MainTab.TRANSFERS -> transfersContent()

                            MainTab.SETTINGS -> SettingsTabContent(
                                state = state,
                                onDraftChanged = viewModel::updateSettingsDraft,
                                onApplyAndRestart = viewModel::applySettingsAndRestart,
                                onStopServer = viewModel::stopNode,
                                onStartServer = viewModel::startNode,
                            )
                        }
                    }
                }
            }

            overlayDialogs()
        }
    }
}

@Composable
private fun NodeStatusHeader(
    state: FileShareMainUiState,
    isCompactWidth: Boolean,
    onDismissBanner: () -> Unit,
    onRetryStart: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${ProjectInfo.PROJECT_NAME} (${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val ipSummary = if (state.localAddresses.isEmpty()) {
                        "127.0.0.1"
                    } else {
                        state.localAddresses.joinToString(", ")
                    }
                    val tlsLabel = if (state.tlsEnabled) "HTTPS/TLS" else "HTTP"
                    Text(
                        text = "Dispositivo: ${state.localAlias}  |  Enderecos: $ipSummary :${state.boundHttpPort} ($tlsLabel)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isCompactWidth) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.tlsEnabled) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "TLS",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    NodeStateBadge(nodeState = state.nodeState, onRetry = onRetryStart)
                }
            }

            state.statusBanner?.let { banner ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissBanner) {
                            Text("Fechar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeStateBadge(
    nodeState: LocalNodeState,
    onRetry: () -> Unit,
) {
    val (label, container, content) = when (nodeState) {
        LocalNodeState.AVAILABLE -> Triple(
            "Online",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        LocalNodeState.DISCOVERING,
        LocalNodeState.INITIALIZING,
        -> Triple(
            "Descobrindo...",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        LocalNodeState.FAULTED -> Triple(
            "Falha (Tentar)",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        LocalNodeState.SHUTTING_DOWN,
        LocalNodeState.STOPPED,
        -> Triple(
            "Parado",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        onClick = { if (nodeState == LocalNodeState.FAULTED) onRetry() },
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SendTabContent(
    state: FileShareMainUiState,
    isWideLayout: Boolean,
    onPickFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearFiles: () -> Unit,
    onSelectPeer: (String?) -> Unit,
    onRefreshPeers: () -> Unit,
    onSendFiles: () -> Unit,
    manualConnectTrigger: @Composable () -> Unit,
) {
    if (isWideLayout) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            SelectedFilesPane(
                files = state.selectedFiles,
                totalBytes = state.totalSelectedBytes,
                onPickFiles = onPickFiles,
                onRemoveFile = onRemoveFile,
                onClearFiles = onClearFiles,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            PeersAndSendPane(
                state = state,
                onSelectPeer = onSelectPeer,
                onRefreshPeers = onRefreshPeers,
                onSendFiles = onSendFiles,
                manualConnectTrigger = manualConnectTrigger,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            SelectedFilesPane(
                files = state.selectedFiles,
                totalBytes = state.totalSelectedBytes,
                onPickFiles = onPickFiles,
                onRemoveFile = onRemoveFile,
                onClearFiles = onClearFiles,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            PeersAndSendPane(
                state = state,
                onSelectPeer = onSelectPeer,
                onRefreshPeers = onRefreshPeers,
                onSendFiles = onSendFiles,
                manualConnectTrigger = manualConnectTrigger,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedFilesPane(
    files: List<FileSource>,
    totalBytes: Long,
    onPickFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text("Arquivos para enviar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${files.size} selecionado(s) - ${ByteFormatter.formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (files.isNotEmpty()) {
                        TextButton(onClick = onClearFiles) { Text("Limpar") }
                    }
                    Button(onClick = onPickFiles) { Text("Selecionar arquivos") }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nenhum arquivo selecionado.\nClique em \"Selecionar arquivos\" para adicionar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(files) { index, file ->
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${ByteFormatter.formatBytes(file.sizeBytes)} (${file.mimeType})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onRemoveFile(index) }) {
                                    Text("Remover")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeersAndSendPane(
    state: FileShareMainUiState,
    onSelectPeer: (String?) -> Unit,
    onRefreshPeers: () -> Unit,
    onSendFiles: () -> Unit,
    manualConnectTrigger: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Dispositivos disponiveis (${state.peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshPeers) { Text("Atualizar") }
                    manualConnectTrigger()
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (state.peers.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nenhum dispositivo encontrado na rede.\nUse \"Conectar por IP\" ou aguarde a descoberta.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(state.peers, key = { it.identity.deviceId }) { peer ->
                        PeerSelectionCard(
                            peer = peer,
                            selected = peer.identity.deviceId == state.selectedPeerDeviceId,
                            onClick = { onSelectPeer(peer.identity.deviceId) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSendFiles,
                enabled = state.canSendFiles,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val targetLabel = state.selectedPeer?.identity?.alias ?: "dispositivo"
                Text("Enviar ${state.selectedFiles.size} arquivo(s) para $targetLabel")
            }
        }
    }
}

@Composable
private fun PeerSelectionCard(
    peer: DiscoveredPeer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val (badgeLabel, badgeContainer, badgeContent) = when (peer.connectionState) {
        PeerConnectionState.DISCOVERED -> Triple(
            "Descoberto",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        PeerConnectionState.CONNECTING -> Triple(
            "Conectando...",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        PeerConnectionState.CONNECTED -> Triple(
            "Conectado",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        PeerConnectionState.STALE -> Triple(
            "Instavel (STALE)",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        PeerConnectionState.DISCONNECTED -> Triple(
            "Desconectado",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    val originLabel = when (peer.origin) {
        PeerOrigin.MULTICAST -> "UDP LAN"
        PeerOrigin.MANUAL_IP -> "IP Manual"
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val peerSupportsTls = FsapTlsCapabilities.supportsTls(peer.identity.capabilities)
            val peerSha256 = FsapTlsCapabilities.extractSha256Fingerprint(peer.identity.capabilities)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = peer.identity.alias,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (peerSupportsTls) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "TLS",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Surface(color = badgeContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = badgeLabel,
                            color = badgeContent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${peer.ipAddress}:${peer.port}  |  ${peer.identity.deviceType} / ${peer.identity.platform}  |  $originLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (peerSupportsTls) {
                val tlsInfo = if (peerSha256 != null) {
                    "TLS SHA-256: ...${FsapTlsCapabilities.shortSuffix(peerSha256)}"
                } else {
                    "TLS ativo (verificacao TOFU na primeira conexao)"
                }
                Text(
                    text = tlsInfo,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ReceivedTabContent(
    saveDirectoryLabel: String,
    files: List<StoredFileDescriptor>,
) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Arquivos recebidos", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Diretorio de destino: $saveDirectoryLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nenhum arquivo recebido nesta sessao.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.locationUri }) { file ->
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(file.finalName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${ByteFormatter.formatBytes(file.sizeBytes)}  |  SHA-256: ${file.sha256.take(16)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTabContent(
    state: FileShareMainUiState,
    onDraftChanged: (alias: String, portText: String, tlsEnabled: Boolean) -> Unit,
    onApplyAndRestart: () -> Unit,
    onStopServer: () -> Unit,
    onStartServer: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Configuracoes do no local", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "UUID persistente: ${state.localDeviceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.aliasDraft,
                onValueChange = { onDraftChanged(it, state.portDraft, state.tlsDraftEnabled) },
                label = { Text("Nome visivel (Alias)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.portDraft,
                onValueChange = { onDraftChanged(state.aliasDraft, it, state.tlsDraftEnabled) },
                label = { Text("Porta HTTP/TCP (1024..65535)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Modo seguro opcional (HTTPS/TLS autoassinado)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Criptografa sinalizacao e transferencia via TLSv1.3/TLSv1.2 com pinning SHA-256. Quando desativado, usa HTTP claro (padrao academico).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.tlsDraftEnabled,
                        onCheckedChange = { enabled ->
                            onDraftChanged(state.aliasDraft, state.portDraft, enabled)
                        },
                    )
                }
            }

            state.localTlsFingerprint?.let { fingerprint ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = "Certificado X.509 local ativo  |  Ultimos 8 hex: ${FsapTlsCapabilities.shortSuffix(fingerprint)}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "SHA-256: ${FsapTlsCapabilities.formatColonSeparated(fingerprint)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            state.settingsError?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onApplyAndRestart) {
                    Text("Aplicar e reiniciar servidor")
                }
                if (state.nodeState == LocalNodeState.STOPPED || state.nodeState == LocalNodeState.FAULTED) {
                    OutlinedButton(onClick = onStartServer) {
                        Text("Iniciar servidor")
                    }
                } else {
                    OutlinedButton(onClick = onStopServer) {
                        Text("Parar servidor")
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultTransfersPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Painel de transferencias em carregamento.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun tabBadgeText(tab: MainTab): String = when (tab) {
    MainTab.SEND -> "UP"
    MainTab.RECEIVED -> "DL"
    MainTab.TRANSFERS -> "TX"
    MainTab.SETTINGS -> "CFG"
}

