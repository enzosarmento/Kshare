package br.edu.fileshare.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.edu.fileshare.ui.format.ByteFormatter
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.IncomingPromptUiModel

/**
 * Host reativo que apresenta o modal de aprovacao explicita quando houver solicitacao pendente (`TASK-DCLI-005`).
 */
@Composable
fun IncomingTransferApprovalDialogHost(
    viewModel: IncomingApprovalViewModel,
    saveDirectoryLabel: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val prompt = state.activePrompt ?: return

    IncomingTransferApprovalDialog(
        prompt = prompt,
        pendingCount = state.pendingCount,
        isSubmitting = state.isSubmitting,
        onAccept = viewModel::acceptActive,
        onDecline = viewModel::declineActive,
        saveDirectoryLabel = saveDirectoryLabel,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncomingTransferApprovalDialog(
    prompt: IncomingPromptUiModel,
    pendingCount: Int,
    isSubmitting: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    saveDirectoryLabel: String? = null,
) {
    val isUrgentTimeout = prompt.remainingSeconds <= 10L
    val countdownFraction = (prompt.remainingSeconds.coerceIn(0L, 60L) / 60f)

    AlertDialog(
        onDismissRequest = { /* Confirmacao explicita obrigatoria */ },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    DeviceAvatar(
                        deviceType = prompt.senderDeviceType,
                        isSelected = true,
                        isOnline = true,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Receber arquivos?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${prompt.senderAlias} • ${ByteFormatter.formatDeviceType(prompt.senderDeviceType)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pendingCount > 1) {
                    SemanticBadge(
                        text = "1 de $pendingCount",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        monospace = true,
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${prompt.files.size} arquivo(s) • ${ByteFormatter.formatBytes(prompt.totalBytes)}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Expira em ${prompt.remainingSeconds}s",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontFeatureSettings = "tnum",
                            ),
                            color = if (isUrgentTimeout) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    LinearProgressIndicator(
                        progress = { countdownFraction },
                        color = if (isUrgentTimeout) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }

                if (!saveDirectoryLabel.isNullOrBlank()) {
                    Text(
                        text = "Destino: $saveDirectoryLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val tlsSegments = buildList {
                    prompt.localTlsFingerprintSuffix?.let { add("Cert. local: ...$it") }
                    prompt.senderTlsFingerprintSuffix?.let { add("Par: ...$it") }
                }
                if (tlsSegments.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Icon(
                                imageVector = KShareIcons.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "TLS SHA-256 (${tlsSegments.joinToString(" | ")})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SemanticBadge(
                            text = "Integridade SHA-256 verificada",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIcon = KShareIcons.Shield,
                        )
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                ) {
                    items(prompt.files, key = { it.fileId }) { file ->
                        FileItemCard(
                            fileName = file.fileName,
                            subtitle = ByteFormatter.formatBytes(file.sizeBytes),
                        )
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDecline,
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text("Recusar")
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(if (isSubmitting) "Processando..." else "Aceitar")
            }
        },
    )
}
