package com.backrooms.game

// LAN multiplayer helpers shared by Android and desktop (pure Kotlin/JVM).
import java.io.IOException
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

private const val LAN_DISCOVERY_PORT = 47821
private const val LAN_DISCOVERY_PREFIX = "BACKROOMS-LAN/1"

data class LanServerInfo(val name: String, val address: String, val port: Int) {
    val endpoint: String get() = "$address:$port"
}

/** LAN host using the same position and skin relay protocol as the dedicated desktop server. */
class LanHost(port: Int = 0, private val name: String = "Backrooms world") : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val server = ServerSocket(port)
    private val relay = SkinAwareRelay(randomWorldSeed())
    private var nextId = 1
    private val acceptThread: Thread
    private val advertiseThread: Thread
    val port: Int get() = server.localPort

    init {
        require(port in 0..65535) { "Port must be 0 (random) or between 1 and 65535" }
        acceptThread = Thread({ acceptClients() }, "backrooms-lan-server").apply { isDaemon = true; start() }
        advertiseThread = Thread({ advertise() }, "backrooms-lan-advertiser").apply { isDaemon = true; start() }
    }

    private fun acceptClients() {
        while (open.get()) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            val id = synchronized(this) { nextId++ }
            Thread({ runCatching { relay.attach(socket, id) }.onFailure { runCatching { socket.close() } } }, "backrooms-client-$id")
                .apply { isDaemon = true; start() }
        }
    }

    private fun advertise() {
        while (open.get()) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val payload = "$LAN_DISCOVERY_PREFIX\t$port\t${name.replace('\t', ' ').replace('\n', ' ')}".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), LAN_DISCOVERY_PORT))
                }
            } catch (_: Exception) { /* Discovery is best-effort; direct addresses still work. */ }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        try { server.close() } catch (_: IOException) {}
        acceptThread.interrupt(); advertiseThread.interrupt()
    }
}

fun discoverLanServers(timeoutMillis: Int = 1800): List<LanServerInfo> {
    require(timeoutMillis > 0) { "Discovery timeout must be positive" }
    val found = LinkedHashMap<String, LanServerInfo>()
    val socket = DatagramSocket(null)
    try {
        socket.reuseAddress = true; socket.broadcast = true
        socket.bind(InetSocketAddress(LAN_DISCOVERY_PORT)); socket.soTimeout = minOf(timeoutMillis, 300)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val bytes = ByteArray(1024); val packet = DatagramPacket(bytes, bytes.size)
            try {
                socket.receive(packet)
                val fields = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).split('\t', limit = 3)
                if (fields.size == 3 && fields[0] == LAN_DISCOVERY_PREFIX) {
                    val port = fields[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                    val info = LanServerInfo(fields[2], packet.address.hostAddress ?: continue, port)
                    found[info.endpoint] = info
                }
            } catch (_: java.net.SocketTimeoutException) { /* Poll until deadline. */ }
        }
    } finally { socket.close() }
    return found.values.toList()
}

/** Persistent, platform-neutral saved server list. The caller supplies its app-private data file. */
class LanServerBook(private val file: java.io.File) {
    @Synchronized fun list(): List<LanServerInfo> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val p = Properties().apply { file.inputStream().use(::load) }
            (0 until (p.getProperty("count")?.toIntOrNull() ?: 0).coerceIn(0, 1000)).mapNotNull { i ->
                val host = p.getProperty("server.$i.host") ?: return@mapNotNull null
                val port = p.getProperty("server.$i.port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                LanServerInfo(p.getProperty("server.$i.name") ?: host, host, port)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun add(server: LanServerInfo) {
        require(server.address.isNotBlank() && server.port in 1..65535)
        write((list().filterNot { it.address == server.address && it.port == server.port } + server).takeLast(100))
    }

    @Synchronized fun remove(server: LanServerInfo) = write(list().filterNot { it.address == server.address && it.port == server.port })

    private fun write(entries: List<LanServerInfo>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("count", entries.size.toString())
            entries.forEachIndexed { i, item ->
                setProperty("server.$i.name", item.name); setProperty("server.$i.host", item.address); setProperty("server.$i.port", item.port.toString())
            }
        }
        val tmp = java.io.File(file.path + ".tmp")
        tmp.outputStream().use { properties.store(it, "Backrooms multiplayer servers") }
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "Unable to save multiplayer server list" } }
    }
}

fun parseServerEndpoint(value: String): LanServerInfo? {
    val raw = value.trim(); if (raw.isEmpty()) return null
    val split = raw.lastIndexOf(':')
    val host = if (split > 0 && raw.indexOf(':') == split) raw.substring(0, split) else raw
    val port = if (host.length < raw.length) raw.substring(split + 1).toIntOrNull() ?: return null else PORT
    if (host.isBlank() || port !in 1..65535) return null
    return LanServerInfo(host, host, port)
}
