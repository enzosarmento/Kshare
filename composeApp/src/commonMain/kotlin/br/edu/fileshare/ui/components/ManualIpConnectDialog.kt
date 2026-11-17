package br.edu.fileshare.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.edu.fileshare.ui.viewmodel.ManualIpConnectViewModel
import br.edu.fileshare.ui.viewmodel.ManualIpUiState

@Composable
fun ManualIpConnectButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Text("Conectar por IP")
    }
}

/**
 * Dialogo modal de conexao manual por IP/Porta (`TASK-DCLI-003`).
 */
@Composable
fun ManualIpConnectDialogHost(
    viewModel: ManualIpConnectViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    if (!state.isDialogOpen) return

    ManualIpConnectDialog(
        state = state,
        onIpChange = viewModel::onIpInputChange,
        onPortChange = viewModel::onPortInputChange,
        onUseTlsChange = viewModel::onUseTlsChange,
        onDismiss = viewModel::closeDialog,
        onConnect = viewModel::submit,
    )
}

@Composable
fun ManualIpConnectDialog(
    state: ManualIpUiState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onUseTlsChange: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = KShareIcons.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text("Conectar manualmente por IP")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Informe o endereço IPv4 e a porta HTTP/TCP do dispositivo FSAP na mesma rede local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.ipInput,
                    onValueChange = onIpChange,
                    label = { Text("Endereço IP ou Host") },
                    placeholder = { Text("192.168.1.50") },
                    isError = state.ipError != null,
                    supportingText = state.ipError?.let { err -> { Text(err) } },
                    enabled = !state.isConnecting,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = onPortChange,
                    label = { Text("Porta FSAP") },
                    isError = state.portError != null,
                    supportingText = state.portError?.let { err -> { Text(err) } },
                    enabled = !state.isConnecting,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!state.isConnecting) onConnect()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                    onClick = { if (!state.isConnecting) onUseTlsChange(!state.useTls) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Checkbox(
                            checked = state.useTls,
                            onCheckedChange = null,
                            enabled = !state.isConnecting,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Usar HTTPS/TLS opcional (certificado autoassinado)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                state.connectionError?.let { err ->
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
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isConnecting,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text("Cancelar")
            }
        },
        confirmButton = {
            Button(
                onClick = onConnect,
                enabled = !state.isConnecting,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                if (state.isConnecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Conectando...")
                    }
                } else {
                    Text("Conectar")
                }
            }
        },
    )
}
