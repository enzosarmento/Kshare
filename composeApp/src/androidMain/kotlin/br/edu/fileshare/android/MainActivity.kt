package br.edu.fileshare.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import br.edu.fileshare.android.di.AndroidAppContainer
import br.edu.fileshare.android.runtime.AndroidFileShareRuntime
import br.edu.fileshare.android.service.AndroidNotificationPermissionRequester
import br.edu.fileshare.android.storage.AndroidSafFilePicker
import br.edu.fileshare.ui.FileShareApp
import br.edu.fileshare.ui.components.IncomingTransferApprovalDialogHost
import br.edu.fileshare.ui.components.ManualIpConnectButton
import br.edu.fileshare.ui.components.ManualIpConnectDialogHost
import br.edu.fileshare.ui.components.TransferProgressScreen
import br.edu.fileshare.ui.viewmodel.FileShareMainViewModel
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.MainTab
import br.edu.fileshare.ui.viewmodel.ManualIpConnectViewModel
import br.edu.fileshare.ui.viewmodel.TransferProgressViewModel

/**
 * Ponto de entrada da aplicacao Compose Multiplatform Android (`TASK-AND-004`, `TASK-AND-005`).
 *
 * Conecta a UI responsiva compartilhada em `commonMain` ao seletor SAF, gerenciador de permissao
 * `POST_NOTIFICATIONS` e ciclo de vida em segundo plano do `FsapForegroundService`.
 */
class MainActivity : ComponentActivity() {

    private var runtime: AndroidFileShareRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AndroidAppContainer()
        val safFilePicker = AndroidSafFilePicker(
            activity = this,
            logger = container.logger,
        )
        val notificationPermissionRequester = AndroidNotificationPermissionRequester(
            context = this,
        ).apply {
            register(this@MainActivity)
        }

        val androidRuntime = AndroidFileShareRuntime(
            context = applicationContext,
            filePicker = safFilePicker,
            permissionRequester = notificationPermissionRequester,
            container = container,
        ).also { runtime = it }

        setContent {
            val uiScope = rememberCoroutineScope()
            val mainViewModel = remember(androidRuntime, uiScope) {
                FileShareMainViewModel(
                    runtime = androidRuntime,
                    scope = uiScope,
                    logger = androidRuntime.logger,
                )
            }
            val manualIpViewModel = remember(androidRuntime, uiScope, mainViewModel) {
                ManualIpConnectViewModel(
                    connectPort = { ip, port -> androidRuntime.connectByManualIp(ip, port) },
                    scope = uiScope,
                    onConnected = { peer -> mainViewModel.selectPeer(peer.identity.deviceId) },
                    tlsConnectPort = { ip, port, useTls ->
                        androidRuntime.connectByManualIp(ip, port, useTls = useTls)
                    },
                )
            }
            val incomingApprovalViewModel = remember(androidRuntime, uiScope, mainViewModel) {
                IncomingApprovalViewModel(
                    coordinator = androidRuntime.approvalCoordinator,
                    scope = uiScope,
                    clock = androidRuntime.clock,
                    localTlsFingerprintProvider = { mainViewModel.uiState.value.localTlsFingerprint },
                    peerFingerprintLookup = { senderId -> androidRuntime.lookupPeerTlsFingerprint(senderId) },
                    onAccepted = { mainViewModel.selectTab(MainTab.TRANSFERS) },
                )
            }
            val transferProgressViewModel = remember(androidRuntime, uiScope) {
                TransferProgressViewModel(
                    snapshotsFlow = androidRuntime.transferSnapshots,
                    cancelPort = { transferId, reason ->
                        androidRuntime.cancelTransfer(transferId, reason)
                    },
                    scope = uiScope,
                    onClearCompleted = androidRuntime::clearCompletedTransfers,
                )
            }
            val mainState by mainViewModel.uiState.collectAsState()
            val transferState by transferProgressViewModel.uiState.collectAsState()

            LaunchedEffect(mainViewModel) {
                mainViewModel.startNode()
            }

            FileShareApp(
                viewModel = mainViewModel,
                manualConnectTrigger = {
                    ManualIpConnectButton(onClick = manualIpViewModel::openDialog)
                },
                transfersContent = {
                    TransferProgressScreen(viewModel = transferProgressViewModel)
                },
                overlayDialogs = {
                    ManualIpConnectDialogHost(viewModel = manualIpViewModel)
                    IncomingTransferApprovalDialogHost(
                        viewModel = incomingApprovalViewModel,
                        saveDirectoryLabel = mainState.saveDirectoryLabel,
                    )
                },
                activeTransfersCount = transferState.activeCount,
            )
        }
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        super.onDestroy()
    }
}
