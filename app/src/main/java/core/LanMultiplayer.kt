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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val LAN_DISCOVERY_PORT = 47821
private const val LAN_DISCOVERY_PREFIX = "BACKROOMS-LAN/1"

/** A LAN server advertisement. Use [address] as the host and [port] to join with [Net]. */
data class LanServerInfo(val name: String, val address: String, val port: Int) {
    val endpoint: String get() = "$address:$port"
}

/**
 * Starts a game server and broadcasts its presence to other Backrooms clients on the local network.
 * Pass port=0 to let the OS choose an available port, or provide a port in 1..65535.
 * The host is closeable so mobile clients can stop hosting when they leave the session.
 */
class LanHost(port: Int = 0, private val name: String = "Backrooms world") : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val clients = ConcurrentHashMap<Int, PrintWriter>()
    private val server = ServerSocket(port)
    private val seed = randomWorldSeed()
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
            Thread({ serveClient(socket, id) }, "backrooms-client-$id").apply { isDaemon = true; start() }
        }
    }

    private fun serveClient(socket: Socket, id: Int) {
        val writer = try { PrintWriter(socket.getOutputStream(), true) } catch (_: IOException) { socket.close(); return }
        clients[id] = writer
        writer.println("ID $id"); writer.println("SEED $seed")
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                clients.forEach { (otherId, output) -> if (otherId != id) output.println(line) }
            }
        } catch (_: IOException) {
            // Client disconnected.
        } finally {
            clients.remove(id)
            clients.values.forEach { it.println("Q $id") }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun advertise() {
        while (open.get()) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val payload = "$LAN_DISCOVERY_PREFIX\t${port}\t${name.replace('\t', ' ').replace('\n', ' ')}".toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), LAN_DISCOVERY_PORT)
                    socket.send(packet)
                }
            } catch (_: Exception) {
                // Discovery is best-effort; the game server remains usable via a manual address.
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        try { server.close() } catch (_: IOException) {}
        clients.clear()
        acceptThread.interrupt(); advertiseThread.interrupt()
    }
}

/**
 * Listens briefly for LAN advertisements. Run this on a background thread; it returns the distinct
 * worlds heard during the scan. Manual server addresses remain available if broadcast is blocked by a router.
 */
fun discoverLanServers(timeoutMillis: Int = 1800): List<LanServerInfo> {
    require(timeoutMillis > 0) { "Discovery timeout must be positive" }
    val found = LinkedHashMap<String, LanServerInfo>()
    val socket = DatagramSocket(null)
    try {
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(LAN_DISCOVERY_PORT))
        socket.soTimeout = minOf(timeoutMillis, 300)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val bytes = ByteArray(1024)
            val packet = DatagramPacket(bytes, bytes.size)
            try {
                socket.receive(packet)
                val parts = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).split('\t', limit = 3)
                if (parts.size == 3 && parts[0] == LAN_DISCOVERY_PREFIX) {
                    val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                    val info = LanServerInfo(parts[2], packet.address.hostAddress ?: continue, port)
                    found[info.endpoint] = info
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Check the deadline again.
            }
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
        val entries = (list().filterNot { it.address == server.address && it.port == server.port } + server).takeLast(100)
        file.parentFile?.mkdirs()
        val p = Properties().apply {
            setProperty("count", entries.size.toString())
            entries.forEachIndexed { i, item ->
                setProperty("server.$i.name", item.name)
                setProperty("server.$i.host", item.address)
                setProperty("server.$i.port", item.port.toString())
            }
        }
        val tmp = java.io.File(file.path + ".tmp")
        tmp.outputStream().use { p.store(it, "Backrooms multiplayer servers") }
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "Unable to save multiplayer server list" } }
    }

    @Synchronized fun remove(server: LanServerInfo) {
        val entries = list().filterNot { it.address == server.address && it.port == server.port }
        file.parentFile?.mkdirs()
        val p = Properties().apply {
            setProperty("count", entries.size.toString())
            entries.forEachIndexed { i, item ->
                setProperty("server.$i.name", item.name)
                setProperty("server.$i.host", item.address)
                setProperty("server.$i.port", item.port.toString())
            }
        }
        val tmp = java.io.File(file.path + ".tmp")
        tmp.outputStream().use { p.store(it, "Backrooms multiplayer servers") }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

/** Parse an IPv4/hostname endpoint, defaulting to the game's standard port. */
fun parseServerEndpoint(value: String): LanServerInfo? {
    val raw = value.trim()
    if (raw.isEmpty()) return null
    val split = raw.lastIndexOf(':')
    val host = if (split > 0 && raw.indexOf(':') == split) raw.substring(0, split) else raw
    val port = if (host.length < raw.length) raw.substring(split + 1).toIntOrNull() ?: return null else PORT
    if (host.isBlank() || port !in 1..65535) return null
    return LanServerInfo(host, host, port)
}
