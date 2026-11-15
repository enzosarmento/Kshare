package br.edu.fileshare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.ui.viewmodel.TransferItemUiModel
import br.edu.fileshare.ui.viewmodel.TransferProgressViewModel
import br.edu.fileshare.ui.viewmodel.TransferStatusTone

/**
 * Painel de progresso de transferencias em tempo real (`TASK-DCLI-006`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferProgressScreen(
    viewModel: TransferProgressViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Transferencias",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SemanticBadge(
                    text = "${state.activeCount} ativa(s)",
                    containerColor = if (state.activeCount > 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (state.activeCount > 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    dotColor = if (state.activeCount > 0) MaterialTheme.colorScheme.primary else null,
                )
                SemanticBadge(
                    text = "${state.items.size} total",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    monospace = true,
                )
            }

            if (state.completedCount > 0) {
                TextButton(onClick = viewModel::clearCompleted) {
                    Text("Limpar concluidas (${state.completedCount})")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                EmptyStateCard(
                    icon = KShareIcons.Transfers,
                    title = "Nenhuma transferencia registrada",
                    subtitle = "Envios e recebimentos ativos ou concluidos nesta sessao aparecerao aqui em tempo real.",
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.transferId }) { item ->
                    TransferProgressCard(
                        item = item,
                        onCancel = { viewModel.cancelTransfer(item.transferId) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferProgressCard(
    item: TransferItemUiModel,
    onCancel: () -> Unit,
) {
    var confirmCancel by remember(item.transferId, item.state) { mutableStateOf(false) }

    val progressColor = when (item.tone) {
        TransferStatusTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
        TransferStatusTone.ERROR -> MaterialTheme.colorScheme.error
        TransferStatusTone.WARNING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    val directionIcon = if (item.directionLabel == "Enviando") {
        KShareIcons.Send
    } else {
        KShareIcons.Received
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = directionIcon,
                                contentDescription = item.directionLabel,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.directionLabel}: ${item.peerAlias}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.currentFileName?.let { "${item.filesSummary} • $it" } ?: item.filesSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TransferStateBadge(label = item.stateLabel, tone = item.tone)
            }

            val barModifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
            if (item.isIndeterminate && !item.isCancelling) {
                LinearProgressIndicator(
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = barModifier,
                )
            } else {
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = barModifier,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${item.percentLabel} • ${item.bytesSummary}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (item.canCancel) {
                        "${item.speedLabel} • ${item.etaLabel}"
                    } else {
                        item.filesSummary
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.errorSummary?.let { err ->
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
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (item.canCancel) {
                if (item.state == TransferState.TRANSFERRING && confirmCancel && !item.isCancelling) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Interromper transferencia em andamento?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { confirmCancel = false }) {
                                    Text("Manter")
                                }
                                Button(
                                    onClick = {
                                        confirmCancel = false
                                        onCancel()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                ) {
                                    Text("Confirmar cancelamento")
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (item.state == TransferState.TRANSFERRING) {
                                    confirmCancel = true
                                } else {
                                    onCancel()
                                }
                            },
                            enabled = !item.isCancelling,
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text(if (item.isCancelling) "Cancelando..." else "Cancelar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStateBadge(
    label: String,
    tone: TransferStatusTone,
) {
    when (tone) {
        TransferStatusTone.ACTIVE -> SemanticBadge(
            text = label,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            dotColor = MaterialTheme.colorScheme.primary,
        )
        TransferStatusTone.SUCCESS -> SemanticBadge(
            text = label,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            leadingIcon = KShareIcons.Check,
        )
        TransferStatusTone.WARNING -> SemanticBadge(
            text = label,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TransferStatusTone.ERROR -> SemanticBadge(
            text = label,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingIcon = KShareIcons.Warning,
        )
        TransferStatusTone.NEUTRAL -> SemanticBadge(
            text = label,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
