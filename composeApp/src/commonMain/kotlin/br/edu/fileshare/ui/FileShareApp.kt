package br.edu.fileshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import br.edu.fileshare.ui.components.DeviceAvatar
import br.edu.fileshare.ui.components.EmptyStateCard
import br.edu.fileshare.ui.components.FileItemCard
import br.edu.fileshare.ui.components.KShareIcons
import br.edu.fileshare.ui.components.SemanticBadge
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
    activeTransfersCount: Int = 0,
) {
    val state by viewModel.uiState.collectAsState()

    FileShareTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= FileShareResponsiveBreakpoints.WIDE_LAYOUT_THRESHOLD_DP.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    NodeStatusHeader(
                        state = state,
                        isCompactWidth = !useNavigationRail,
                        onDismissBanner = viewModel::dismissBanner,
                        onStartNode = viewModel::startNode,
                        onStopNode = viewModel::stopNode,
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = {
                                        TabNavIcon(
                                            tab = tab,
                                            selectedFilesCount = state.selectedFiles.size,
                                            activeTransfersCount = activeTransfersCount,
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.compactLabel(),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
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
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            MainTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = {
                                        TabNavIcon(
                                            tab = tab,
                                            selectedFilesCount = state.selectedFiles.size,
                                            activeTransfersCount = activeTransfersCount,
                                        )
                                    },
                                    label = { Text(tab.title) },
                                )
                            }
                        }
                    }

                    val contentInsetsModifier = if (useNavigationRail) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                        )
                    } else {
                        Modifier
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(contentInsetsModifier)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
private fun TabNavIcon(
    tab: MainTab,
    selectedFilesCount: Int,
    activeTransfersCount: Int,
) {
    val badgeCount = when (tab) {
        MainTab.TRANSFERS -> activeTransfersCount
        MainTab.SEND -> selectedFilesCount
        else -> 0
    }

    BadgedBox(
        badge = {
            if (badgeCount > 0) {
                Badge {
                    Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                }
            }
        },
    ) {
        Icon(
            imageVector = tab.iconVector(),
            contentDescription = tab.title,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeStatusHeader(
    state: FileShareMainUiState,
    isCompactWidth: Boolean,
    onDismissBanner: () -> Unit,
    onStartNode: () -> Unit,
    onStopNode: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val primaryIp = state.localAddresses.firstOrNull() ?: "127.0.0.1"
            val extraNicsSuffix = if (state.localAddresses.size > 1) " (+${state.localAddresses.size - 1})" else ""
            val endpointLabel = "$primaryIp:${state.boundHttpPort}$extraNicsSuffix"
            val isRunning = state.nodeState == LocalNodeState.AVAILABLE || state.nodeState == LocalNodeState.DISCOVERING
            val canToggle = state.nodeState != LocalNodeState.INITIALIZING &&
                state.nodeState != LocalNodeState.SHUTTING_DOWN &&
                !state.isBusy

            if (isCompactWidth) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BrandTitleGroup()
                        NodeStateBadge(nodeState = state.nodeState, onRetry = onStartNode)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = state.localAlias,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SemanticBadge(
                                text = endpointLabel,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                monospace = true,
                            )
                            if (state.tlsEnabled) {
                                SemanticBadge(
                                    text = "TLS",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    leadingIcon = KShareIcons.Shield,
                                )
                            }
                        }

                        QuickVisibilityButton(
                            isRunning = isRunning,
                            enabled = canToggle,
                            onStartNode = onStartNode,
                            onStopNode = onStopNode,
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        BrandTitleGroup()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.localAlias,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SemanticBadge(
                                text = endpointLabel,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                monospace = true,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.tlsEnabled) {
                            SemanticBadge(
                                text = "TLS",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                leadingIcon = KShareIcons.Shield,
                            )
                        }
                        NodeStateBadge(nodeState = state.nodeState, onRetry = onStartNode)
                        QuickVisibilityButton(
                            isRunning = isRunning,
                            enabled = canToggle,
                            onStartNode = onStartNode,
                            onStopNode = onStopNode,
                        )
                    }
                }
            }

            state.statusBanner?.let { banner ->
                Spacer(modifier = Modifier.height(10.dp))
                val isError = ByteFormatter.isErrorMessage(banner)
                val bannerContainer = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
                val bannerContent = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
                Surface(
                    color = bannerContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (isError) KShareIcons.Warning else KShareIcons.Check,
                                contentDescription = null,
                                tint = bannerContent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = bannerContent,
                            )
                        }
                        IconButton(onClick = onDismissBanner) {
                            Icon(
                                imageVector = KShareIcons.Close,
                                contentDescription = "Fechar aviso",
                                tint = bannerContent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandTitleGroup() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = KShareIcons.Transfers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = ProjectInfo.PROJECT_NAME,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SemanticBadge(
            text = "${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION}",
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            monospace = true,
        )
    }
}

@Composable
private fun QuickVisibilityButton(
    isRunning: Boolean,
    enabled: Boolean,
    onStartNode: () -> Unit,
    onStopNode: () -> Unit,
) {
    if (isRunning) {
        OutlinedButton(
            onClick = onStopNode,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp),
        ) {
            Text("Ocultar", style = MaterialTheme.typography.labelMedium)
        }
    } else {
        FilledTonalButton(
            onClick = onStartNode,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp),
        ) {
            Text("Ficar visivel", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NodeStateBadge(
    nodeState: LocalNodeState,
    onRetry: () -> Unit,
) {
    when (nodeState) {
        LocalNodeState.AVAILABLE -> SemanticBadge(
            text = "Visivel na rede",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            dotColor = MaterialTheme.colorScheme.tertiary,
        )
        LocalNodeState.DISCOVERING,
        LocalNodeState.INITIALIZING,
        -> SemanticBadge(
            text = "Descobrindo...",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            dotColor = MaterialTheme.colorScheme.secondary,
        )
        LocalNodeState.FAULTED -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            onClick = onRetry,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = KShareIcons.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "Falha (Tentar)",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        LocalNodeState.SHUTTING_DOWN,
        LocalNodeState.STOPPED,
        -> SemanticBadge(
            text = "Modo invisivel",
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                modifier = Modifier.weight(0.52f).fillMaxHeight(),
            )
            PeersAndSendPane(
                state = state,
                onSelectPeer = onSelectPeer,
                onRefreshPeers = onRefreshPeers,
                onSendFiles = onSendFiles,
                manualConnectTrigger = manualConnectTrigger,
                modifier = Modifier.weight(0.48f).fillMaxHeight(),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                item(key = "compact_files_header") {
                    FlowRow(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Text(
                                text = "1. Arquivos para envio",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${state.selectedFiles.size} selecionado(s) • ${ByteFormatter.formatBytes(state.totalSelectedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.selectedFiles.isNotEmpty()) {
                                TextButton(onClick = onClearFiles) {
                                    Text("Limpar")
                                }
                            }
                            FilledTonalButton(
                                onClick = onPickFiles,
                                modifier = Modifier.heightIn(min = 40.dp),
                            ) {
                                Icon(
                                    imageVector = KShareIcons.Plus,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (state.selectedFiles.isEmpty()) "Selecionar" else "Adicionar")
                            }
                        }
                    }
                }

                if (state.selectedFiles.isEmpty()) {
                    item(key = "compact_files_empty") {
                        FilePickerPromptCard(
                            onClick = onPickFiles,
                            compact = true,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = state.selectedFiles,
                        key = { idx, file -> "${file.fileName}#${file.sizeBytes}#$idx" },
                    ) { index, file ->
                        SelectedFileRow(
                            file = file,
                            onRemove = { onRemoveFile(index) },
                        )
                    }
                }

                item(key = "compact_peers_header") {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "2. Dispositivo destino (${state.peers.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onRefreshPeers,
                                modifier = Modifier.heightIn(min = 40.dp),
                            ) {
                                Icon(
                                    imageVector = KShareIcons.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Buscar")
                            }
                            manualConnectTrigger()
                        }
                    }
                }

                if (state.peers.isEmpty()) {
                    item(key = "compact_peers_empty") {
                        EmptyStateCard(
                            icon = KShareIcons.Radar,
                            title = "Buscando dispositivos na rede",
                            subtitle = "Certifique-se de que ambos os aparelhos estao no mesmo Wi-Fi ou use \"Conectar por IP\".",
                        )
                    }
                } else {
                    items(state.peers, key = { it.identity.deviceId }) { peer ->
                        PeerSelectionCard(
                            peer = peer,
                            selected = peer.identity.deviceId == state.selectedPeerDeviceId,
                            onClick = { onSelectPeer(peer.identity.deviceId) },
                        )
                    }
                }
            }

            SendActionFooter(
                selectedFilesCount = state.selectedFiles.size,
                totalSelectedBytes = state.totalSelectedBytes,
                selectedPeerAlias = state.selectedPeer?.identity?.alias,
                canSendFiles = state.canSendFiles,
                isBusy = state.isBusy,
                onSendFiles = onSendFiles,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text = "Arquivos para envio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${files.size} selecionado(s) • ${ByteFormatter.formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (files.isNotEmpty()) {
                        TextButton(onClick = onClearFiles) {
                            Text("Limpar")
                        }
                    }
                    FilledTonalButton(
                        onClick = onPickFiles,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Icon(
                            imageVector = KShareIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (files.isEmpty()) "Selecionar" else "Adicionar")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (files.isEmpty()) {
                FilePickerPromptCard(
                    onClick = onPickFiles,
                    compact = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(files) { index, file ->
                        SelectedFileRow(
                            file = file,
                            onRemove = { onRemoveFile(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePickerPromptCard(
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(
                horizontal = 20.dp,
                vertical = if (compact) 20.dp else 32.dp,
            ),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = KShareIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Selecionar arquivos para enviar",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Escolha documentos, imagens, videos ou arquivos locais",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SelectedFileRow(
    file: FileSource,
    onRemove: () -> Unit,
) {
    FileItemCard(
        fileName = file.fileName,
        subtitle = ByteFormatter.formatBytes(file.sizeBytes),
        trailingContent = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = KShareIcons.Close,
                    contentDescription = "Remover ${file.fileName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Text(
                        text = "Dispositivos na rede",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SemanticBadge(
                        text = state.peers.size.toString(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        monospace = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshPeers,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Icon(
                            imageVector = KShareIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar")
                    }
                    manualConnectTrigger()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (state.peers.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    EmptyStateCard(
                        icon = KShareIcons.Radar,
                        title = "Buscando dispositivos na rede...",
                        subtitle = "Mantenha os aparelhos na mesma rede Wi-Fi/LAN ou utilize \"Conectar por IP\".",
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
            SendActionFooter(
                selectedFilesCount = state.selectedFiles.size,
                totalSelectedBytes = state.totalSelectedBytes,
                selectedPeerAlias = state.selectedPeer?.identity?.alias,
                canSendFiles = state.canSendFiles,
                isBusy = state.isBusy,
                onSendFiles = onSendFiles,
            )
        }
    }
}

@Composable
private fun SendActionFooter(
    selectedFilesCount: Int,
    totalSelectedBytes: Long,
    selectedPeerAlias: String?,
    canSendFiles: Boolean,
    isBusy: Boolean,
    onSendFiles: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (selectedPeerAlias != null) {
                        "Destino: $selectedPeerAlias"
                    } else {
                        "Selecione um dispositivo destino"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    text = "$selectedFilesCount arq. • ${ByteFormatter.formatBytes(totalSelectedBytes)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onSendFiles,
                enabled = canSendFiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = KShareIcons.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                val ctaLabel = when {
                    isBusy -> "Preparando envio..."
                    selectedFilesCount == 0 -> "Selecione arquivos para enviar"
                    selectedPeerAlias == null -> "Escolha um destinatario"
                    else -> "Enviar para $selectedPeerAlias"
                }
                Text(
                    text = ctaLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerSelectionCard(
    peer: DiscoveredPeer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val originLabel = when (peer.origin) {
        PeerOrigin.MULTICAST -> "LAN"
        PeerOrigin.MANUAL_IP -> "IP Manual"
    }
    val peerSupportsTls = FsapTlsCapabilities.supportsTls(peer.identity.capabilities)
    val peerSha256 = FsapTlsCapabilities.extractSha256Fingerprint(peer.identity.capabilities)
    val isOnline = peer.connectionState != PeerConnectionState.DISCONNECTED

    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.medium,
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            DeviceAvatar(
                deviceType = peer.identity.deviceType,
                isSelected = selected,
                isOnline = isOnline,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f),
            ) {
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
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                    )
                    PeerConnectionBadge(connectionState = peer.connectionState)
                }

                Text(
                    text = "${ByteFormatter.formatPlatform(peer.identity.platform)} • ${ByteFormatter.formatDeviceType(peer.identity.deviceType)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${peer.ipAddress}:${peer.port} • $originLabel",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (peerSupportsTls) {
                        val tlsInfo = if (peerSha256 != null) {
                            "TLS ...${FsapTlsCapabilities.shortSuffix(peerSha256)}"
                        } else {
                            "TLS TOFU"
                        }
                        SemanticBadge(
                            text = tlsInfo,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            leadingIcon = KShareIcons.Shield,
                            monospace = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerConnectionBadge(connectionState: PeerConnectionState) {
    when (connectionState) {
        PeerConnectionState.DISCOVERED -> SemanticBadge(
            text = "Disponivel",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        PeerConnectionState.CONNECTING -> SemanticBadge(
            text = "Conectando...",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        PeerConnectionState.CONNECTED -> SemanticBadge(
            text = "Conectado",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            dotColor = MaterialTheme.colorScheme.tertiary,
        )
        PeerConnectionState.STALE -> SemanticBadge(
            text = "Instavel",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        PeerConnectionState.DISCONNECTED -> SemanticBadge(
            text = "Offline",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivedTabContent(
    saveDirectoryLabel: String,
    files: List<StoredFileDescriptor>,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = "Arquivos recebidos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Destino: $saveDirectoryLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SemanticBadge(
                text = "${files.size} arquivo(s)",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                monospace = true,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (files.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                EmptyStateCard(
                    icon = KShareIcons.Folder,
                    title = "Nenhum arquivo recebido ainda",
                    subtitle = "Os arquivos aprovados e verificados via SHA-256 sao gravados automaticamente em $saveDirectoryLabel.",
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(files, key = { it.locationUri }) { file ->
                    val isLocalOnly = file.sha256 == "local" || file.sha256.isBlank()
                    FileItemCard(
                        fileName = file.finalName,
                        subtitle = ByteFormatter.formatBytes(file.sizeBytes),
                        monospaceSubtitle = if (isLocalOnly) {
                            "Armazenado no diretorio de recebidos"
                        } else {
                            "SHA-256: ${file.sha256.take(16)}..."
                        },
                        trailingContent = {
                            if (isLocalOnly) {
                                SemanticBadge(
                                    text = "Local",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                SemanticBadge(
                                    text = "Verificado",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    leadingIcon = KShareIcons.Shield,
                                )
                            }
                        },
                    )
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
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Identidade e Rede Local",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    OutlinedTextField(
                        value = state.aliasDraft,
                        onValueChange = { onDraftChanged(it, state.portDraft, state.tlsDraftEnabled) },
                        label = { Text("Nome visivel (Alias)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.portDraft,
                        onValueChange = { onDraftChanged(state.aliasDraft, it, state.tlsDraftEnabled) },
                        label = { Text("Porta HTTP/TCP (1024..65535)") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            onDraftChanged(state.aliasDraft, state.portDraft, !state.tlsDraftEnabled)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Modo seguro opcional (HTTPS/TLS autoassinado)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Criptografa sinalizacao e transferencia via TLSv1.3/TLSv1.2 com pinning SHA-256. Quando desativado, usa HTTP claro.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.tlsDraftEnabled,
                                onCheckedChange = null,
                            )
                        }
                    }

                    state.settingsError?.let { errorMsg ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(10.dp),
                            ) {
                                Icon(
                                    imageVector = KShareIcons.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = errorMsg,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onApplyAndRestart,
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Text("Aplicar e reiniciar servidor")
                        }
                        if (state.nodeState == LocalNodeState.STOPPED || state.nodeState == LocalNodeState.FAULTED) {
                            OutlinedButton(
                                onClick = onStartServer,
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) {
                                Text("Iniciar servidor")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onStopServer,
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) {
                                Text("Parar servidor")
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Diagnostico do No FSAP/1.0",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    DiagnosticRow(label = "UUID persistente", value = state.localDeviceId)
                    DiagnosticRow(
                        label = "Enderecos IPv4",
                        value = if (state.localAddresses.isEmpty()) "127.0.0.1" else state.localAddresses.joinToString(", "),
                    )
                    DiagnosticRow(label = "Pasta de recebimento", value = state.saveDirectoryLabel)
                    DiagnosticRow(
                        label = "Transporte ativo",
                        value = if (state.tlsEnabled) "HTTPS/TLS (porta ${state.boundHttpPort})" else "HTTP/TCP (porta ${state.boundHttpPort})",
                    )

                    state.localTlsFingerprint?.let { fingerprint ->
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Text(
                                    text = "Certificado X.509 local • ...${FsapTlsCapabilities.shortSuffix(fingerprint)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = FsapTlsCapabilities.formatColonSeparated(fingerprint),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

private fun MainTab.iconVector(): ImageVector = when (this) {
    MainTab.SEND -> KShareIcons.Send
    MainTab.RECEIVED -> KShareIcons.Received
    MainTab.TRANSFERS -> KShareIcons.Transfers
    MainTab.SETTINGS -> KShareIcons.Settings
}

private fun MainTab.compactLabel(): String = when (this) {
    MainTab.SEND -> "Enviar"
    MainTab.RECEIVED -> "Recebidos"
    MainTab.TRANSFERS -> "Transf."
    MainTab.SETTINGS -> "Ajustes"
}
