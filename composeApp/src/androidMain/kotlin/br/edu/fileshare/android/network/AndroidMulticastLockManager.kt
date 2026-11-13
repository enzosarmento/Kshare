package br.edu.fileshare.android.network

import android.content.Context
import android.net.wifi.WifiManager
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.network.discovery.DiscoveryDefaults
import br.edu.fileshare.network.discovery.JvmMulticastTransport
import br.edu.fileshare.network.discovery.JvmNetworkInterfaceProvider
import br.edu.fileshare.network.discovery.LanInterfaceInfo
import br.edu.fileshare.network.discovery.MulticastTransport
import br.edu.fileshare.network.discovery.NetworkInterfaceProvider
import br.edu.fileshare.network.discovery.UdpDatagram
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Abstracao testavel de um `WifiManager.MulticastLock` no Android (`TASK-AND-001`).
 */
interface MulticastLockHandle {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/**
 * Fabrica responsavel por criar [MulticastLockHandle] com `setReferenceCounted(false)`.
 */
fun interface MulticastLockFactory {
    fun createLock(tag: String): MulticastLockHandle?
}

/**
 * Implementacao Android real de [MulticastLockHandle] encapsulando [WifiManager.MulticastLock].
 */
class AndroidWifiMulticastLockHandle(
    private val wifiLock: WifiManager.MulticastLock,
) : MulticastLockHandle {

    init {
        wifiLock.setReferenceCounted(false)
    }

    override val isHeld: Boolean
        get() = wifiLock.isHeld

    override fun acquire() {
        if (!wifiLock.isHeld) {
            wifiLock.acquire()
        }
    }

    override fun release() {
        if (wifiLock.isHeld) {
            wifiLock.release()
        }
    }
}

/**
 * Fabrica padrao baseada no servico `Context.WIFI_SERVICE` do Android.
 */
class AndroidWifiMulticastLockFactory(
    context: Context,
) : MulticastLockFactory {

    private val appContext: Context = context.applicationContext

    override fun createLock(tag: String): MulticastLockHandle? {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val multicastLock = wifiManager.createMulticastLock(tag)
        multicastLock.setReferenceCounted(false)
        return AndroidWifiMulticastLockHandle(multicastLock)
    }
}

/**
 * Gerenciador seguro do ciclo de vida de `WifiManager.MulticastLock` no Android (`TASK-AND-001`).
 *
 * Garante aquisicao idempotente com `setReferenceCounted(false)` e liberacao garantida
 * mesmo diante de excecoes ou chamadas duplicadas.
 */
class AndroidMulticastLockManager(
    private val lockFactory: MulticastLockFactory,
    private val lockTag: String = DEFAULT_LOCK_TAG,
    private val logger: FsapLogger? = null,
) {
    constructor(
        context: Context,
        lockTag: String = DEFAULT_LOCK_TAG,
        logger: FsapLogger? = null,
    ) : this(
        lockFactory = AndroidWifiMulticastLockFactory(context),
        lockTag = lockTag,
        logger = logger,
    )

    private val guard = Any()

    @Volatile
    private var activeHandle: MulticastLockHandle? = null

    val isHeld: Boolean
        get() = synchronized(guard) {
            activeHandle?.isHeld == true
        }

    /**
     * Adquire o `MulticastLock` se ainda nao estiver ativo.
     * Retorna `true` se o lock estiver retido ao final da chamada, ou `false` caso
     * `WifiManager` nao esteja disponivel ou falhe por permissao.
     */
    fun acquire(): Boolean = synchronized(guard) {
        val existing = activeHandle
        if (existing != null && existing.isHeld) {
            return@synchronized true
        }

        val handle = runCatching { lockFactory.createLock(lockTag) }
            .onFailure { err ->
                logger?.warn(
                    component = COMPONENT,
                    message = "Falha ao criar MulticastLock ($lockTag): ${err.message}",
                    throwable = err,
                )
            }
            .getOrNull()
            ?: return@synchronized false

        return@synchronized try {
            handle.acquire()
            val held = handle.isHeld
            if (held) {
                activeHandle = handle
                logger?.info(
                    component = COMPONENT,
                    message = "MulticastLock '$lockTag' adquirido com sucesso.",
                )
            } else {
                activeHandle = null
                logger?.warn(
                    component = COMPONENT,
                    message = "MulticastLock '$lockTag' retornou isHeld=false apos acquire().",
                )
            }
            held
        } catch (err: Throwable) {
            runCatching {
                if (handle.isHeld) handle.release()
            }
            activeHandle = null
            logger?.warn(
                component = COMPONENT,
                message = "Erro ao adquirir MulticastLock '$lockTag': ${err.message}",
                throwable = err,
            )
            false
        }
    }

    /**
     * Libera garantidamente o `MulticastLock` ativo, deixando [isHeld] == false mesmo em erro.
     */
    fun release() = synchronized(guard) {
        val handle = activeHandle ?: return@synchronized
        try {
            handle.release()
            logger?.info(
                component = COMPONENT,
                message = "MulticastLock '$lockTag' liberado.",
            )
        } catch (err: Throwable) {
            logger?.warn(
                component = COMPONENT,
                message = "Erro ao liberar MulticastLock '$lockTag': ${err.message}",
                throwable = err,
            )
        } finally {
            activeHandle = null
        }
    }

    inline fun <T> withLock(block: () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }

    companion object {
        const val DEFAULT_LOCK_TAG: String = "fileshare_fsap_multicast_lock"
        private const val COMPONENT: String = "AndroidMulticastLockManager"
    }
}

/**
 * Filtro e ordenacao de interfaces LAN especifica do Android (`TASK-AND-001`).
 * Exclui interfaces celulares/dummy (`rmnet`, `ccmni`, `pdp`, `dummy`) e prioriza `wlan*`.
 */
object AndroidLanInterfaceHelper {
    val ANDROID_EXCLUDED_PREFIXES: List<String> = listOf("rmnet", "ccmni", "pdp", "dummy")

    fun isAndroidEligibleInterface(name: String, displayName: String = name): Boolean {
        val lowerName = name.trim().lowercase()
        val lowerDisplay = displayName.trim().lowercase()
        return ANDROID_EXCLUDED_PREFIXES.none { prefix ->
            lowerName.startsWith(prefix) || lowerDisplay.startsWith(prefix)
        }
    }

    fun prioritizeWifiInterfaces(interfaces: List<LanInterfaceInfo>): List<LanInterfaceInfo> =
        interfaces
            .filter { isAndroidEligibleInterface(it.name, it.displayName) }
            .sortedWith(
                compareBy<LanInterfaceInfo>(
                    { !it.name.lowercase().startsWith("wlan") },
                    { it.name.lowercase() },
                ),
            )
}

/**
 * Provedor de interfaces de rede adaptado para dispositivos Android, priorizando `wlan0`.
 */
class AndroidNetworkInterfaceProvider(
    private val baseProvider: NetworkInterfaceProvider = JvmNetworkInterfaceProvider(
        extraExcludedPrefixes = AndroidLanInterfaceHelper.ANDROID_EXCLUDED_PREFIXES,
        prioritizedPrefixes = listOf("wlan"),
    ),
) : NetworkInterfaceProvider {

    override fun listLanInterfaces(): List<LanInterfaceInfo> =
        AndroidLanInterfaceHelper.prioritizeWifiInterfaces(baseProvider.listLanInterfaces())
}

/**
 * Adaptador Android de [MulticastTransport] que acopla a aquisicao e liberacao garantida
 * do [AndroidMulticastLockManager] ao ciclo de vida `open()` / `close()` do socket UDP (`TASK-AND-001`).
 */
class AndroidMulticastTransport(
    private val lockManager: AndroidMulticastLockManager,
    private val delegate: MulticastTransport,
    private val logger: FsapLogger? = null,
) : MulticastTransport {

    constructor(
        context: Context,
        groupIp: String = DiscoveryDefaults.MULTICAST_GROUP,
        port: Int = DiscoveryDefaults.UDP_PORT,
        ttl: Int = DiscoveryDefaults.MULTICAST_TTL,
        maxDatagramBytes: Int = DiscoveryDefaults.MAX_DATAGRAM_BYTES,
        enableLoopback: Boolean = true,
        allowLoopbackFallback: Boolean = false,
        logger: FsapLogger? = null,
    ) : this(
        lockManager = AndroidMulticastLockManager(context = context, logger = logger),
        delegate = JvmMulticastTransport(
            groupIp = groupIp,
            port = port,
            ttl = ttl,
            maxDatagramBytes = maxDatagramBytes,
            interfaceProvider = JvmNetworkInterfaceProvider(
                extraExcludedPrefixes = AndroidLanInterfaceHelper.ANDROID_EXCLUDED_PREFIXES,
                prioritizedPrefixes = listOf("wlan"),
            ),
            enableLoopback = enableLoopback,
            allowLoopbackFallback = allowLoopbackFallback,
            logger = logger,
        ),
        logger = logger,
    )

    private val lifecycleMutex = Mutex()

    override val isOpen: Boolean
        get() = delegate.isOpen

    override val boundPort: Int
        get() = delegate.boundPort

    override suspend fun open() {
        lifecycleMutex.withLock {
            if (delegate.isOpen) return@withLock

            val acquired = lockManager.acquire()
            if (!acquired) {
                logger?.warn(
                    component = COMPONENT,
                    message = "Prosseguindo abertura do transporte UDP sem MulticastLock retido.",
                )
            }
            try {
                delegate.open()
            } catch (ex: Throwable) {
                lockManager.release()
                throw ex
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        delegate.send(bytes)
    }

    override suspend fun receive(): UdpDatagram? = delegate.receive()

    override fun close() {
        try {
            delegate.close()
        } finally {
            lockManager.release()
        }
    }

    private companion object {
        const val COMPONENT = "AndroidMulticastTransport"
    }
}
