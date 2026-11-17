package br.edu.fileshare.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import br.edu.fileshare.android.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot exibido na notificacao persistente do `FsapForegroundService` (`TASK-AND-003`).
 */
data class FsapNotificationState(
    val title: String,
    val text: String,
    val progressPercent: Int? = null,
    val ongoing: Boolean = true,
    val hasActiveTransfers: Boolean = false,
)

/**
 * Gerencia permissao `POST_NOTIFICATIONS` no Android 13+ (API 33+) (`TASK-AND-003`).
 */
object AndroidNotificationPermissionHelper {

    fun requiresRuntimeNotificationPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun isPostNotificationsGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (!requiresRuntimeNotificationPermission(sdkInt)) {
            return true
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun shouldRequestPostNotifications(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean = requiresRuntimeNotificationPermission(sdkInt) && !isPostNotificationsGranted(context, sdkInt)
}

/**
 * Auxiliar de ciclo de vida para solicitar `POST_NOTIFICATIONS` a partir de uma [ComponentActivity].
 */
class AndroidNotificationPermissionRequester(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val requestMutex = Mutex()
    private var launcher: ActivityResultLauncher<String>? = null

    @Volatile
    private var pendingResult: CompletableDeferred<Boolean>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pendingResult?.complete(granted)
        }
    }

    suspend fun ensureNotificationPermission(): Boolean = requestMutex.withLock {
        if (AndroidNotificationPermissionHelper.isPostNotificationsGranted(context, sdkInt)) {
            return@withLock true
        }
        val activeLauncher = launcher ?: return@withLock false
        val deferred = CompletableDeferred<Boolean>()
        pendingResult?.cancel()
        pendingResult = deferred
        return@withLock try {
            activeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            deferred.await()
        } finally {
            if (pendingResult === deferred) {
                pendingResult = null
            }
        }
    }
}

/**
 * Fabrica de canal e notificacoes do `FsapForegroundService` com acao "Parar servico" (`TASK-AND-003`).
 */
object FsapServiceNotificationFactory {

    const val SERVICE_CHANNEL_ID: String = "fileshare_fsap_service"
    const val SERVICE_CHANNEL_NAME: String = "Serviço FileShare (FSAP)"
    const val SERVICE_NOTIFICATION_ID: Int = 53317

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(SERVICE_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            SERVICE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantém o servidor FSAP e descoberta UDP ativos durante recepção e transferências."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildServiceNotification(
        context: Context,
        state: FsapNotificationState,
    ): Notification {
        ensureNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopServiceIntent = FsapForegroundService.createStopIntent(context)
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Parar serviço",
                    stopPendingIntent,
                ).build(),
            )

        if (state.hasActiveTransfers) {
            val cancelIntent = FsapForegroundService.createCancelTransfersIntent(context)
            val cancelPendingIntent = PendingIntent.getService(
                context,
                2,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Cancelar envios",
                    cancelPendingIntent,
                ).build(),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val percent = state.progressPercent
        if (percent != null) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    fun updateNotification(
        context: Context,
        state: FsapNotificationState,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(context, state))
    }
}

