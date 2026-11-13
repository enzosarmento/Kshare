package br.edu.fileshare.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.storage.FileSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Integrador do Storage Access Framework (`OpenMultipleDocuments` e `OpenDocumentTree`)
 * com API assincrona compativel com `FileShareRuntimePort.pickFilesToSend()` (`TASK-AND-002`).
 */
class AndroidSafFilePicker(
    private val contentAccess: AndroidContentAccess,
    private val persistPermission: (Uri) -> Unit = {},
    private val logger: FsapLogger? = null,
) {
    constructor(
        activity: ComponentActivity,
        logger: FsapLogger? = null,
    ) : this(
        contentAccess = ContentResolverAccess(activity.applicationContext),
        persistPermission = { uri ->
            tryPersistReadPermission(activity.applicationContext, uri)
        },
        logger = logger,
    ) {
        register(activity)
    }

    private val pickMutex = Mutex()
    private val treeMutex = Mutex()

    private var openMultipleLauncher: ActivityResultLauncher<Array<String>>? = null
    private var openTreeLauncher: ActivityResultLauncher<Uri?>? = null

    @Volatile
    private var pendingFilesDeferred: CompletableDeferred<List<FileSource>>? = null

    @Volatile
    private var pendingTreeDeferred: CompletableDeferred<Uri?>? = null

    /**
     * Registra os `ActivityResultLauncher` na `ComponentActivity` antes de atingir `STARTED`.
     */
    fun register(activity: ComponentActivity) {
        openMultipleLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            onDocumentsSelected(uris)
        }

        openTreeLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            onTreeSelected(treeUri)
        }
    }

    suspend fun pickMultipleFiles(
        mimeTypes: Array<String> = DEFAULT_MIME_TYPES,
    ): List<FileSource> = pickMutex.withLock {
        val launcher = openMultipleLauncher ?: run {
            logger?.warn(
                component = COMPONENT,
                message = "Seletor SAF chamado antes de register(activity).",
            )
            return@withLock emptyList()
        }

        val deferred = CompletableDeferred<List<FileSource>>()
        pendingFilesDeferred?.cancel()
        pendingFilesDeferred = deferred

        return@withLock try {
            launcher.launch(mimeTypes)
            deferred.await()
        } finally {
            if (pendingFilesDeferred === deferred) {
                pendingFilesDeferred = null
            }
        }
    }

    suspend fun pickDestinationDirectory(initialUri: Uri? = null): Uri? = treeMutex.withLock {
        val launcher = openTreeLauncher ?: run {
            logger?.warn(
                component = COMPONENT,
                message = "Seletor OpenDocumentTree chamado antes de register(activity).",
            )
            return@withLock null
        }

        val deferred = CompletableDeferred<Uri?>()
        pendingTreeDeferred?.cancel()
        pendingTreeDeferred = deferred

        return@withLock try {
            launcher.launch(initialUri)
            deferred.await()
        } finally {
            if (pendingTreeDeferred === deferred) {
                pendingTreeDeferred = null
            }
        }
    }

    internal fun onDocumentsSelected(uris: List<Uri>) {
        val uriStrings = uris.map { uri ->
            persistPermission(uri)
            uri.toString()
        }
        val sources = resolveSourcesFromUriStrings(uriStrings)
        pendingFilesDeferred?.complete(sources)
    }

    internal fun resolveSourcesFromUriStrings(uriStrings: List<String>): List<FileSource> =
        uriStrings.mapNotNull { uriString ->
            runCatching {
                AndroidSafFileSource(
                    uriString = uriString,
                    contentAccess = contentAccess,
                )
            }.onFailure { err ->
                logger?.warn(
                    component = COMPONENT,
                    message = "Ignorando URI SAF inacessivel ($uriString): ${err.message}",
                    throwable = err,
                )
            }.getOrNull()
        }

    internal fun onTreeSelected(treeUri: Uri?) {
        if (treeUri != null) {
            runCatching { persistPermission(treeUri) }
        }
        pendingTreeDeferred?.complete(treeUri)
    }

    companion object {
        val DEFAULT_MIME_TYPES: Array<String> = arrayOf("*/*")
        private const val COMPONENT = "AndroidSafFilePicker"

        private fun tryPersistReadPermission(context: Context, uri: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
}
