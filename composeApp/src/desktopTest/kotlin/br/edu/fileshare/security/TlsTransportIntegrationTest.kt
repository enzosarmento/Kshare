package br.edu.fileshare.security

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.runtime.DesktopAppRuntime
import br.edu.fileshare.desktop.server.ServerCliConfig
import br.edu.fileshare.desktop.server.ServerCliParser
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.desktop.storage.DesktopNioFileSource
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.ManualPeerConnector
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.security.tls.FsapTlsCapabilities
import br.edu.fileshare.security.tls.PlatformTlsProvider
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Suite de integracao para transporte HTTPS/TLS opcional com certificado X.509 autoassinado
 * e pinning/TOFU via SHA-256 (`TASK-SEC-004`).
 */
@OptIn(ExperimentalPathApi::class)
class TlsTransportIntegrationTest {

    @Test
    fun headlessServerTlsAndDesktopRuntimeCompleteHandshakeAndMultiChunkTransfer(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tls-e2e-")
        val serverDownloads = tempRoot.resolve("server-downloads")
        val clientDownloads = tempRoot.resolve("client-downloads")
        val sourceDir = tempRoot.resolve("sources")
        Files.createDirectories(sourceDir)

        val logger = InMemoryFsapLogger()
        val headlessServer = createDesktopHeadlessServer(
            config = ServerCliConfig(
                port = 0,
                alias = "Headless-TLS-Node",
                downloadDir = serverDownloads,
                autoAcceptTest = true,
                tlsEnabled = true,
            ),
            container = DesktopServerContainer(logger = logger),
            enableDiscovery = false,
        )
        val senderRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Desktop-TLS-Sender",
            initialPort = 0,
            downloadDirectory = clientDownloads,
        )

        try {
            val certInfo = headlessServer.tlsCertificateInfo
            assertNotNull(certInfo, "Servidor com --tls deve gerar certificado X.509 local.")
            assertEquals(64, certInfo.sha256Fingerprint.length)
            assertTrue(certInfo.sha256Fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
            assertEquals(certInfo.sha256Fingerprint.takeLast(8).uppercase(), certInfo.shortSuffix)

            val serverPort = headlessServer.start()
            val senderPort = senderRuntime.startNode(
                alias = "Desktop-TLS-Sender",
                port = 0,
                tlsEnabled = true,
            ).getOrThrow()
            assertTrue(senderPort > 0)
            assertTrue(senderRuntime.tlsEnabled.value)
            assertNotNull(senderRuntime.localTlsFingerprint.value)

            val connectResult = senderRuntime.connectByManualIp(
                ipAddress = "127.0.0.1",
                port = serverPort,
                useTls = true,
            )
            assertTrue(connectResult is ManualConnectResult.Connected, "Falha no IDENTIFY sobre HTTPS: $connectResult")
            val remotePeer = connectResult.peer
            assertTrue(FsapTlsCapabilities.supportsTls(remotePeer.identity.capabilities))
            assertEquals(
                certInfo.sha256Fingerprint,
                FsapTlsCapabilities.extractSha256Fingerprint(remotePeer.identity.capabilities),
            )
            val endpoint = FsapTlsCapabilities.endpointForPeer(remotePeer)
            assertEquals("https", endpoint.scheme)
            assertEquals(certInfo.sha256Fingerprint, endpoint.expectedTlsFingerprintSha256)

            val payload = ByteArray(210 * 1024) { idx -> ((idx * 31) % 251).toByte() }
            val sourcePath = sourceDir.resolve("relatorio-tls.bin").also { Files.write(it, payload) }

            val transferId = senderRuntime.sendFiles(
                peer = remotePeer,
                sources = listOf(DesktopNioFileSource(sourcePath)),
            ).getOrThrow()

            withTimeout(10_000) {
                while (
                    senderRuntime.transferCoordinator.transfers.value
                        .firstOrNull { it.transferId == transferId }
                        ?.state != TransferState.COMPLETED
                ) {
                    delay(20)
                }
            }

            assertContentEquals(payload, Files.readAllBytes(serverDownloads.resolve("relatorio-tls.bin")))
            val stagingDir = serverDownloads.resolve(".fsap-staging")
            if (Files.isDirectory(stagingDir)) {
                Files.list(stagingDir).use { stream ->
                    assertTrue(stream.noneMatch { it.fileName.toString().endsWith(".part") })
                }
            }
        } finally {
            senderRuntime.close()
            headlessServer.stopGracefully()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun tlsClientRejectsMismatchedSha256FingerprintAgainstMitmAttack(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tls-pin-fail-")
        val receiverDownloads = tempRoot.resolve("receiver-downloads")
        val logger = InMemoryFsapLogger()

        val receiverRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Receptor-TLS",
            initialPort = 0,
            downloadDirectory = receiverDownloads,
        )
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val senderIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Remetente-Pinning",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
        )
        val httpClient = FsapHttpClient(localIdentity = senderIdentity, logger = logger)
        val peerRegistry = PeerRegistry(logger = logger)
        val manualConnector = ManualPeerConnector(
            localIdentity = senderIdentity,
            peerRegistry = peerRegistry,
            logger = logger,
        )
        val transferCoordinator = TransferCoordinator(
            httpClient = httpClient,
            parentScope = senderScope,
            logger = logger,
            peerRegistry = peerRegistry,
        )

        try {
            val serverPort = receiverRuntime.startNode(
                alias = "Receptor-TLS",
                port = 0,
                tlsEnabled = true,
            ).getOrThrow()
            val realSha256 = receiverRuntime.localTlsFingerprint.value
            assertNotNull(realSha256)

            val forgedSha256 = if (realSha256.startsWith("0")) {
                "f" + realSha256.substring(1)
            } else {
                "0" + realSha256.substring(1)
            }

            val connectBlocked = manualConnector.connectByManualIp(
                ipAddress = "127.0.0.1",
                port = serverPort,
                useTls = true,
                expectedTlsFingerprintSha256 = forgedSha256,
            )
            assertTrue(connectBlocked is ManualConnectResult.Failed)
            assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, connectBlocked.errorCode)

            val spoofedPeer = DiscoveredPeer(
                identity = DeviceIdentity(
                    deviceId = receiverRuntime.localDeviceId,
                    alias = "Receptor-TLS",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.LINUX,
                    protocolVersion = FsapConstants.PROTOCOL_VERSION,
                    httpPort = serverPort,
                    capabilities = FsapTlsCapabilities.withTlsCapabilities(emptyList(), forgedSha256),
                ),
                ipAddress = "127.0.0.1",
                port = serverPort,
                connectionState = PeerConnectionState.DISCOVERED,
                origin = PeerOrigin.MANUAL_IP,
                lastSeenEpochMillis = System.currentTimeMillis(),
                firstSeenEpochMillis = System.currentTimeMillis(),
            )
            val outcome = transferCoordinator.sendFiles(
                endpoint = FsapTlsCapabilities.endpointForPeer(spoofedPeer),
                peerAlias = spoofedPeer.alias,
                sources = listOf(InMemoryFileSource("segredo.txt", "nao deve vazar".encodeToByteArray())),
            )
            assertTrue(outcome is SendFilesOutcome.Failed, "Esperada recusa TLS por mismatch: $outcome")
            assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, outcome.errorCode)
            assertFalse(Files.exists(receiverDownloads.resolve("segredo.txt")))
        } finally {
            manualConnector.close()
            transferCoordinator.close()
            httpClient.close()
            senderScope.cancel()
            receiverRuntime.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun approvalViewModelExposesShortTlsSuffixAndDefaultCleartextHttpRemainsWorking(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("fsap-tls-approval-")
        val receiverDownloads = tempRoot.resolve("receiver-downloads")
        val logger = InMemoryFsapLogger()

        val receiverRuntime = DesktopAppRuntime(
            container = DesktopServerContainer(logger = logger),
            initialAlias = "Receptor-Interativo-TLS",
            initialPort = 0,
            downloadDirectory = receiverDownloads,
        )
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val senderBundle = PlatformTlsProvider.generateSelfSignedBundle(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Remetente-TLS",
            extraIpAddresses = listOf("127.0.0.1"),
        )
        val senderIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Remetente-TLS",
            deviceType = DeviceType.MOBILE,
            platform = PlatformType.ANDROID,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
            capabilities = FsapTlsCapabilities.withTlsCapabilities(
                emptyList(),
                senderBundle.certificateInfo.sha256Fingerprint,
            ),
        )
        val httpClient = FsapHttpClient(localIdentity = senderIdentity, logger = logger)
        val transferCoordinator = TransferCoordinator(
            httpClient = httpClient,
            parentScope = senderScope,
            logger = logger,
        )

        try {
            val tlsPort = receiverRuntime.startNode(
                alias = "Receptor-Interativo-TLS",
                port = 0,
                tlsEnabled = true,
            ).getOrThrow()
            val localFingerprint = assertNotNull(receiverRuntime.localTlsFingerprint.value)
            val expectedLocalSuffix = localFingerprint.takeLast(8).uppercase()
            val expectedSenderSuffix = senderBundle.certificateInfo.shortSuffix

            val approvalVm = IncomingApprovalViewModel(
                coordinator = receiverRuntime.approvalCoordinator,
                scope = receiverRuntime.scope,
                localTlsFingerprintProvider = { receiverRuntime.localTlsFingerprint.value },
                peerFingerprintLookup = { senderId ->
                    if (senderId == senderIdentity.deviceId) senderBundle.certificateInfo.sha256Fingerprint else null
                },
            )

            val payload = ByteArray(140 * 1024) { idx -> (idx % 233).toByte() }
            val sendJob = async {
                transferCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint(
                        host = "127.0.0.1",
                        port = tlsPort,
                        scheme = "https",
                        expectedTlsFingerprintSha256 = localFingerprint,
                    ),
                    peerAlias = "Receptor-Interativo-TLS",
                    sources = listOf(InMemoryFileSource("pacote-validado.bin", payload)),
                )
            }

            withTimeout(5_000) {
                while (approvalVm.uiState.value.activePrompt == null) delay(15)
            }
            val prompt = assertNotNull(approvalVm.uiState.value.activePrompt)
            assertEquals(expectedLocalSuffix, prompt.localTlsFingerprintSuffix)
            assertEquals(expectedSenderSuffix, prompt.senderTlsFingerprintSuffix)

            approvalVm.acceptActive()
            val outcome = withTimeout(10_000) { sendJob.await() }
            assertTrue(outcome is SendFilesOutcome.Completed, "Resultado inesperado no envio TLS: $outcome")
            assertContentEquals(payload, Files.readAllBytes(receiverDownloads.resolve("pacote-validado.bin")))

            // Garante que ao reiniciar em modo padrao HTTP em texto claro tudo continua operacional
            val plainPort = receiverRuntime.startNode(
                alias = "Receptor-HTTP-Padrao",
                port = 0,
            ).getOrThrow()
            assertFalse(receiverRuntime.tlsEnabled.value)
            assertNull(receiverRuntime.localTlsFingerprint.value)

            val plainJob = async {
                transferCoordinator.sendFiles(
                    endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = plainPort, scheme = "http"),
                    peerAlias = "Receptor-HTTP-Padrao",
                    sources = listOf(InMemoryFileSource("plain.txt", "http padrao ok".encodeToByteArray())),
                )
            }
            withTimeout(5_000) {
                while (approvalVm.uiState.value.activePrompt == null) delay(15)
            }
            val plainPrompt = assertNotNull(approvalVm.uiState.value.activePrompt)
            assertNull(plainPrompt.localTlsFingerprintSuffix)
            approvalVm.acceptActive()
            val plainOutcome = withTimeout(10_000) { plainJob.await() }
            assertTrue(plainOutcome is SendFilesOutcome.Completed)
            assertEquals("http padrao ok", Files.readString(receiverDownloads.resolve("plain.txt")))
        } finally {
            transferCoordinator.close()
            httpClient.close()
            senderScope.cancel()
            receiverRuntime.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun manualPeerConnectorTofuCapturesObservedSha256AndServerCliParsesTlsFlag(): Unit = runBlocking {
        val defaultConfig = ServerCliParser.parse(emptyArray())
        assertFalse(defaultConfig.tlsEnabled)
        assertTrue(ServerCliParser.parse(arrayOf("--tls")).tlsEnabled)
        assertTrue(ServerCliParser.parse(arrayOf("--tls=true")).tlsEnabled)
        assertFalse(ServerCliParser.parse(arrayOf("--tls=false")).tlsEnabled)
        assertTrue(ServerCliParser.usage().contains("--tls"))

        val tempRoot = Files.createTempDirectory("fsap-tls-tofu-")
        val logger = InMemoryFsapLogger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Server-TOFU",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = 0,
        )
        val bundle = PlatformTlsProvider.generateSelfSignedBundle(
            deviceId = serverIdentity.deviceId,
            alias = serverIdentity.alias,
            extraIpAddresses = listOf("127.0.0.1"),
        )
        val sink = DesktopNioFileSink(tempRoot.resolve("downloads"))
        val sessionManager = ServerTransferSessionManager(
            tokenManager = TransferTokenManager(logger = logger),
            fileSink = sink,
            scope = scope,
            logger = logger,
        )
        val server = FsapHttpServer(
            config = FsapHttpServerConfig(
                identity = serverIdentity,
                host = "127.0.0.1",
                port = 0,
                tlsBundle = bundle,
            ),
            logger = logger,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = sink,
                approvalHandler = AutoAcceptTransferApprovalHandler,
            ),
        )
        val peerRegistry = PeerRegistry(logger = logger)
        val clientIdentity = DeviceIdentity(
            deviceId = PlatformUuidGenerator.nextUuidV4(),
            alias = "Client-TOFU",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            protocolVersion = FsapConstants.PROTOCOL_VERSION,
            httpPort = FsapConstants.DEFAULT_HTTP_PORT,
        )
        val connector = ManualPeerConnector(
            localIdentity = clientIdentity,
            peerRegistry = peerRegistry,
            logger = logger,
        )

        try {
            val boundPort = server.start()
            val tofuResult = connector.connectByManualIp(
                ipAddress = "127.0.0.1",
                port = boundPort,
                useTls = true,
            )
            assertTrue(tofuResult is ManualConnectResult.Connected, "TOFU manual falhou: $tofuResult")
            val discoveredSha = FsapTlsCapabilities.extractSha256Fingerprint(tofuResult.peer.identity.capabilities)
            assertEquals(bundle.certificateInfo.sha256Fingerprint, discoveredSha)
        } finally {
            connector.close()
            server.stop()
            sessionManager.close()
            scope.cancel()
            tempRoot.deleteRecursively()
        }
    }
}

