package br.edu.fileshare.conformance

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.server.ServerCliConfig
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.desktop.storage.DesktopNioFileSource
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.client.PrepareTransferResult
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Testes de resiliencia a desconexao abrupta (TCP RST/EOF), estagnacao de stream (15 s -> parametrizavel),
 * desconexao de pares na PeerRegistry e encerramento gracioso (`T_SHUTDOWN_GRACE = 2000 ms`) (`TASK-XFER-006`).
 */
@OptIn(ExperimentalPathApi::class)
class DisconnectAndTimeoutRecoveryTest {

    private class MutableClock(var nowMs: Long = 10_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T09:30:00Z"
    }

    @Test
    fun `TC-FSAP-021 e recuperacao abrupta TCP RST limpa part e libera slot do servidor`() = runBlocking {
        val tempDir = Files.createTempDirectory("fsap-xfer006-rst-")
        val downloadsDir = tempDir.resolve("downloads")
        val logger = InMemoryFsapLogger()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fileSink = DesktopNioFileSink(downloadsDir)
        val stagingDir = fileSink.stagingDirectory
        val sessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = logger),
            fileSink = fileSink,
            scope = serverScope,
            logger = logger,
        )
        val server = FsapHttpServer(
            config = FsapHttpServerConfig(
                identity = DeviceIdentity(
                    deviceId = PlatformUuidGenerator.nextUuidV4(),
                    alias = "Receptor-RST",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.LINUX,
                    protocolVersion = FsapConstants.PROTOCOL_VERSION,
                    httpPort = 0,
                ),
                host = "127.0.0.1",
                port = 0,
            ),
            logger = logger,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = fileSink,
                approvalHandler = AutoAcceptTransferApprovalHandler,
                streamReadTimeoutMs = 1_500L,
            ),
        )
        val client = FsapHttpClient(
            localIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Remetente-RST",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                protocolVersion = FsapConstants.PROTOCOL_VERSION,
                httpPort = FsapConstants.DEFAULT_HTTP_PORT,
            ),
            logger = logger,
        )

        try {
            val port = server.start()
            val endpoint = FsapPeerEndpoint("127.0.0.1", port)
            val totalSize = 256 * 1024
            val fakeSha = "a".repeat(64)

            val session = assertIs<PrepareTransferResult.Accepted>(
                client.prepareTransfer(
                    endpoint = endpoint,
                    files = listOf(FileItemDto("f1", "queda-brusca.bin", totalSize.toLong(), fakeSha)),
                ),
            ).session

            // Abre TCP com Content-Length integral, envia apenas 32 KB e forca TCP RST via SO_LINGER=0
            Socket("127.0.0.1", port).use { socket ->
                socket.setSoLinger(true, 0)
                val out = socket.getOutputStream()
                val headers = "PUT ${FsapConstants.Routes.transferContent(session.transferId, "f1")} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: $totalSize\r\n" +
                    "${FsapHeaders.VERSION}: ${FsapConstants.PROTOCOL_VERSION}\r\n" +
                    "${FsapHeaders.TRANSFER_TOKEN}: ${session.transferToken}\r\n" +
                    "${FsapHeaders.FILE_SHA256}: $fakeSha\r\n\r\n"
                out.write(headers.encodeToByteArray())
                out.write(ByteArray(32 * 1024) { 0x3C })
                out.flush()
            }

            withTimeout(5_000L) {
                while (sessionManager.findSnapshot(session.transferId)?.state != TransferState.FAILED) {
                    delay(20L)
                }
            }
            val failedSnapshot = sessionManager.findSnapshot(session.transferId)!!
            assertTrue(
                failedSnapshot.errorCode == FsapErrorCode.CONNECTION_LOST ||
                    failedSnapshot.errorCode == FsapErrorCode.SIZE_MISMATCH ||
                    failedSnapshot.errorCode == FsapErrorCode.STREAM_TIMEOUT,
                "Codigo de falha inesperado: ${failedSnapshot.errorCode}",
            )
            withTimeout(2_000L) {
                while (Files.list(stagingDir).use { it.count() } > 0L) delay(20L)
            }
            assertFalse(Files.exists(downloadsDir.resolve("queda-brusca.bin")))
            assertEquals(0, sessionManager.sessions.value.count { !it.state.isTerminal })

            // Servidor segue plenamente funcional para nova transferencia apos recuperacao
            val recoveryData = "recuperado-com-sucesso".encodeToByteArray()
            val coordinator = TransferCoordinator(httpClient = client, parentScope = serverScope, logger = logger)
            val recoveryOutcome = coordinator.sendFiles(
                endpoint = endpoint,
                peerAlias = "Receptor-RST",
                sources = listOf(InMemoryFileSource("apos-queda.txt", recoveryData)),
            )
            assertIs<SendFilesOutcome.Completed>(recoveryOutcome)
            assertTrue(Files.exists(downloadsDir.resolve("apos-queda.txt")))
            coordinator.close()
        } finally {
            client.close()
            server.stop(200, 500)
            serverSessionCleanupSafe(sessionManager)
            serverScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `TC-FSAP-022 timeout de socket no cliente mapeia STREAM_TIMEOUT e desconecta par na PeerRegistry`() = runBlocking {
        val tempDir = Files.createTempDirectory("fsap-xfer006-timeout-")
        val logger = InMemoryFsapLogger()
        val clock = MutableClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val peerRegistry = PeerRegistry(clock = clock, logger = logger)

        val remoteIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Par-Congelado",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.WINDOWS,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
        )

        // Servidor TCP mock que aprova TRANSFER_REQUEST e congela durante PUT /content sem responder
        val frozenServerSocket = ServerSocket(0)
        val serverPort = frozenServerSocket.localPort
        val acceptedSockets = mutableListOf<Socket>()
        val serverThread = Thread {
            runCatching {
                while (!frozenServerSocket.isClosed) {
                    val clientSocket = frozenServerSocket.accept()
                    synchronized(acceptedSockets) { acceptedSockets += clientSocket }
                    Thread {
                        runCatching {
                            val input = clientSocket.getInputStream()
                            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                            val requestLine = reader.readLine() ?: return@Thread
                            var contentLength = 0
                            while (true) {
                                val headerLine = reader.readLine() ?: break
                                if (headerLine.isEmpty()) break
                                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                                    contentLength = headerLine.substringAfter(':').trim().toIntOrNull() ?: 0
                                }
                            }
                            if (requestLine.startsWith("POST ${FsapConstants.Routes.TRANSFERS_PREPARE}")) {
                                val chars = CharArray(contentLength)
                                var offset = 0
                                while (offset < contentLength) {
                                    val r = reader.read(chars, offset, contentLength - offset)
                                    if (r < 0) break
                                    offset += r
                                }
                                val rawReq = String(chars)
                                val transferId = Regex("\"transferId\"\\s*:\\s*\"([^\"]+)\"")
                                    .find(rawReq)?.groupValues?.get(1) ?: PlatformUuidGenerator.nextUuidV4()
                                val respJson = FsapJson.encodeEnvelope(
                                    FsapEnvelopeFactory.create(
                                        type = FsapMessageType.TRANSFER_ACCEPT,
                                        senderId = remoteIdentity.deviceId,
                                        payload = TransferAcceptPayload(
                                            transferId = transferId,
                                            transferToken = "tok-congelado-1234567890123456",
                                            acceptedFileIds = listOf("file-1"),
                                            chunkSizeBytes = FsapConstants.CHUNK_SIZE_BYTES,
                                        ),
                                        clock = clock,
                                    ),
                                )
                                val respBytes = respJson.encodeToByteArray()
                                val out = clientSocket.getOutputStream()
                                out.write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: application/json\r\n" +
                                            "Content-Length: ${respBytes.size}\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).encodeToByteArray(),
                                )
                                out.write(respBytes)
                                out.flush()
                            } else {
                                // Congela leitura/resposta no PUT /content alem do timeout configurado (250 ms)
                                Thread.sleep(2_500L)
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }
        }.apply { isDaemon = true; start() }

        val client = FsapHttpClient(
            localIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Cliente-Timeout",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                protocolVersion = FsapConstants.PROTOCOL_VERSION,
                httpPort = FsapConstants.DEFAULT_HTTP_PORT,
            ),
            logger = logger,
            clock = clock,
            connectTimeoutMs = 1_000L,
            streamTimeoutMs = 250L,
            approvalTimeoutMs = 2_000L,
        )
        val coordinator = TransferCoordinator(
            httpClient = client,
            parentScope = scope,
            logger = logger,
            clock = clock,
            peerRegistry = peerRegistry,
        )

        try {
            val peer = DiscoveredPeer(
                identity = remoteIdentity.copy(httpPort = serverPort),
                ipAddress = "127.0.0.1",
                port = serverPort,
                connectionState = PeerConnectionState.CONNECTED,
                origin = PeerOrigin.MANUAL_IP,
                lastSeenEpochMillis = clock.nowMs,
                firstSeenEpochMillis = clock.nowMs,
            )
            // Insere par manualmente na registry como CONNECTED
            peerRegistry.registerManualPeer(
                identity = peer.identity,
                ipAddress = peer.ipAddress,
                port = serverPort,
            )
            assertEquals(1, peerRegistry.peers.value.size)

            val done = CompletableDeferred<SendFilesOutcome>()
            coordinator.launchSendFiles(
                peer = peer,
                sources = listOf(InMemoryFileSource("congelado.bin", ByteArray(4096) { 0x22 })),
                onFinished = { done.complete(it) },
            )

            val outcome = withTimeout(5_000L) { done.await() }
            val failed = assertIs<SendFilesOutcome.Failed>(outcome)
            assertTrue(
                failed.errorCode == FsapErrorCode.STREAM_TIMEOUT || failed.errorCode == FsapErrorCode.CONNECTION_LOST,
                "Esperado STREAM_TIMEOUT ou CONNECTION_LOST, obtido: ${failed.errorCode}",
            )
            // Como a falha de transporte foi notificada, par CONNECTED e marcado DISCONNECTED e removido
            withTimeout(2_000L) {
                while (peerRegistry.peers.value.isNotEmpty()) delay(10L)
            }
            val snap = coordinator.progressTracker.getSnapshot(failed.transferId)!!
            assertEquals(TransferState.FAILED, snap.state)
        } finally {
            coordinator.close()
            client.close()
            frozenServerSocket.close()
            synchronized(acceptedSockets) {
                acceptedSockets.forEach { runCatching { it.close() } }
            }
            serverThread.join(500L)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `graceful shutdown do servidor headless encerra dentro de T_SHUTDOWN_GRACE e remove arquivos part`() = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-xfer006-shutdown-")
        val downloadsDir = tempRoot.resolve("server-downloads")
        val stagingDir = downloadsDir.resolve(".fsap-staging")
        val sourcePath = Files.write(
            tempRoot.resolve("grande-parcial.bin"),
            ByteArray(128 * 1024) { 0x11 },
        )
        val shaHex = IncrementalSha256Channel.computeDigest(DesktopNioFileSource(sourcePath)).sha256Hex
        val logger = InMemoryFsapLogger()

        val server = createDesktopHeadlessServer(
            config = ServerCliConfig(
                port = 0,
                alias = "Shutdown-Server",
                downloadDir = downloadsDir,
                autoAcceptTest = true,
            ),
            container = DesktopServerContainer(logger = logger),
            enableDiscovery = false,
        )
        val client = FsapHttpClient(
            localIdentity = DeviceIdentity(
                deviceId = PlatformUuidGenerator.nextUuidV4(),
                alias = "Client-Shutdown",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                protocolVersion = FsapConstants.PROTOCOL_VERSION,
                httpPort = FsapConstants.DEFAULT_HTTP_PORT,
            ),
            logger = logger,
        )

        var stallSocket: Socket? = null
        try {
            val port = server.start()
            val session = assertIs<PrepareTransferResult.Accepted>(
                client.prepareTransfer(
                    endpoint = FsapPeerEndpoint("127.0.0.1", port),
                    files = listOf(FileItemDto("f1", "grande-parcial.bin", 128L * 1024L, shaHex)),
                ),
            ).session

            // Inicia upload lento via socket e mantém stream aberto enquanto chama stopGracefully()
            stallSocket = Socket("127.0.0.1", port).apply {
                val out = getOutputStream()
                val headers = "PUT ${FsapConstants.Routes.transferContent(session.transferId, "f1")} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${128 * 1024}\r\n" +
                    "${FsapHeaders.VERSION}: ${FsapConstants.PROTOCOL_VERSION}\r\n" +
                    "${FsapHeaders.TRANSFER_TOKEN}: ${session.transferToken}\r\n" +
                    "${FsapHeaders.FILE_SHA256}: $shaHex\r\n\r\n"
                out.write(headers.encodeToByteArray())
                out.write(ByteArray(32 * 1024) { 0x07 })
                out.flush()
            }

            withTimeout(3_000L) {
                while (!Files.isDirectory(stagingDir) || Files.list(stagingDir).use { it.count() } == 0L) {
                    delay(15L)
                }
            }

            val elapsedMs = measureTimeMillis {
                server.stopGracefully()
            }
            assertTrue(
                elapsedMs <= FsapConstants.T_SHUTDOWN_GRACE_MS + 1_500L,
                "Encerramento gracioso excedeu o limite esperado: ${elapsedMs}ms",
            )
            assertFalse(Files.exists(downloadsDir.resolve("grande-parcial.bin")))
            withTimeout(2_000L) {
                while (Files.list(stagingDir).use { it.count() } > 0L) delay(20L)
            }
        } finally {
            runCatching { stallSocket?.close() }
            client.close()
            server.stopGracefully()
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun serverSessionCleanupSafe(sessionManager: ServerTransferSessionManager) {
        runCatching { sessionManager.close() }
    }
}

