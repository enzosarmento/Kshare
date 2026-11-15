package br.edu.fileshare.security.tls

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.discovery.DiscoveredPeer
import io.ktor.client.HttpClient

/**
 * Metadados publicos do certificado TLS X.509 autoassinado local (`TASK-SEC-004`).
 */
data class FsapTlsCertificateInfo(
    val sha256Fingerprint: String,
    val subjectCommonName: String,
) {
    val formattedFingerprint: String
        get() = FsapTlsCapabilities.formatColonSeparated(sha256Fingerprint)

    /**
     * Ultimos 8 caracteres hexadecimais (maiusculos) para autenticacao visual do par no dialogo.
     */
    val shortSuffix: String
        get() = FsapTlsCapabilities.shortSuffix(sha256Fingerprint)
}

/**
 * Handle encerravel do proxy TLS loopback acoplado ao servidor FSAP quando HTTPS opcional esta ativo.
 */
interface FsapTlsServerProxy {
    val boundPort: Int
    fun close()
}

/**
 * Pacote opaco multiplataforma com identidade TLS local e contexto de chave privada da plataforma.
 */
expect class PlatformTlsBundle {
    val certificateInfo: FsapTlsCertificateInfo
}

/**
 * Fabrica multiplataforma para certificados X.509 autoassinados, cliente HTTPS com pinning
 * SHA-256 e servidor TLSv1.3/TLSv1.2 opcional (`TASK-SEC-004`).
 */
expect object PlatformTlsProvider {
    fun generateSelfSignedBundle(
        deviceId: String,
        alias: String,
        extraIpAddresses: List<String> = emptyList(),
    ): PlatformTlsBundle

    fun startTlsProxy(
        bundle: PlatformTlsBundle,
        bindHost: String,
        bindPort: Int,
        targetLoopbackHost: String = "127.0.0.1",
        targetLoopbackPort: Int,
        logger: FsapLogger? = null,
    ): FsapTlsServerProxy

    fun createTlsHttpClient(
        expectedFingerprintSha256: String? = null,
        allowUnpinnedSelfSigned: Boolean = false,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        requestTimeoutMs: Long,
        onPeerFingerprintObserved: ((String) -> Unit)? = null,
    ): HttpClient
}

/**
 * Helpers puros de sinalizacao de capacidade TLS (`capabilities`) e pinning SHA-256 em FSAP/1.0.
 */
object FsapTlsCapabilities {
    const val CAPABILITY_TLS: String = "TLS"
    const val FINGERPRINT_CAPABILITY_PREFIX: String = "TLS_SHA256:"

    fun withTlsCapabilities(
        baseCapabilities: List<String>,
        sha256Fingerprint: String?,
    ): List<String> {
        val cleaned = baseCapabilities.filterNot { cap ->
            cap == CAPABILITY_TLS || cap.startsWith(FINGERPRINT_CAPABILITY_PREFIX, ignoreCase = true)
        }
        val normalizedHex = sha256Fingerprint?.let(::normalizeFingerprintHex) ?: return cleaned
        return cleaned + CAPABILITY_TLS + "$FINGERPRINT_CAPABILITY_PREFIX$normalizedHex"
    }

    fun supportsTls(capabilities: List<String>): Boolean =
        capabilities.any {
            it == CAPABILITY_TLS || it.startsWith(FINGERPRINT_CAPABILITY_PREFIX, ignoreCase = true)
        }

    fun extractSha256Fingerprint(capabilities: List<String>): String? {
        for (entry in capabilities) {
            if (entry.startsWith(FINGERPRINT_CAPABILITY_PREFIX, ignoreCase = true)) {
                val rawHex = entry.substring(FINGERPRINT_CAPABILITY_PREFIX.length)
                normalizeFingerprintHex(rawHex)?.let { return it }
            }
        }
        return null
    }

    fun normalizeFingerprintHex(raw: String): String? {
        val compact = buildString(64) {
            for (ch in raw.trim()) {
                if (ch == ':' || ch == '-' || ch == ' ') continue
                append(ch.lowercaseChar())
            }
        }
        if (compact.length != 64) return null
        val isHex = compact.all { it in '0'..'9' || it in 'a'..'f' }
        return if (isHex) compact else null
    }

    fun shortSuffix(rawFingerprint: String): String {
        val normalized = normalizeFingerprintHex(rawFingerprint)
            ?: rawFingerprint.replace(":", "").trim().lowercase()
        return normalized.takeLast(8).uppercase()
    }

    fun formatColonSeparated(rawFingerprint: String): String {
        val clean = (normalizeFingerprintHex(rawFingerprint) ?: rawFingerprint.replace(":", "").trim())
            .uppercase()
        return clean.chunked(2).joinToString(":")
    }

    fun endpointForPeer(
        peer: DiscoveredPeer,
        forcePlainHttp: Boolean = false,
    ): FsapPeerEndpoint {
        val capabilities = peer.identity.capabilities
        if (!forcePlainHttp && supportsTls(capabilities)) {
            val expectedSha = extractSha256Fingerprint(capabilities)
            return FsapPeerEndpoint(
                host = peer.ipAddress,
                port = peer.port,
                scheme = "https",
                expectedTlsFingerprintSha256 = expectedSha,
            )
        }
        return FsapPeerEndpoint(
            host = peer.ipAddress,
            port = peer.port,
            scheme = "http",
        )
    }
}

