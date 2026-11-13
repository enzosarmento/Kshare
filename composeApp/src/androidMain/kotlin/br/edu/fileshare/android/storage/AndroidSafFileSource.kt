package br.edu.fileshare.android.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Metadados consultados para um documento Android (`content://` ou URI SAF).
 */
data class AndroidDocumentMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
)

/**
 * Porta testavel sobre `ContentResolver` do Android para consulta de metadados e abertura de stream (`TASK-AND-002`).
 */
interface AndroidContentAccess {
    fun queryMetadata(uriString: String): AndroidDocumentMetadata?

    fun openInputStream(uriString: String): InputStream?
}

/**
 * Implementacao real baseada em `android.content.ContentResolver` (sem uso de `File(uri.path)`).
 */
class ContentResolverAccess(
    context: Context,
) : AndroidContentAccess {

    private val resolver = context.applicationContext.contentResolver

    override fun queryMetadata(uriString: String): AndroidDocumentMetadata? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null

        var displayName: String? = null
        var sizeBytes: Long? = null

        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        val rawSize = cursor.getLong(sizeIdx)
                        if (rawSize >= 0L) {
                            sizeBytes = rawSize
                        }
                    }
                }
            }
        }

        val resolvedSize = sizeBytes
            ?: resolveSizeViaAssetDescriptor(uri)
            ?: measureStreamSizeInChunks(uri)
            ?: return null

        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: "arquivo"

        return AndroidDocumentMetadata(
            displayName = (displayName?.takeIf { it.isNotBlank() } ?: fallbackName),
            sizeBytes = resolvedSize,
            mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        )
    }

    override fun openInputStream(uriString: String): InputStream? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return resolver.openInputStream(uri)
    }

    private fun resolveSizeViaAssetDescriptor(uri: Uri): Long? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            afd.length.takeIf { it >= 0L }
        }
    }.getOrNull()

    private fun measureStreamSizeInChunks(uri: Uri): Long? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }.getOrNull()
}

/**
 * Leitor em streaming via SAF/`ContentResolver` para Android (`TASK-AND-002`).
 *
 * Nunca carrega o arquivo inteiro na RAM (`readBytes()` proibido) nem converte `Uri` em `File(uri.path)`.
 */
class AndroidSafFileSource(
    val uriString: String,
    private val contentAccess: AndroidContentAccess,
    fileNameOverride: String? = null,
    mimeTypeOverride: String? = null,
) : FileSource {

    constructor(
        context: Context,
        uri: Uri,
        fileNameOverride: String? = null,
        mimeTypeOverride: String? = null,
    ) : this(
        uriString = uri.toString(),
        contentAccess = ContentResolverAccess(context),
        fileNameOverride = fileNameOverride,
        mimeTypeOverride = mimeTypeOverride,
    )

    private val metadata: AndroidDocumentMetadata = contentAccess.queryMetadata(uriString)
        ?: throw FsapProtocolException(
            errorCode = FsapErrorCode.FILE_NOT_FOUND,
            message = "Nao foi possivel consultar metadados da URI Android: $uriString",
        )

    init {
        require(metadata.sizeBytes >= 0L) {
            "Tamanho invalido para arquivo SAF ($uriString): ${metadata.sizeBytes}"
        }
    }

    override val fileName: String = FileNameSanitizer.requireValid(
        extractLeafName(fileNameOverride ?: metadata.displayName),
    )

    override val sizeBytes: Long = metadata.sizeBytes

    override val mimeType: String = mimeTypeOverride?.takeIf { it.isNotBlank() }
        ?: metadata.mimeType?.takeIf { it.isNotBlank() }
        ?: DEFAULT_MIME_TYPE

    override suspend fun openReadSession(): FileReadSession = withContext(Dispatchers.IO) {
        val stream = contentAccess.openInputStream(uriString)
            ?: throw FsapProtocolException(
                errorCode = FsapErrorCode.FILE_NOT_FOUND,
                message = "Nao foi possivel abrir fluxo de leitura para '$fileName' ($uriString).",
            )
        InputStreamFileReadSession(stream)
    }

    private class InputStreamFileReadSession(
        private val inputStream: InputStream,
    ) : FileReadSession {

        override suspend fun readChunk(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            if (buffer.isEmpty()) return@withContext 0
            inputStream.read(buffer)
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) {
                runCatching { inputStream.close() }
            }
        }
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        fun extractLeafName(rawName: String): String {
            val trimmed = rawName.trim()
            return trimmed
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { trimmed }
        }
    }
}

