package br.edu.fileshare.android.storage

import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.StoredFileDescriptor
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidStorageTest {

    private class FakeContentAccess(
        private val entries: Map<String, Pair<AndroidDocumentMetadata, ByteArray>>,
    ) : AndroidContentAccess {
        override fun queryMetadata(uriString: String): AndroidDocumentMetadata? =
            entries[uriString]?.first

        override fun openInputStream(uriString: String): InputStream? =
            entries[uriString]?.second?.let { ByteArrayInputStream(it) }
    }

    private class RecordingPublisher(
        private val delegate: DirectoryDestinationPublisher,
        private val failOnPublish: Boolean = false,
    ) : AndroidDestinationPublisher {
        var publishCalls: Int = 0
            private set

        override fun availableBytes(): Long = delegate.availableBytes()

        override fun exists(fileName: String): Boolean = delegate.exists(fileName)

        override fun publishFromPart(
            partFile: File,
            requestedFileName: String,
            mimeType: String,
            verifiedSha256: String,
            totalBytesWritten: Long,
        ): StoredFileDescriptor {
            publishCalls++
            if (failOnPublish) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.INTERNAL_ERROR,
                    message = "Falha simulada na publicacao MediaStore.",
                )
            }
            return delegate.publishFromPart(
                partFile = partFile,
                requestedFileName = requestedFileName,
                mimeType = mimeType,
                verifiedSha256 = verifiedSha256,
                totalBytesWritten = totalBytesWritten,
            )
        }
    }

    @Test
    fun safFileSourceStreamsLargeContentAndComputesExactSha256() = runTest {
        val payload = ByteArray(200 * 1024) { idx -> (idx % 251).toByte() }
        val expectedSha = sha256Hex(payload)
        val uri = "content://com.android.providers.downloads.documents/document/42"

        val source = AndroidSafFileSource(
            uriString = uri,
            contentAccess = FakeContentAccess(
                mapOf(
                    uri to (
                        AndroidDocumentMetadata(
                            displayName = "relatorio-rede.pdf",
                            sizeBytes = payload.size.toLong(),
                            mimeType = "application/pdf",
                        ) to payload
                    ),
                ),
            ),
        )

        assertEquals("relatorio-rede.pdf", source.fileName)
        assertEquals(payload.size.toLong(), source.sizeBytes)
        assertEquals("application/pdf", source.mimeType)

        val digestSummary = IncrementalSha256Channel.computeDigest(source)
        assertEquals(payload.size.toLong(), digestSummary.totalBytes)
        assertEquals(expectedSha, digestSummary.sha256Hex)
    }

    @Test
    fun safFileSourceAndPickerHandleMissingUriAndUnsafeNames() {
        val goodUri = "content://docs/1"
        val badNameUri = "content://docs/2"
        val missingUri = "content://docs/missing"

        val access = FakeContentAccess(
            mapOf(
                goodUri to (AndroidDocumentMetadata("foto.png", 4L, "image/png") to byteArrayOf(1, 2, 3, 4)),
                badNameUri to (AndroidDocumentMetadata("CON.txt", 3L, "text/plain") to byteArrayOf(1, 2, 3)),
            ),
        )

        val missingEx = assertFailsWith<FsapProtocolException> {
            AndroidSafFileSource(uriString = missingUri, contentAccess = access)
        }
        assertEquals(FsapErrorCode.FILE_NOT_FOUND, missingEx.errorCode)

        val unsafeEx = assertFailsWith<FsapProtocolException> {
            AndroidSafFileSource(uriString = badNameUri, contentAccess = access)
        }
        assertEquals(FsapErrorCode.INVALID_FILENAME, unsafeEx.errorCode)

        val picker = AndroidSafFilePicker(contentAccess = access)
        val resolved = picker.resolveSourcesFromUriStrings(listOf(goodUri, missingUri, badNameUri))
        assertEquals(listOf("foto.png"), resolved.map { it.fileName })
    }

    @Test
    fun androidFileSinkStagesInIncomingDirBeforeVerifiedPublishAndAvoidsOverwrite() = runTest {
        val tempRoot = Files.createTempDirectory("android-sink-success").toFile()
        try {
            val stagingDir = File(tempRoot, "cache/incoming")
            val downloadsDir = File(tempRoot, "Downloads/FileShare")
            val committed = mutableListOf<StoredFileDescriptor>()
            val publisher = RecordingPublisher(DirectoryDestinationPublisher(downloadsDir))
            val sink = AndroidFileSink(
                stagingDirectory = stagingDir,
                publisher = publisher,
                mimeTypeResolver = { "application/pdf" },
                onCommitted = { committed += it },
            )

            val data = ByteArray(150 * 1024) { idx -> ((idx * 7) % 256).toByte() }
            val sha = sha256Hex(data)

            val session = sink.openPartSession(
                transferId = "tx-android-01",
                fileId = "file-01",
                requestedFileName = "apostila.pdf",
                expectedSizeBytes = data.size.toLong(),
            )

            session.writeChunk(data, 0, 65536)
            assertEquals(1, stagingDir.listFiles()?.size, "Arquivo .part deve estar em staging durante TRANSFERRING")
            assertEquals(0, publisher.publishCalls, "Nao deve publicar em Downloads antes do SHA-256")
            assertFalse(File(downloadsDir, "apostila.pdf").exists())

            session.writeChunk(data, 65536, data.size - 65536)
            val descriptor = session.commitPart(verifiedSha256 = sha, totalBytesWritten = data.size.toLong())

            assertEquals("apostila.pdf", descriptor.finalName)
            assertEquals(1, publisher.publishCalls)
            assertEquals(1, committed.size)
            assertEquals(0, stagingDir.listFiles()?.size ?: 0, "Staging deve ficar vazio apos commit")
            assertContentEquals(data, File(downloadsDir, "apostila.pdf").readBytes())

            // Segunda transferencia com mesmo nome deve gerar "apostila (1).pdf" sem sobrescrever
            val data2 = "nova versao".encodeToByteArray()
            val sha2 = sha256Hex(data2)
            val session2 = sink.openPartSession("tx-android-02", "file-01", "apostila.pdf", data2.size.toLong())
            session2.writeChunk(data2, 0, data2.size)
            val descriptor2 = session2.commitPart(verifiedSha256 = sha2, totalBytesWritten = data2.size.toLong())

            assertEquals("apostila (1).pdf", descriptor2.finalName)
            assertContentEquals(data, File(downloadsDir, "apostila.pdf").readBytes())
            assertContentEquals(data2, File(downloadsDir, "apostila (1).pdf").readBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidFileSinkDeletesPartOnHashMismatchAndNeverPublishesToDownloads() = runTest {
        val tempRoot = Files.createTempDirectory("android-sink-hash-mismatch").toFile()
        try {
            val stagingDir = File(tempRoot, "cache/incoming")
            val downloadsDir = File(tempRoot, "Downloads/FileShare")
            val publisher = RecordingPublisher(DirectoryDestinationPublisher(downloadsDir))
            val sink = AndroidFileSink(
                stagingDirectory = stagingDir,
                publisher = publisher,
            )

            val payload = ByteArray(80 * 1024) { idx -> (idx % 127).toByte() }
            val wrongSha = "0".repeat(64)

            val partSession = sink.openPartSession(
                transferId = "tx-corrupt",
                fileId = "file-01",
                requestedFileName = "video.mp4",
                expectedSizeBytes = payload.size.toLong(),
            )

            val ex = assertFailsWith<FsapProtocolException> {
                IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = ByteReadChannel(payload),
                    sinkSession = partSession,
                    expectedSizeBytes = payload.size.toLong(),
                    expectedSha256 = wrongSha,
                    chunkSizeBytes = FsapConstants.CHUNK_SIZE_BYTES,
                )
            }

            assertEquals(FsapErrorCode.HASH_MISMATCH, ex.errorCode)
            assertEquals(0, publisher.publishCalls, "MediaStore/Downloads nunca pode ser tocado em falha de hash")
            assertEquals(0, stagingDir.listFiles()?.size ?: 0, "Arquivo .part deve ser removido de cacheDir")
            assertFalse(File(downloadsDir, "video.mp4").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidFileSinkCleansTransferPartsRejectsTraversalAndChecksCapacity() = runTest {
        val tempRoot = Files.createTempDirectory("android-sink-guards").toFile()
        try {
            val stagingDir = File(tempRoot, "cache/incoming")
            val downloadsDir = File(tempRoot, "Downloads/FileShare")
            val sink = AndroidFileSink(
                stagingDirectory = stagingDir,
                publisher = DirectoryDestinationPublisher(downloadsDir),
                availableStagingBytesProvider = { 1024L },
            )

            val traversalEx = assertFailsWith<FsapProtocolException> {
                sink.openPartSession("tx-sec", "f1", "../../etc/passwd", 10L)
            }
            assertEquals(FsapErrorCode.INVALID_FILENAME, traversalEx.errorCode)

            val spaceEx = assertFailsWith<FsapProtocolException> {
                sink.openPartSession("tx-space", "f1", "grande.bin", 4096L)
            }
            assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, spaceEx.errorCode)

            val s1 = sink.openPartSession("tx-cancel", "f1", "a.txt", 10L)
            val s2 = sink.openPartSession("tx-cancel", "f2", "b.txt", 10L)
            val other = sink.openPartSession("tx-keep", "f1", "c.txt", 10L)

            s1.writeChunk("aaa".encodeToByteArray(), 0, 3)
            s2.writeChunk("bbb".encodeToByteArray(), 0, 3)
            other.writeChunk("ccc".encodeToByteArray(), 0, 3)
            s1.flushAndSync()
            s2.flushAndSync()

            // Limpa apenas tx-cancel mantendo tx-keep
            val deletedCount = sink.cleanupTransferParts("tx-cancel")
            assertEquals(2, deletedCount)
            assertEquals(1, stagingDir.listFiles()?.size)
            assertTrue(File(stagingDir, "tx-keep_f1.part").exists())

            other.discardPart()
            assertEquals(0, stagingDir.listFiles()?.size ?: 0)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun androidFileSinkDiscardDeletesPartEvenIfPublishThrows() = runTest {
        val tempRoot = Files.createTempDirectory("android-sink-publish-err").toFile()
        try {
            val stagingDir = File(tempRoot, "cache/incoming")
            val downloadsDir = File(tempRoot, "Downloads/FileShare")
            val failingPublisher = RecordingPublisher(
                delegate = DirectoryDestinationPublisher(downloadsDir),
                failOnPublish = true,
            )
            val sink = AndroidFileSink(
                stagingDirectory = stagingDir,
                publisher = failingPublisher,
            )

            val data = "conteudo valido".encodeToByteArray()
            val partSession = sink.openPartSession("tx-pub-err", "f1", "nota.txt", data.size.toLong())
            partSession.writeChunk(data, 0, data.size)

            assertFailsWith<FsapProtocolException> {
                partSession.commitPart(verifiedSha256 = sha256Hex(data), totalBytesWritten = data.size.toLong())
            }
            partSession.discardPart()
            assertEquals(0, stagingDir.listFiles()?.size ?: 0, "discardPart deve limpar .part restante")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
