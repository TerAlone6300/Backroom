package com.backrooms.game
// Net.kt - multiplayer server and client. Shared by Android and desktop (pure Kotlin/JVM, no AWT/Android imports).
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

// ---------- Server: relays positions and player-skin state ----------
fun server(port: Int = PORT) {
    val ss = ServerSocket(port)
    val relay = SkinAwareRelay(randomWorldSeed())
    println("Backrooms server listening on $port")
    var next = 1
    try {
        while (true) {
            val socket = ss.accept()
            val id = next++
            Thread({ runCatching { relay.attach(socket, id) }.onFailure { runCatching { socket.close() } } }, "backrooms-accept-$id")
                .apply { isDaemon = true; start() }
        }
    } finally { ss.close() }
}

// ---------- Client networking (background thread: Android-safe) ----------
class Net(private val host: String, private val port: Int = PORT) {
    val others = ConcurrentHashMap<Int, DoubleArray>() // id -> [x, y, level]
    val playerSkins: ConcurrentHashMap<Int, RemotePlayerSkin> get() = skins.players // skins of all players (own included), read by the renderer
    @Volatile var id = 0
    @Volatile var state = 0 // 0 connecting, 1 online, 2 failed/lost
    @Volatile private var seed: Int? = null
    private var seedTaken = false
    @Volatile private var out: PrintWriter? = null
    @Volatile private var sock: Socket? = null
    private val writeLock = Any()
    private val skins = SkinClientTransport(writeLock) { out } // skin exchange with the server; follows SkinLibrary changes by itself

    /** The server's world seed, once, when it has arrived (game loop thread). */
    fun takeSeed(): Int? { val s = seed; if (s == null || seedTaken) return null; seedTaken = true; return s }

    /** Skin available for the local player; null means use the shared default skin. */
    fun setCustomSkin(png: ByteArray?) = skins.setOwn(png)

    fun start() {
        Thread {
            try {
                val s = Socket(); s.connect(InetSocketAddress(host, port), 5000)
                sock = s; out = PrintWriter(s.getOutputStream(), true); state = 1
                Thread { Thread.sleep(2000); if (seed == null) seed = LEGACY_SEED }.apply { isDaemon = true; start() }
                s.getInputStream().bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.length > SkinWire.MAX_LINE_LENGTH) throw IOException("Oversize multiplayer packet")
                    val p = line.split(' ', limit = 5)
                    when (p.firstOrNull()) {
                        "ID" -> p.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { playerId ->
                            id = playerId
                            skins.onId(playerId)
                        }
                        "SEED" -> seed = p.getOrNull(1)?.toIntOrNull()
                        "P" -> if (p.size == 5) {
                            val playerId = p[1].toIntOrNull(); val x = p[2].toDoubleOrNull(); val y = p[3].toDoubleOrNull(); val lv = p[4].toIntOrNull()
                            if (playerId != null && x != null && y != null && lv != null && x.isFinite() && y.isFinite())
                                others[playerId] = others[playerId].let { prev -> // [x, y, level, id, yaw]; the protocol carries no facing, so face the direction of travel
                                    val yaw = if (prev != null && prev.size > 4 && Math.hypot(x - prev[0], y - prev[1]) > 0.02) Math.atan2(y - prev[1], x - prev[0]) else if (prev != null && prev.size > 4) prev[4] else 0.0
                                    doubleArrayOf(x, y, lv.toDouble(), playerId.toDouble(), yaw)
                                }
                        }
                        "Q" -> p.getOrNull(1)?.toIntOrNull()?.let { others.remove(it); skins.forget(it) }
                        "SKIN_DEFAULT", "SKIN_BEGIN", "SKIN_CHUNK", "SKIN_END" -> skins.receive(p)
                    }
                }
            } catch (_: Exception) {
                // Connection loss is reflected in state; callers can keep using the fallback skin.
            } finally {
                state = 2
                others.clear(); skins.clear()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendLine(line: String) { synchronized(writeLock) { out?.println(line) } }
    fun send(x: Double, y: Double, lv: Int) { if (id != 0) sendLine("P $id $x $y $lv") }
    fun close() {
        skins.close()
        try { sock?.close() } catch (_: IOException) {}
        others.clear()
    }
}
