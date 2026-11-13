package br.edu.fileshare.security

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.client.PrepareTransferResult
import br.edu.fileshare.network.client.TransferBatchUploadResult
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.StagedFileSession
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Testes de seguranca e protecao contra exaustao de recursos (`TASK-SEC-003`):
 * - Limite maximo de payload JSON (256 KB) por Content-Length e chunked em todas as rotas JSON;
 * - Verificacao de cota de disco antes e durante o streaming + traducao de ENOSPC -> HTTP 507;
 * - Reserva concorrente de espaco entre sessoes simultaneas;
 * - Corte imediato de overflow de stream na camada de canal e sink -> SIZE_MISMATCH + limpeza .part.
 */
@OptIn(ExperimentalPathApi::class)
class ResourceExhaustionSecurityTest {

    private data class RawHttpResponse(
        val statusCode: Int,
        val bodyText: String,
    )

    private fun testServerIdentity(port: Int = 0): DeviceIdentity = DeviceIdentity(
        deviceId = PlatformUuidGenerator.nextUuidV4(),
        alias = "SecServer",
        deviceType = DeviceType.DESKTOP,
        platform = PlatformType.LINUX,
        httpPort = port,
    )

    private fun testClientIdentity(): DeviceIdentity = DeviceIdentity(
        deviceId = PlatformUuidGenerator.nextUuidV4(),
        alias = "SecClient",
        deviceType = DeviceType.DESKTOP,
        platform = PlatformType.LINUX,
        httpPort = 53399,
    )

    private fun readRawHttpResponse(socket: Socket): RawHttpResponse {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) break
        }
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val statusCode = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull()
        val isChunked = lines.any {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                it.contains("chunked", ignoreCase = true)
        }

        val bodyString = when {
            isChunked -> readChunkedBody(input)
            contentLength != null -> {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            }
            else -> input.readBytes().toString(StandardCharsets.UTF_8)
        }
        return RawHttpResponse(statusCode = statusCode, bodyText = bodyString)
    }

    private fun readChunkedBody(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val lenLine = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch == -1 || ch == '\n'.code) break
                if (ch != '\r'.code) lenLine.append(ch.toChar())
            }
            val sizeHex = lenLine.toString().substringBefore(';').trim()
            if (sizeHex.isEmpty()) break
            val size = sizeHex.toInt(16)
            if (size == 0) {
                while (true) {
                    val line = StringBuilder()
                    while (true) {
                        val ch = input.read()
                        if (ch == -1 || ch == '\n'.code) break
                        if (ch != '\r'.code) line.append(ch.toChar())
                    }
                    if (line.isEmpty()) break
                }
                break
            }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(chunk, 0, read)
            input.read()
            input.read()
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun sendRawJsonRequest(
        port: Int,
        path: String,
        bodyBytes: ByteArray,
        useChunked: Boolean,
    ): RawHttpResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val header = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(port).append("\r\n")
                append("Content-Type: application/json\r\n")
                append(FsapHeaders.PROTOCOL).append(": ").append(FsapConstants.PROTOCOL_NAME).append("\r\n")
                append(FsapHeaders.VERSION).append(": ").append(FsapConstants.PROTOCOL_VERSION).append("\r\n")
                if (useChunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(StandardCharsets.US_ASCII))
            if (useChunked) {
                var offset = 0
                val step = 16_384
                while (offset < bodyBytes.size) {
                    val len = minOf(step, bodyBytes.size - offset)
                    out.write("${len.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    out.write(bodyBytes, offset, len)
                    out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    offset += len
                }
                out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            } else {
                out.write(bodyBytes)
            }
            out.flush()
            return readRawHttpResponse(socket)
        }
    }

    @Test
    fun `rotas JSON rejeitam payload acima de 256 KB por Content-Length e chunked com 413 FSAP-ERR-413-FILE_TOO_LARGE`(): Unit =
        runBlocking {
            val tempDir = Files.createTempDirectory("fsap-sec003-json-")
            val logger = InMemoryFsapLogger()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val fileSink = DesktopNioFileSink(tempDir.resolve("downloads"))
            val sessionManager = ServerTransferSessionManager(
                tokenManager = TransferTokenManager(logger = logger),
                fileSink = fileSink,
                scope = serverScope,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = testServerIdentity(0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = fileSink,
                    approvalHandler = AutoAcceptTransferApprovalHandler,
                ),
            )

            try {
                val port = server.start()
                val oversizedPadding = "a".repeat(FsapConstants.MAX_JSON_BODY_BYTES + 64)
                val oversizedJson = """{"padding":"$oversizedPadding"}""".toByteArray(StandardCharsets.UTF_8)
                assertTrue(oversizedJson.size > FsapConstants.MAX_JSON_BODY_BYTES)

                val fakeTxId = PlatformUuidGenerator.nextUuidV4()
                val routes = listOf(
                    FsapConstants.Routes.IDENTIFY to false,
                    FsapConstants.Routes.TRANSFERS_PREPARE to false,
                    FsapConstants.Routes.TRANSFERS_PREPARE to true,
                    FsapConstants.Routes.transferCancel(fakeTxId) to false,
                    FsapConstants.Routes.transferComplete(fakeTxId) to true,
                )

                for ((route, chunked) in routes) {
                    val response = sendRawJsonRequest(
                        port = port,
                        path = route,
                        bodyBytes = oversizedJson,
                        useChunked = chunked,
                    )
                    assertEquals(413, response.statusCode, "Rota $route (chunked=$chunked) deveria responder 413.")
                    val envelope = FsapJson.decodeEnvelope<ErrorPayload>(response.bodyText)
                    assertEquals(FsapMessageType.ERROR.name, envelope.type)
                    assertEquals(FsapErrorCode.FILE_TOO_LARGE.code, envelope.payload.errorCode)
                }

                assertEquals(0, sessionManager.sessions.value.size)
            } finally {
                sessionManager.close()
                server.stop(200, 500)
                serverScope.cancel()
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `esgotamento de espaco durante streaming e ENOSPC retornam HTTP 507 e removem arquivo part`(): Unit =
        runBlocking {
            val tempDir = Files.createTempDirectory("fsap-sec003-enospc-")
            val downloadsDir = tempDir.resolve("downloads")
            val logger = InMemoryFsapLogger()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val fileSize = FsapConstants.CHUNK_SIZE_BYTES * 3L // 192 KB
            val payloadBytes = ByteArray(fileSize.toInt()) { ((it * 13 + 7) and 0xFF).toByte() }
            val source = InMemoryFileSource("quota-drop.bin", payloadBytes)
            val sha256 = IncrementalSha256Channel.computeDigest(source).sha256Hex

            val backingSink = DesktopNioFileSink(
                targetDirectory = downloadsDir,
                usableSpaceProvider = { _ ->
                    val staging = downloadsDir.resolve(".fsap-staging")
                    val currentPartBytes = if (Files.isDirectory(staging)) {
                        Files.list(staging).use { stream ->
                            stream.filter { it.fileName.toString().endsWith(".part") }
                                .mapToLong { path -> runCatching { path.fileSize() }.getOrDefault(0L) }
                                .sum()
                        }
                    } else {
                        0L
                    }
                    if (currentPartBytes >= FsapConstants.CHUNK_SIZE_BYTES) 1_024L else 20_000_000L
                },
            )

            val sessionManager = ServerTransferSessionManager(
                tokenManager = TransferTokenManager(logger = logger),
                fileSink = backingSink,
                scope = serverScope,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = testServerIdentity(0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = backingSink,
                    approvalHandler = AutoAcceptTransferApprovalHandler,
                ),
            )
            val client = FsapHttpClient(localIdentity = testClientIdentity(), logger = logger)

            try {
                val port = server.start()
                val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = port)
                val transferId = PlatformUuidGenerator.nextUuidV4()
                val fileItem = FileItemDto(
                    fileId = "f1",
                    fileName = "quota-drop.bin",
                    sizeBytes = fileSize,
                    sha256 = sha256,
                )
                val prepare = assertIs<PrepareTransferResult.Accepted>(
                    client.prepareTransfer(
                        endpoint = endpoint,
                        files = listOf(fileItem),
                        transferId = transferId,
                    ),
                )

                val uploadResult = client.uploadAcceptedBatch(
                    session = prepare.session,
                    sourcesByFileId = mapOf("f1" to source),
                )
                val failed = assertIs<TransferBatchUploadResult.Failed>(uploadResult)
                assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, failed.errorCode)

                val remainingParts = if (Files.isDirectory(backingSink.stagingDirectory)) {
                    Files.list(backingSink.stagingDirectory).use { it.count() }
                } else {
                    0L
                }
                assertEquals(0L, remainingParts)
                assertEquals(TransferState.FAILED, sessionManager.findSnapshot(transferId)?.state)

                // Segunda validacao: IOException("No space left on device") dentro de writeChunk -> HTTP 507
                val enospcSink = object : FileSink {
                    override suspend fun availableStorageBytes(): Long = 5_000_000L
                    override suspend fun ensureCapacity(requiredBytes: Long, safetyMarginBytes: Long) = Unit
                    override suspend fun cleanupTransferParts(transferId: String): Int =
                        backingSink.cleanupTransferParts(transferId)

                    override suspend fun openPartSession(
                        transferId: String,
                        fileId: String,
                        requestedFileName: String,
                        expectedSizeBytes: Long,
                    ): StagedFileSession {
                        val delegate = backingSink.openPartSession(
                            transferId = transferId,
                            fileId = fileId,
                            requestedFileName = requestedFileName,
                            expectedSizeBytes = expectedSizeBytes,
                        )
                        return object : StagedFileSession by delegate {
                            override suspend fun writeChunk(buffer: ByteArray, offset: Int, length: Int) {
                                throw IOException("No space left on device")
                            }
                        }
                    }
                }
                val enospcManager = ServerTransferSessionManager(
                    tokenManager = TransferTokenManager(logger = logger),
                    fileSink = enospcSink,
                    scope = serverScope,
                    logger = logger,
                )
                val enospcServer = FsapHttpServer(
                    config = FsapHttpServerConfig(
                        identity = testServerIdentity(0),
                        host = "127.0.0.1",
                        port = 0,
                    ),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = enospcManager,
                        fileSink = enospcSink,
                        approvalHandler = AutoAcceptTransferApprovalHandler,
                    ),
                )
                try {
                    val enospcPort = enospcServer.start()
                    val enospcEndpoint = FsapPeerEndpoint("127.0.0.1", enospcPort)
                    val txEnospc = PlatformUuidGenerator.nextUuidV4()
                    val smallBytes = ByteArray(1024) { 0x2A }
                    val smallSource = InMemoryFileSource("enospc.bin", smallBytes)
                    val smallSha = IncrementalSha256Channel.computeDigest(smallSource).sha256Hex
                    val enospcItem = FileItemDto("f2", "enospc.bin", 1024L, smallSha)

                    val prepEnospc = assertIs<PrepareTransferResult.Accepted>(
                        client.prepareTransfer(
                            endpoint = enospcEndpoint,
                            files = listOf(enospcItem),
                            transferId = txEnospc,
                        ),
                    )
                    val enospcUpload = client.uploadAcceptedBatch(
                        session = prepEnospc.session,
                        sourcesByFileId = mapOf("f2" to smallSource),
                    )
                    val enospcFailed = assertIs<TransferBatchUploadResult.Failed>(enospcUpload)
                    assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, enospcFailed.errorCode)
                } finally {
                    enospcManager.close()
                    enospcServer.stop(200, 500)
                }
            } finally {
                client.close()
                sessionManager.close()
                server.stop(200, 500)
                serverScope.cancel()
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `reserva de cota concorrente impede que segunda sessao simultanea ultrapasse espaco livre`(): Unit =
        runBlocking {
            val tempDir = Files.createTempDirectory("fsap-sec003-quota-")
            val logger = InMemoryFsapLogger()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sink = DesktopNioFileSink(
                targetDirectory = tempDir.resolve("downloads"),
                usableSpaceProvider = { _ -> 16L * 1024L * 1024L + 100_000L },
            )
            val firstTxId = PlatformUuidGenerator.nextUuidV4()
            val secondTxId = PlatformUuidGenerator.nextUuidV4()
            val secondRetryTxId = PlatformUuidGenerator.nextUuidV4()
            val tx1Gate = CompletableDeferred<TransferApprovalDecision>()
            val approvalHandler = TransferApprovalHandler { context: ApprovalRequestContext ->
                if (context.transferId == firstTxId) {
                    tx1Gate.await()
                } else {
                    TransferApprovalDecision.Approved(
                        acceptedFileIds = context.request.files.map { it.fileId },
                    )
                }
            }
            val sessionManager = ServerTransferSessionManager(
                tokenManager = TransferTokenManager(logger = logger),
                fileSink = sink,
                scope = serverScope,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = testServerIdentity(0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = sink,
                    approvalHandler = approvalHandler,
                ),
            )
            val client = FsapHttpClient(localIdentity = testClientIdentity(), logger = logger)

            try {
                val port = server.start()
                val endpoint = FsapPeerEndpoint("127.0.0.1", port)
                val dummyHash = "a".repeat(64)

                // Sessao 1 entra em PENDING_APPROVAL reservando 60 KB de 100 KB livres
                val firstDeferred = async {
                    client.prepareTransfer(
                        endpoint = endpoint,
                        files = listOf(FileItemDto("f1", "part1.bin", 60_000L, dummyHash)),
                        transferId = firstTxId,
                    )
                }

                withTimeout(3_000) {
                    while (sessionManager.findSnapshot(firstTxId)?.state != TransferState.PENDING_APPROVAL) {
                        delay(15)
                    }
                }
                assertEquals(60_000L, sessionManager.reservedRemainingBytes())

                // Sessao 2 tenta reservar outros 60 KB -> 60 + 60 = 120 KB > 100 KB -> HTTP 507 imediato
                val secondResult = client.prepareTransfer(
                    endpoint = endpoint,
                    files = listOf(FileItemDto("f2", "part2.bin", 60_000L, dummyHash)),
                    transferId = secondTxId,
                )
                val secondFailed = assertIs<PrepareTransferResult.Failed>(secondResult)
                assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, secondFailed.errorCode)

                // Recusa sessao 1 liberando reserva; tentativa subsequente de 60 KB deve ser aceita
                tx1Gate.complete(
                    TransferApprovalDecision.Rejected(
                        reasonCode = RejectReasonCode.USER_DECLINED,
                        reasonMessage = "Liberando cota",
                    ),
                )
                assertIs<PrepareTransferResult.Rejected>(firstDeferred.await())
                assertEquals(0L, sessionManager.reservedRemainingBytes())

                val retrySecond = client.prepareTransfer(
                    endpoint = endpoint,
                    files = listOf(FileItemDto("f2", "part2.bin", 60_000L, dummyHash)),
                    transferId = secondRetryTxId,
                )
                assertIs<PrepareTransferResult.Accepted>(retrySecond)
                Unit
            } finally {
                client.close()
                sessionManager.close()
                server.stop(200, 500)
                serverScope.cancel()
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `overflow de stream acima de sizeBytes corta gravacao com SIZE_MISMATCH e limpa arquivo part`(): Unit =
        runBlocking {
            val tempDir = Files.createTempDirectory("fsap-sec003-overflow-")
            val logger = InMemoryFsapLogger()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sink = DesktopNioFileSink(tempDir.resolve("downloads"))
            val sessionManager = ServerTransferSessionManager(
                tokenManager = TransferTokenManager(logger = logger),
                fileSink = sink,
                scope = serverScope,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = testServerIdentity(0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = sink,
                    approvalHandler = AutoAcceptTransferApprovalHandler,
                ),
            )
            val client = FsapHttpClient(localIdentity = testClientIdentity(), logger = logger)

            try {
                // 1) Verifica corte estrito de overflow na camada do channel + sink
                val declaredSize = (FsapConstants.CHUNK_SIZE_BYTES * 2).toLong() // 128 KB
                val overflowingBytes = ByteArray(declaredSize.toInt() + 128) { 0x42 }
                val expectedSha = IncrementalSha256Channel.computeDigest(
                    InMemoryFileSource("expected.bin", overflowingBytes.copyOf(declaredSize.toInt())),
                ).sha256Hex

                val partSession = sink.openPartSession(
                    transferId = "tx-direct-overflow",
                    fileId = "f-over",
                    requestedFileName = "overflow.bin",
                    expectedSizeBytes = declaredSize,
                )
                val channelOverflow = assertFailsWith<FsapProtocolException> {
                    IncrementalSha256Channel.receiveChannelToSinkVerified(
                        readChannel = ByteReadChannel(overflowingBytes),
                        sinkSession = partSession,
                        expectedSizeBytes = declaredSize,
                        expectedSha256 = expectedSha,
                    )
                }
                assertEquals(FsapErrorCode.SIZE_MISMATCH, channelOverflow.errorCode)
                assertEquals(
                    0L,
                    Files.list(sink.stagingDirectory).use { it.count() },
                    "Staging precisa ficar vazio apos overflow no streaming.",
                )

                // 2) Verifica bloqueio direto em StagedFileSession.writeChunk acima de expectedSizeBytes
                val directSession = sink.openPartSession(
                    transferId = "tx-sink-guard",
                    fileId = "f-guard",
                    requestedFileName = "guard.bin",
                    expectedSizeBytes = 100L,
                )
                val sinkOverflow = assertFailsWith<FsapProtocolException> {
                    directSession.writeChunk(ByteArray(101), 0, 101)
                }
                assertEquals(FsapErrorCode.SIZE_MISMATCH, sinkOverflow.errorCode)
                directSession.discardPart()

                // 3) Verifica rejeicao HTTP PUT /content quando Content-Length excede sizeBytes declarado
                val port = server.start()
                val endpoint = FsapPeerEndpoint("127.0.0.1", port)
                val txId = PlatformUuidGenerator.nextUuidV4()
                val filePayload = ByteArray(1024) { 0x11 }
                val fileSha = IncrementalSha256Channel.computeDigest(
                    InMemoryFileSource("valid.bin", filePayload),
                ).sha256Hex
                val accepted = assertIs<PrepareTransferResult.Accepted>(
                    client.prepareTransfer(
                        endpoint = endpoint,
                        files = listOf(FileItemDto("f-http", "valid.bin", 1024L, fileSha)),
                        transferId = txId,
                    ),
                )

                val oversizedSource = InMemoryFileSource("valid.bin", ByteArray(2048) { 0x11 })
                val httpOverflow = assertFailsWith<FsapProtocolException> {
                    client.uploadSingleFileContent(
                        session = accepted.session,
                        fileItem = FileItemDto("f-http", "valid.bin", 2048L, fileSha),
                        source = oversizedSource,
                    )
                }
                assertEquals(FsapErrorCode.SIZE_MISMATCH, httpOverflow.errorCode)
                assertEquals(0L, Files.list(sink.stagingDirectory).use { it.count() })
            } finally {
                client.close()
                sessionManager.close()
                server.stop(200, 500)
                serverScope.cancel()
                tempDir.deleteRecursively()
            }
        }
}
