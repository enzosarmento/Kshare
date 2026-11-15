package br.edu.fileshare.security.tls

import br.edu.fileshare.core.concurrency.PlatformLock
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.protocol.FsapConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

actual class PlatformTlsBundle(
    val keyStore: KeyStore,
    val keyPassword: String,
    val sslContext: SSLContext,
    actual val certificateInfo: FsapTlsCertificateInfo,
)

actual object PlatformTlsProvider {

    private const val KEY_ALIAS = "fsap-node"
    private val PREFERRED_TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    actual fun generateSelfSignedBundle(
        deviceId: String,
        alias: String,
        extraIpAddresses: List<String>,
    ): PlatformTlsBundle {
        val safeCn = sanitizeX500Value(deviceId.ifBlank { "fileshare-node" }).take(48)
        val safeAliasDomain = alias.trim()
            .replace(Regex("[^a-zA-Z0-9.-]"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "fileshare-local" }
        val keyPassword = "fsap-ephemeral-$safeCn"

        val sanIps = collectSubjectAltIpAddresses(extraIpAddresses)
        val sanDomains = listOf("localhost", safeAliasDomain).distinct()

        val keyStore = buildKeyStore {
            certificate(KEY_ALIAS) {
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 2048
                daysValid = 365L
                keyType = KeyType.Server
                password = keyPassword
                subject = X500Principal("CN=$safeCn, O=FileShare, OU=FSAP-1.0")
                domains = sanDomains
                ipAddresses = sanIps
            }
        }

        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val sha256Hex = sha256Hex(cert.encoded)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, keyPassword.toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }

        return PlatformTlsBundle(
            keyStore = keyStore,
            keyPassword = keyPassword,
            sslContext = sslContext,
            certificateInfo = FsapTlsCertificateInfo(
                sha256Fingerprint = sha256Hex,
                subjectCommonName = safeCn,
            ),
        )
    }

    actual fun startTlsProxy(
        bundle: PlatformTlsBundle,
        bindHost: String,
        bindPort: Int,
        targetLoopbackHost: String,
        targetLoopbackPort: Int,
        logger: FsapLogger?,
    ): FsapTlsServerProxy {
        val serverSocket = bundle.sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        serverSocket.reuseAddress = true
        val supported = serverSocket.supportedProtocols.toSet()
        val chosenProtocols = PREFERRED_TLS_PROTOCOLS.filter { it in supported }
        if (chosenProtocols.isNotEmpty()) {
            serverSocket.enabledProtocols = chosenProtocols.toTypedArray()
        }
        serverSocket.bind(InetSocketAddress(bindHost, bindPort))

        return JvmLoopbackTlsProxy(
            serverSocket = serverSocket,
            targetLoopbackHost = targetLoopbackHost,
            targetLoopbackPort = targetLoopbackPort,
            fingerprintSuffix = bundle.certificateInfo.shortSuffix,
            logger = logger,
        ).also { it.start() }
    }

    actual fun createTlsHttpClient(
        expectedFingerprintSha256: String?,
        allowUnpinnedSelfSigned: Boolean,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        requestTimeoutMs: Long,
        onPeerFingerprintObserved: ((String) -> Unit)?,
    ): HttpClient {
        val pinningTrustManager = createPinningTrustManager(
            expectedFingerprintSha256 = expectedFingerprintSha256,
            allowUnpinnedSelfSigned = allowUnpinnedSelfSigned,
            onPeerFingerprintObserved = onPeerFingerprintObserved,
        )
        return HttpClient(CIO) {
            engine {
                https {
                    trustManager = pinningTrustManager
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = socketTimeoutMs
                requestTimeoutMillis = requestTimeoutMs
            }
        }
    }

    internal fun createPinningTrustManager(
        expectedFingerprintSha256: String? = null,
        allowUnpinnedSelfSigned: Boolean = false,
        onPeerFingerprintObserved: ((String) -> Unit)? = null,
    ): X509TrustManager {
        val expectedNormalized = expectedFingerprintSha256?.let { raw ->
            FsapTlsCapabilities.normalizeFingerprintHex(raw)
                ?: throw IllegalArgumentException("Fingerprint SHA-256 esperado invalido: '$raw'.")
        }
        val defaultTrustManager = lazy { loadDefaultSystemTrustManager() }

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Servidor TLS nao apresentou certificado X.509.")
                val actualHex = sha256Hex(leaf.encoded)
                onPeerFingerprintObserved?.invoke(actualHex)

                if (expectedNormalized != null) {
                    if (!actualHex.equals(expectedNormalized, ignoreCase = true)) {
                        throw CertificateException(
                            "Divergencia de fingerprint SHA-256 TLS: " +
                                "esperado=...${FsapTlsCapabilities.shortSuffix(expectedNormalized)}, " +
                                "recebido=...${FsapTlsCapabilities.shortSuffix(actualHex)}.",
                        )
                    }
                    return
                }

                if (allowUnpinnedSelfSigned) {
                    return
                }

                defaultTrustManager.value.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private fun loadDefaultSystemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun collectSubjectAltIpAddresses(extraIps: List<String>): List<InetAddress> {
        val addresses = linkedMapOf<String, InetAddress>()
        runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()?.let {
            addresses[it.hostAddress] = it
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (iface in Collections.list(interfaces)) {
                    if (!iface.isUp) continue
                    for (addr in Collections.list(iface.inetAddresses)) {
                        if (addr is Inet4Address) {
                            addresses[addr.hostAddress] = addr
                        }
                    }
                }
            }
        }
        for (rawIp in extraIps) {
            runCatching { InetAddress.getByName(rawIp.trim()) }.getOrNull()?.let {
                addresses[it.hostAddress] = it
            }
        }
        return addresses.values.toList()
    }

    private fun sanitizeX500Value(raw: String): String =
        raw.replace(Regex("[,=+<>#;\"\\\\]"), "_").trim()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}

private class JvmLoopbackTlsProxy(
    private val serverSocket: SSLServerSocket,
    private val targetLoopbackHost: String,
    private val targetLoopbackPort: Int,
    private val fingerprintSuffix: String,
    private val logger: FsapLogger?,
) : FsapTlsServerProxy {

    override val boundPort: Int
        get() = serverSocket.localPort

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketsLock = PlatformLock()
    private val activeSockets = mutableSetOf<Socket>()

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var acceptJob: Job? = null

    fun start() {
        acceptJob = scope.launch {
            while (isActive && !closed) {
                val tlsClient = try {
                    serverSocket.accept() as SSLSocket
                } catch (ex: Throwable) {
                    if (!closed) {
                        logger?.warn(
                            component = "JvmLoopbackTlsProxy",
                            message = "Erro no accept TLS (porta $boundPort): ${ex.message}",
                            throwable = ex,
                        )
                    }
                    break
                }
                registerSocket(tlsClient)
                launch {
                    handleConnection(tlsClient)
                }
            }
        }
        logger?.info(
            component = "JvmLoopbackTlsProxy",
            message = "Transporte opcional HTTPS/TLS ativo na porta $boundPort (fingerprint ...$fingerprintSuffix).",
        )
    }

    private suspend fun handleConnection(tlsClient: SSLSocket) {
        var loopback: Socket? = null
        try {
            tlsClient.soTimeout = 0
            tlsClient.startHandshake()

            loopback = Socket().apply {
                connect(
                    InetSocketAddress(targetLoopbackHost, targetLoopbackPort),
                    FsapConstants.T_TCP_CONNECT_TIMEOUT_MS.toInt(),
                )
            }
            registerSocket(loopback)

            val tlsIn = tlsClient.inputStream
            val tlsOut = tlsClient.outputStream
            val plainIn = loopback.inputStream
            val plainOut = loopback.outputStream

            val up = scope.launch {
                pumpStream(tlsIn, plainOut)
                runCatching { loopback.shutdownOutput() }
            }
            val down = scope.launch {
                pumpStream(plainIn, tlsOut)
                runCatching { tlsClient.shutdownOutput() }
            }
            listOf(up, down).joinAll()
        } catch (ex: Throwable) {
            if (!closed) {
                logger?.debug(
                    component = "JvmLoopbackTlsProxy",
                    message = "Conexao TLS encerrada/falhou na porta $boundPort: ${ex.message}",
                    context = LogContext(),
                )
            }
        } finally {
            unregisterAndClose(loopback)
            unregisterAndClose(tlsClient)
        }
    }

    private fun pumpStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(FsapConstants.CHUNK_SIZE_BYTES)
        while (!closed) {
            val read = try {
                input.read(buffer)
            } catch (_: Throwable) {
                -1
            }
            if (read <= 0) break
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: Throwable) {
                break
            }
        }
    }

    private fun registerSocket(socket: Socket) {
        socketsLock.withLock {
            if (!closed) {
                activeSockets += socket
            } else {
                runCatching { socket.close() }
            }
        }
    }

    private fun unregisterAndClose(socket: Socket?) {
        if (socket == null) return
        socketsLock.withLock {
            activeSockets -= socket
        }
        runCatching { socket.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { serverSocket.close() }
        val toClose = socketsLock.withLock {
            val copy = activeSockets.toList()
            activeSockets.clear()
            copy
        }
        for (sock in toClose) {
            runCatching { sock.close() }
        }
        acceptJob?.cancel()
        scope.cancel()
    }
}

