package br.edu.fileshare.android.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.StagedFileSession
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Estrategia de publicacao final apos validacao SHA-256 no Android (`TASK-AND-002`).
 * Jamais deve ser acionada durante o estado `TRANSFERRING`.
 */
interface AndroidDestinationPublisher {
    fun availableBytes(): Long

    fun exists(fileName: String): Boolean

    fun publishFromPart(
        partFile: File,
        requestedFileName: String,
        mimeType: String,
        verifiedSha256: String,
        totalBytesWritten: Long,
    ): StoredFileDescriptor
}

/**
 * Publicador padrao em `MediaStore.Downloads` (Android 10 / API 29+) com `IS_PENDING = 1 -> 0`
 * e limpeza automatica da entrada pendente em caso de falha (`TASK-AND-002`).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloadsPublisher(
    context: Context,
    private val relativeDirectory: String = "${Environment.DIRECTORY_DOWNLOADS}/FileShare",
) : AndroidDestinationPublisher {

    private val resolver = context.applicationContext.contentResolver

    override fun availableBytes(): Long = runCatching {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists()) {
            StatFs(downloadsDir.absolutePath).availableBytes
        } else {
            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
        }
    }.getOrDefault(Long.MAX_VALUE)

    override fun exists(fileName: String): Boolean {
        val cleanName = FileNameSanitizer.requireValid(fileName)
        val normalizedRelNoSlash = relativeDirectory.trimEnd('/')
        val normalizedRelWithSlash = "$normalizedRelNoSlash/"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val args = arrayOf(cleanName, normalizedRelNoSlash, normalizedRelWithSlash)

        return runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        }.getOrDefault(false)
    }

    override fun publishFromPart(
        partFile: File,
        requestedFileName: String,
        mimeType: String,
        verifiedSha256: String,
        totalBytesWritten: Long,
    ): StoredFileDescriptor {
        val resolvedName = FileNameSanitizer.resolveNonOverwritingName(requestedFileName) { candidate ->
            exists(candidate)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw FsapProtocolException(
                errorCode = FsapErrorCode.INTERNAL_ERROR,
                message = "Falha ao criar entrada em MediaStore.Downloads para '$resolvedName'.",
            )

        try {
            val outStream = resolver.openOutputStream(itemUri, "w")
                ?: throw IOException("ContentResolver retornou OutputStream nulo para $itemUri")

            outStream.use { output ->
                partFile.inputStream().use { input ->
                    val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            }

            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, finalizeValues, null, null)

            return StoredFileDescriptor(
                finalName = resolvedName,
                locationUri = itemUri.toString(),
                sizeBytes = totalBytesWritten,
                sha256 = verifiedSha256,
            )
        } catch (err: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            if (err is FsapProtocolException) throw err
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INTERNAL_ERROR,
                message = "Falha ao publicar arquivo validado em MediaStore.Downloads: ${err.message}",
                cause = err,
            )
        }
    }
}

/**
 * Publicador para diretorio autorizado pelo usuario via `OpenDocumentTree` (`DocumentsContract`).
 */
class SafTreeDestinationPublisher(
    context: Context,
    private val treeUri: Uri,
) : AndroidDestinationPublisher {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val treeDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocumentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    private val childrenUri: Uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

    override fun availableBytes(): Long = runCatching {
        StatFs(appContext.cacheDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    override fun exists(fileName: String): Boolean {
        val cleanName = FileNameSanitizer.requireValid(fileName)
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0 && cursor.getString(idx) == cleanName) {
                        return@use true
                    }
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    override fun publishFromPart(
        partFile: File,
        requestedFileName: String,
        mimeType: String,
        verifiedSha256: String,
        totalBytesWritten: Long,
    ): StoredFileDescriptor {
        val resolvedName = FileNameSanitizer.resolveNonOverwritingName(requestedFileName) { candidate ->
            exists(candidate)
        }

        val targetUri = DocumentsContract.createDocument(
            resolver,
            rootDocumentUri,
            mimeType,
            resolvedName,
        ) ?: throw FsapProtocolException(
            errorCode = FsapErrorCode.INTERNAL_ERROR,
            message = "Falha ao criar documento na pasta SAF autorizada para '$resolvedName'.",
        )

        try {
            val outStream = resolver.openOutputStream(targetUri, "w")
                ?: throw IOException("OutputStream nulo ao gravar documento SAF $targetUri")

            outStream.use { output ->
                partFile.inputStream().use { input ->
                    val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            }

            return StoredFileDescriptor(
                finalName = resolvedName,
                locationUri = targetUri.toString(),
                sizeBytes = totalBytesWritten,
                sha256 = verifiedSha256,
            )
        } catch (err: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
            if (err is FsapProtocolException) throw err
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INTERNAL_ERROR,
                message = "Falha ao publicar arquivo validado na pasta SAF: ${err.message}",
                cause = err,
            )
        }
    }
}

/**
 * Publicador baseado em diretorio POSIX (usado como fallback para API 26-28 ou testes JVM em `androidUnitTest`).
 */
class DirectoryDestinationPublisher(
    private val destinationDir: File,
    private val usableSpaceProvider: (File) -> Long = { dir ->
        dir.mkdirs()
        dir.usableSpace
    },
) : AndroidDestinationPublisher {

    override fun availableBytes(): Long = runCatching {
        usableSpaceProvider(destinationDir)
    }.getOrDefault(0L)

    override fun exists(fileName: String): Boolean {
        val resolved = resolveContainedFile(fileName)
        return resolved.exists()
    }

    override fun publishFromPart(
        partFile: File,
        requestedFileName: String,
        mimeType: String,
        verifiedSha256: String,
        totalBytesWritten: Long,
    ): StoredFileDescriptor {
        destinationDir.mkdirs()
        val resolvedName = FileNameSanitizer.resolveNonOverwritingName(requestedFileName) { candidate ->
            exists(candidate)
        }
        val targetFile = resolveContainedFile(resolvedName)

        try {
            try {
                Files.move(partFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.copy(partFile.toPath(), targetFile.toPath())
                partFile.delete()
            } catch (_: IOException) {
                // Fallback por stream de 64 KB quando cacheDir e destino estao em volumes distintos
                partFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                            }
                        }
                        output.flush()
                    }
                }
                partFile.delete()
            }

            return StoredFileDescriptor(
                finalName = resolvedName,
                locationUri = targetFile.toURI().toString(),
                sizeBytes = totalBytesWritten,
                sha256 = verifiedSha256,
            )
        } catch (err: Throwable) {
            runCatching { targetFile.delete() }
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INTERNAL_ERROR,
                message = "Falha ao consolidar arquivo no diretorio '$destinationDir': ${err.message}",
                cause = err,
            )
        }
    }

    private fun resolveContainedFile(fileName: String): File {
        destinationDir.mkdirs()
        val sanitized = FileNameSanitizer.requireValid(fileName)
        val canonicalDir = destinationDir.canonicalFile
        val candidate = File(canonicalDir, sanitized).canonicalFile
        if (candidate.parentFile != canonicalDir || !candidate.toPath().startsWith(canonicalDir.toPath())) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_FILENAME,
                message = "Tentativa de escape do diretorio de destino Android detectada.",
            )
        }
        return candidate
    }
}

/**
 * Implementacao Android de [FileSink] (`TASK-AND-002`, `ADR-006`).
 *
 * - Grava bytes recebidos exclusivamente em `context.cacheDir/incoming/<transferId>_<fileId>.part`
 * - Nunca cria entrada em `MediaStore.Downloads` ou SAF antes da verificacao SHA-256 (`commitPart`)
 * - Exclui imediatamente o `.part` em caso de falha, `HASH_MISMATCH` ou cancelamento
 */
class AndroidFileSink(
    stagingDirectory: File,
    private val publisher: AndroidDestinationPublisher,
    private val availableStagingBytesProvider: () -> Long = {
        stagingDirectory.mkdirs()
        stagingDirectory.usableSpace
    },
    private val mimeTypeResolver: (String) -> String = { fileName -> inferMimeType(fileName) },
    private val onCommitted: (StoredFileDescriptor) -> Unit = {},
    private val logger: FsapLogger? = null,
) : FileSink {

    constructor(
        context: Context,
        safTreeUri: Uri? = null,
        onCommitted: (StoredFileDescriptor) -> Unit = {},
        logger: FsapLogger? = null,
    ) : this(
        stagingDirectory = File(context.applicationContext.cacheDir, INCOMING_DIR_NAME),
        publisher = createDefaultPublisher(context.applicationContext, safTreeUri),
        availableStagingBytesProvider = {
            val dir = File(context.applicationContext.cacheDir, INCOMING_DIR_NAME).apply { mkdirs() }
            StatFs(dir.absolutePath).availableBytes
        },
        mimeTypeResolver = { fileName -> inferAndroidMimeType(fileName) },
        onCommitted = onCommitted,
        logger = logger,
    )

    val stagingDirectory: File = stagingDirectory.absoluteFile

    private val commitMutex = Mutex()
    private val canonicalStagingDir: File by lazy {
        this.stagingDirectory.mkdirs()
        this.stagingDirectory.canonicalFile
    }

    override suspend fun availableStorageBytes(): Long = withContext(Dispatchers.IO) {
        queryAvailableStorageBytes()
    }

    private fun queryAvailableStorageBytes(): Long {
        val stagingBytes = runCatching { availableStagingBytesProvider() }.getOrDefault(0L)
        val destinationBytes = runCatching { publisher.availableBytes() }.getOrDefault(stagingBytes)
        return minOf(stagingBytes, destinationBytes)
    }

    override suspend fun openPartSession(
        transferId: String,
        fileId: String,
        requestedFileName: String,
        expectedSizeBytes: Long,
    ): StagedFileSession = withContext(Dispatchers.IO) {
        val sanitizedName = FileNameSanitizer.requireValid(requestedFileName)
        val safeTransfer = sanitizeToken(transferId, "transferId")
        val safeFile = sanitizeToken(fileId, "fileId")

        canonicalStagingDir.mkdirs()
        val available = availableStorageBytes()
        if (expectedSizeBytes > 0L && available < expectedSizeBytes) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                message = "Espaco insuficiente no armazenamento Android ($available B livre, $expectedSizeBytes B necessarios).",
                transferId = transferId,
                fileId = fileId,
            )
        }

        val partId = "${safeTransfer}_${safeFile}$PART_EXTENSION"
        val partFile = resolveContainedStagingFile(partId)
        val channel = FileChannel.open(
            partFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )

        AndroidStagedFileSession(
            transferId = transferId,
            fileId = fileId,
            sanitizedFileName = sanitizedName,
            expectedSizeBytes = expectedSizeBytes,
            partIdentifier = partId,
            partFile = partFile,
            channel = channel,
        )
    }

    override suspend fun cleanupTransferParts(transferId: String): Int = withContext(Dispatchers.IO) {
        if (!stagingDirectory.isDirectory) return@withContext 0
        val safeTransfer = sanitizeToken(transferId, "transferId")
        val prefix = "${safeTransfer}_"
        var deleted = 0
        stagingDirectory.listFiles().orEmpty().forEach { entry ->
            if (entry.isFile && entry.name.startsWith(prefix) && entry.name.endsWith(PART_EXTENSION)) {
                if (entry.delete()) {
                    deleted++
                }
            }
        }
        deleted
    }

    private fun resolveContainedStagingFile(partFileName: String): File {
        val candidate = File(canonicalStagingDir, partFileName).canonicalFile
        if (candidate.parentFile != canonicalStagingDir ||
            !candidate.toPath().startsWith(canonicalStagingDir.toPath())
        ) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_FILENAME,
                message = "Caminho de staging Android fora de cacheDir/incoming.",
            )
        }
        return candidate
    }

    private inner class AndroidStagedFileSession(
        override val transferId: String,
        override val fileId: String,
        override val sanitizedFileName: String,
        private val expectedSizeBytes: Long,
        override val partIdentifier: String,
        private val partFile: File,
        private val channel: FileChannel,
    ) : StagedFileSession {

        private var bytesWritten: Long = 0L
        private var finished: Boolean = false

        override suspend fun writeChunk(buffer: ByteArray, offset: Int, length: Int) {
            check(!finished) { "Sessao de gravacao ja finalizada para $transferId/$fileId." }
            if (length == 0) return
            val nextTotal = bytesWritten + length.toLong()
            if (nextTotal > expectedSizeBytes) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.SIZE_MISMATCH,
                    message = "Overflow de stream: $nextTotal bytes excede expectedSizeBytes ($expectedSizeBytes).",
                    transferId = transferId,
                    fileId = fileId,
                )
            }
            withContext(Dispatchers.IO) {
                val remainingRequired = (expectedSizeBytes - bytesWritten).coerceAtLeast(length.toLong())
                val available = queryAvailableStorageBytes()
                if (available < remainingRequired) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                        message = "Espaco insuficiente no armazenamento Android durante gravacao ($available B livre, $remainingRequired B necessarios).",
                        transferId = transferId,
                        fileId = fileId,
                    )
                }
                try {
                    val slice = ByteBuffer.wrap(buffer, offset, length)
                    while (slice.hasRemaining()) {
                        channel.write(slice)
                    }
                    bytesWritten = nextTotal
                } catch (io: IOException) {
                    val lowSpaceAfterIo = queryAvailableStorageBytes() < remainingRequired
                    if (lowSpaceAfterIo) {
                        throw FsapProtocolException(
                            errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                            message = "Espaco esgotado durante gravacao de '$fileId': ${io.message}",
                            transferId = transferId,
                            fileId = fileId,
                            cause = io,
                        )
                    }
                    throw br.edu.fileshare.storage.StorageExhaustionClassifier.mapWriteOrCommitFailure(
                        throwable = io,
                        fallbackErrorCode = FsapErrorCode.INTERNAL_ERROR,
                        transferId = transferId,
                        fileId = fileId,
                        actionDescription = "Falha de I/O ao gravar chunk em staging Android",
                    )
                }
            }
        }

        override suspend fun flushAndSync() {
            withContext(Dispatchers.IO) {
                try {
                    if (channel.isOpen) {
                        channel.force(true)
                        channel.close()
                    }
                } catch (io: IOException) {
                    throw br.edu.fileshare.storage.StorageExhaustionClassifier.mapWriteOrCommitFailure(
                        throwable = io,
                        fallbackErrorCode = FsapErrorCode.INTERNAL_ERROR,
                        transferId = transferId,
                        fileId = fileId,
                        actionDescription = "Falha ao sincronizar arquivo temporario Android",
                    )
                }
            }
        }

        override suspend fun commitPart(
            verifiedSha256: String,
            totalBytesWritten: Long,
        ): StoredFileDescriptor = commitMutex.withLock {
            check(!finished) { "Sessao ja finalizada para $transferId/$fileId." }
            withContext(Dispatchers.IO) {
                if (channel.isOpen) {
                    channel.force(true)
                    channel.close()
                }

                val descriptor = try {
                    publisher.publishFromPart(
                        partFile = partFile,
                        requestedFileName = sanitizedFileName,
                        mimeType = mimeTypeResolver(sanitizedFileName),
                        verifiedSha256 = verifiedSha256,
                        totalBytesWritten = totalBytesWritten,
                    )
                } catch (err: Throwable) {
                    logger?.error(
                        component = COMPONENT,
                        message = "Erro ao publicar $sanitizedFileName apos verificacao SHA-256: ${err.message}",
                        throwable = err,
                    )
                    if (err !is FsapProtocolException &&
                        br.edu.fileshare.storage.StorageExhaustionClassifier.isOutOfSpaceThrowable(err)
                    ) {
                        throw FsapProtocolException(
                            errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                            message = "Espaco insuficiente ao publicar '$sanitizedFileName': ${err.message}",
                            transferId = transferId,
                            fileId = fileId,
                            cause = err,
                        )
                    }
                    throw err
                }

                runCatching {
                    if (partFile.exists()) {
                        partFile.delete()
                    }
                }
                finished = true
                onCommitted(descriptor)
                descriptor
            }
        }

        override suspend fun discardPart() {
            if (finished && !partFile.exists()) return
            finished = true
            withContext(Dispatchers.IO) {
                runCatching {
                    if (channel.isOpen) {
                        channel.close()
                    }
                }
                runCatching {
                    if (partFile.exists()) {
                        partFile.delete()
                    }
                }
            }
        }
    }

    companion object {
        const val INCOMING_DIR_NAME: String = "incoming"
        const val PART_EXTENSION: String = ".part"
        private const val COMPONENT: String = "AndroidFileSink"

        fun createDefaultPublisher(
            appContext: Context,
            safTreeUri: Uri? = null,
        ): AndroidDestinationPublisher = when {
            safTreeUri != null -> SafTreeDestinationPublisher(appContext, safTreeUri)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStoreDownloadsPublisher(appContext)
            else -> DirectoryDestinationPublisher(resolveLegacyDownloadsDir(appContext))
        }

        private fun resolveLegacyDownloadsDir(context: Context): File {
            val publicDownloads = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }.getOrNull()
            return if (publicDownloads != null) {
                File(publicDownloads, "FileShare")
            } else {
                val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                File(appExternal, "FileShare")
            }
        }

        fun inferAndroidMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isBlank()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: inferMimeType(fileName)
        }

        fun inferMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        fun sanitizeToken(raw: String, field: String): String {
            val cleaned = buildString(raw.length) {
                for (ch in raw) {
                    if (ch.isLetterOrDigit() || ch == '-' || ch == '_') {
                        append(ch)
                    } else {
                        append('_')
                    }
                }
            }
            require(cleaned.isNotBlank()) { "Identificador $field invalido para staging Android." }
            return cleaned
        }
    }
}
