package br.edu.fileshare.conformance

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.server.ServerCliConfig
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.InMemoryFileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Testes de carga, concorrencia e multiplas conexoes simultaneas (`TC-FSAP-026`, `TASK-TEST-004`).
 */
@OptIn(ExperimentalPathApi::class)
class ConcurrentTransfersTest {

    private data class ConcurrentClientHarness(
        val index: Int,
        val identity: DeviceIdentity,
        val scope: CoroutineScope,
        val httpClient: FsapHttpClient,
        val coordinator: TransferCoordinator,
    ) {
        suspend fun close() {
            coordinator.close()
            httpClient.close()
            scope.cancel()
        }
    }

    /**
     * `FileSource` que permite concluir a leitura rapida de pre-hashing (`sessao 1`) e pausa logo apos
     * entregar o primeiro chunk de 64 KB no stream HTTP real (`sessao >= 2`), possibilitando cancelamento
     * deterministico em pleno envio concorrente.
     */
    private class PausedMidStreamFileSource(
        override val fileName: String,
        private val data: ByteArray,
        private val firstUploadChunkSent: CompletableDeferred<Unit>,
        override val mimeType: String = "application/octet-stream",
    ) : FileSource {
        override val sizeBytes: Long = data.size.toLong()
        private val openCount = AtomicInteger(0)

        override suspend fun openReadSession(): FileReadSession {
            val sessionNumber = openCount.incrementAndGet()
            return object : FileReadSession {
                private var offset = 0
                private val holdGate = CompletableDeferred<Unit>()

                override suspend fun readChunk(buffer: ByteArray): Int {
                    if (offset >= data.size) return -1
                    if (sessionNumber >= 2 && offset >= FsapConstants.CHUNK_SIZE_BYTES) {
                        firstUploadChunkSent.complete(Unit)
                        holdGate.await()
                    }
                    val len = minOf(buffer.size, data.size - offset)
                    data.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + len)
                    offset += len
                    return len
                }

                override suspend fun close() {
                    holdGate.cancel(CancellationException("Stream encerrado"))
                }
            }
        }
    }

    @Test
    fun tcFsap026FourConcurrentClientsTransferMultiFileBatchesWithoutRaceOrCorruption(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tc026-batch-")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val logger = InMemoryFsapLogger()

        val headlessServer = createDesktopHeadlessServer(
            config = ServerCliConfig(
                port = 0,
                alias = "Servidor-Concorrente-M6",
                downloadDir = serverDownloads,
                autoAcceptTest = true,
            ),
            container = DesktopServerContainer(logger = logger),
            enableDiscovery = false,
        )

        val clients = (1..4).map { idx -> createClientHarness(idx, logger) }
        try {
            val serverPort = headlessServer.start()
            val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = serverPort)

            val payloadAByClient = (1..4).associateWith { idx ->
                deterministicPayload(sizeBytes = 150 * 1024, seed = idx * 17 + 3)
            }
            val payloadBByClient = (1..4).associateWith { idx ->
                deterministicPayload(sizeBytes = 220 * 1024, seed = idx * 31 + 7)
            }
            val sharedNamePayloadByClient = (1..4).associateWith { idx ->
                deterministicPayload(sizeBytes = 96 * 1024, seed = idx * 53 + 11)
            }

            val outcomes = withTimeout(30_000) {
                clients.map { client ->
                    async(Dispatchers.IO) {
                        val idx = client.index
                        val sources = listOf(
                            InMemoryFileSource("cliente-$idx-parteA.bin", payloadAByClient.getValue(idx)),
                            InMemoryFileSource("cliente-$idx-parteB.bin", payloadBByClient.getValue(idx)),
                            InMemoryFileSource("relatorio-comum.bin", sharedNamePayloadByClient.getValue(idx)),
                        )
                        client.coordinator.sendFiles(
                            endpoint = endpoint,
                            peerAlias = "Servidor-Concorrente-M6",
                            sources = sources,
                        )
                    }
                }.awaitAll()
            }

            outcomes.forEach { outcome ->
                assertTrue(outcome is SendFilesOutcome.Completed, "Transferencia falhou sob carga: $outcome")
                assertEquals(3, outcome.filesCount)
            }

            for (idx in 1..4) {
                val actualA = Files.readAllBytes(serverDownloads.resolve("cliente-$idx-parteA.bin"))
                val actualB = Files.readAllBytes(serverDownloads.resolve("cliente-$idx-parteB.bin"))
                assertContentEquals(payloadAByClient.getValue(idx), actualA)
                assertContentEquals(payloadBByClient.getValue(idx), actualB)
            }

            val sharedCandidateNames = listOf(
                "relatorio-comum.bin",
                "relatorio-comum (1).bin",
                "relatorio-comum (2).bin",
                "relatorio-comum (3).bin",
            )
            val savedSharedHashes = sharedCandidateNames.map { candidate ->
                val path = serverDownloads.resolve(candidate)
                assertTrue(Files.exists(path), "Arquivo concorrente nao gravado sem sobrescrita: $candidate")
                sha256Hex(Files.readAllBytes(path))
            }.toSet()
            val expectedSharedHashes = sharedNamePayloadByClient.values.map(::sha256Hex).toSet()
            assertEquals(expectedSharedHashes, savedSharedHashes)

            assertNoLeftoverPartFiles(serverDownloads)
        } finally {
            clients.forEach { it.close() }
            headlessServer.stopGracefully()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun tcFsap026MidStreamCancellationInOneClientDoesNotAffectThreeConcurrentPeers(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tc026-cancel-")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val logger = InMemoryFsapLogger()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val sink = DesktopNioFileSink(serverDownloads)
        val sessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = logger),
            fileSink = sink,
            scope = serverScope,
            logger = logger,
        )
        val serverIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Servidor-Isolamento",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val server = FsapHttpServer(
            config = FsapHttpServerConfig(identity = serverIdentity, host = "127.0.0.1", port = 0),
            logger = logger,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = sink,
                approvalHandler = AutoAcceptTransferApprovalHandler,
            ),
        )

        val clients = (1..4).map { idx -> createClientHarness(idx, logger) }
        try {
            val serverPort = server.start()
            val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = serverPort)

            val firstChunkUploaded = CompletableDeferred<Unit>()
            val transferId4 = PlatformUuidGenerator.nextUuidV4()
            val slowPayload = deterministicPayload(sizeBytes = 256 * 1024, seed = 99)
            val peerPayloads = (1..3).associateWith { idx ->
                deterministicPayload(sizeBytes = 190 * 1024, seed = idx * 41 + 5)
            }

            val send4Job = async(Dispatchers.IO) {
                try {
                    clients[3].coordinator.sendFiles(
                        endpoint = endpoint,
                        peerAlias = serverIdentity.alias,
                        sources = listOf(
                            PausedMidStreamFileSource(
                                fileName = "abortado-cliente-4.bin",
                                data = slowPayload,
                                firstUploadChunkSent = firstChunkUploaded,
                            ),
                        ),
                        transferId = transferId4,
                    )
                } catch (_: CancellationException) {
                    SendFilesOutcome.Cancelled(transferId4, "USER_CANCELLED")
                }
            }

            withTimeout(5_000) { firstChunkUploaded.await() }

            val concurrentJobs = (1..3).map { idx ->
                async(Dispatchers.IO) {
                    clients[idx - 1].coordinator.sendFiles(
                        endpoint = endpoint,
                        peerAlias = serverIdentity.alias,
                        sources = listOf(
                            InMemoryFileSource("peer-$idx-integro.bin", peerPayloads.getValue(idx)),
                        ),
                    )
                }
            }

            val cancelled = clients[3].coordinator.cancelTransfer(transferId4, "USER_CANCELLED")
            assertTrue(cancelled, "Cancelamento do cliente 4 deveria ser aceito em andamento.")

            val outcome4 = withTimeout(10_000) {
                try {
                    send4Job.await()
                } catch (_: CancellationException) {
                    SendFilesOutcome.Cancelled(transferId4, "USER_CANCELLED")
                }
            }
            assertTrue(
                outcome4 is SendFilesOutcome.Cancelled ||
                    (outcome4 is SendFilesOutcome.Failed && outcome4.errorCode == FsapErrorCode.TRANSFER_CANCELLED),
                "Esperado cancelamento do cliente 4, obtido: $outcome4",
            )
            assertEquals(
                TransferState.CANCELLED,
                clients[3].coordinator.transfers.value.firstOrNull { it.transferId == transferId4 }?.state,
            )

            val peerOutcomes = withTimeout(20_000) { concurrentJobs.awaitAll() }
            peerOutcomes.forEach { outcome ->
                assertTrue(outcome is SendFilesOutcome.Completed, "Par concorrente falhou apos cancelamento vizinho: $outcome")
            }

            for (idx in 1..3) {
                assertContentEquals(
                    peerPayloads.getValue(idx),
                    Files.readAllBytes(serverDownloads.resolve("peer-$idx-integro.bin")),
                )
            }
            assertFalse(Files.exists(serverDownloads.resolve("abortado-cliente-4.bin")))

            withTimeout(5_000) {
                while (hasAnyPartFile(serverDownloads)) delay(20)
            }
            assertNoLeftoverPartFiles(serverDownloads)
        } finally {
            clients.forEach { it.close() }
            server.stop()
            sessionManager.close()
            serverScope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun tcFsap026ExceedingMaxConcurrentSessionsReturnsBusyAndRecoversSlotAfterCompletion(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tc026-capacity-")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val logger = InMemoryFsapLogger()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val sink = DesktopNioFileSink(serverDownloads)
        val sessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = logger),
            fileSink = sink,
            scope = serverScope,
            logger = logger,
            maxConcurrentSessions = 2,
        )
        val gate1 = CompletableDeferred<TransferApprovalDecision>()
        val gate2 = CompletableDeferred<TransferApprovalDecision>()
        val requestCounter = AtomicInteger(0)

        val controlledApprovalHandler = TransferApprovalHandler { _ ->
            when (requestCounter.incrementAndGet()) {
                1 -> gate1.await()
                2 -> gate2.await()
                else -> TransferApprovalDecision.Approved()
            }
        }

        val serverIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Servidor-Cap-2",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val server = FsapHttpServer(
            config = FsapHttpServerConfig(identity = serverIdentity, host = "127.0.0.1", port = 0),
            logger = logger,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = sink,
                approvalHandler = controlledApprovalHandler,
            ),
        )

        val clients = (1..4).map { idx -> createClientHarness(idx, logger) }
        try {
            val serverPort = server.start()
            val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = serverPort)

            val payload1 = deterministicPayload(sizeBytes = 64 * 1024 + 512, seed = 10)
            val payload2 = deterministicPayload(sizeBytes = 64 * 1024 + 768, seed = 20)
            val payload4 = deterministicPayload(sizeBytes = 32 * 1024 + 128, seed = 40)

            val job1 = async(Dispatchers.IO) {
                clients[0].coordinator.sendFiles(
                    endpoint = endpoint,
                    peerAlias = serverIdentity.alias,
                    sources = listOf(InMemoryFileSource("slot-1.bin", payload1)),
                )
            }
            val job2 = async(Dispatchers.IO) {
                clients[1].coordinator.sendFiles(
                    endpoint = endpoint,
                    peerAlias = serverIdentity.alias,
                    sources = listOf(InMemoryFileSource("slot-2.bin", payload2)),
                )
            }

            withTimeout(5_000) {
                while (sessionManager.sessions.value.count { it.state == TransferState.PENDING_APPROVAL } < 2) {
                    delay(15)
                }
            }

            val overflowOutcome = withTimeout(5_000) {
                clients[2].coordinator.sendFiles(
                    endpoint = endpoint,
                    peerAlias = serverIdentity.alias,
                    sources = listOf(InMemoryFileSource("slot-overflow.bin", byteArrayOf(1, 2, 3, 4))),
                )
            }
            assertTrue(
                overflowOutcome is SendFilesOutcome.Rejected,
                "Terceira sessao simultanea deveria receber rejeicao BUSY: $overflowOutcome",
            )
            assertTrue(
                overflowOutcome.reason?.contains("simultaneas", ignoreCase = true) == true ||
                    overflowOutcome.reason == "BUSY",
                "Motivo inesperado para excesso de capacidade: ${overflowOutcome.reason}",
            )

            gate1.complete(TransferApprovalDecision.Approved())
            gate2.complete(TransferApprovalDecision.Approved())

            val outcome1 = withTimeout(10_000) { job1.await() }
            val outcome2 = withTimeout(10_000) { job2.await() }
            assertTrue(outcome1 is SendFilesOutcome.Completed)
            assertTrue(outcome2 is SendFilesOutcome.Completed)

            withTimeout(5_000) {
                while (sessionManager.sessions.value.any { !it.state.isTerminal }) delay(15)
            }

            val recoveryOutcome = withTimeout(10_000) {
                clients[3].coordinator.sendFiles(
                    endpoint = endpoint,
                    peerAlias = serverIdentity.alias,
                    sources = listOf(InMemoryFileSource("slot-recuperado.bin", payload4)),
                )
            }
            assertTrue(recoveryOutcome is SendFilesOutcome.Completed, "Slot nao liberado apos conclusao: $recoveryOutcome")

            assertContentEquals(payload1, Files.readAllBytes(serverDownloads.resolve("slot-1.bin")))
            assertContentEquals(payload2, Files.readAllBytes(serverDownloads.resolve("slot-2.bin")))
            assertContentEquals(payload4, Files.readAllBytes(serverDownloads.resolve("slot-recuperado.bin")))
            assertFalse(Files.exists(serverDownloads.resolve("slot-overflow.bin")))
            assertNoLeftoverPartFiles(serverDownloads)
        } finally {
            gate1.cancel()
            gate2.cancel()
            clients.forEach { it.close() }
            server.stop()
            sessionManager.close()
            serverScope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    private fun createClientHarness(index: Int, logger: InMemoryFsapLogger): ConcurrentClientHarness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Cliente-Concorrente-$index",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val httpClient = FsapHttpClient(localIdentity = identity, logger = logger)
        val coordinator = TransferCoordinator(
            httpClient = httpClient,
            parentScope = scope,
            logger = logger,
        )
        return ConcurrentClientHarness(
            index = index,
            identity = identity,
            scope = scope,
            httpClient = httpClient,
            coordinator = coordinator,
        )
    }

    private fun deterministicPayload(sizeBytes: Int, seed: Int): ByteArray =
        ByteArray(sizeBytes) { idx -> (((idx * 37) + seed) % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun hasAnyPartFile(downloadDir: java.nio.file.Path): Boolean {
        val stagingDir = downloadDir.resolve(".fsap-staging")
        if (!Files.isDirectory(stagingDir)) return false
        return Files.walk(stagingDir).use { stream ->
            stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
        }
    }

    private fun assertNoLeftoverPartFiles(downloadDir: java.nio.file.Path) {
        assertFalse(hasAnyPartFile(downloadDir), "Arquivos .part residuais encontrados em $downloadDir")
    }
}
