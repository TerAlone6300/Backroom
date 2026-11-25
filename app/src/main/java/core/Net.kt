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
    val playerSkins = ConcurrentHashMap<Int, RemotePlayerSkin>()
    @Volatile var id = 0
    @Volatile var state = 0 // 0 connecting, 1 online, 2 failed/lost
    @Volatile private var seed: Int? = null
    private var seedTaken = false
    @Volatile private var customSkinPng: ByteArray? = SkinLibrary.selected().png?.copyOf()  // the skin worn when the connection is made

    // Skin change hook: every change in SkinLibrary (Dressing Room pick, new external skin...) is pushed to the server from a worker thread
    // (never from the UI thread: Android refuses network writes there). Ordered, so the last choice wins.
    private val skinSender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "backrooms-skin-send").apply { isDaemon = true } }
    private val skinHook: (SkinEntry) -> Unit = { e -> runCatching { skinSender.execute { runCatching { setCustomSkin(e.png) } } } }
    init { SkinLibrary.addChangeListener(skinHook) }
    @Volatile private var out: PrintWriter? = null
    @Volatile private var sock: Socket? = null
    private val writeLock = Any()

    /** The server's world seed, once, when it has arrived (game loop thread). */
    fun takeSeed(): Int? { val s = seed; if (s == null || seedTaken) return null; seedTaken = true; return s }

    /** Skin available for the local player; null means use the shared default skin. */
    fun setCustomSkin(png: ByteArray?) {
        if (png == null) customSkinPng = null else {
            require(png.size in 33..SkinWire.MAX_SKIN_BYTES)
            SkinTexture.fromPng(png)
            customSkinPng = png.copyOf()
        }
        val playerId = id
        if (playerId != 0) sendOwnSkin(playerId)
    }

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
                            playerSkins[playerId] = RemotePlayerSkin(isDefault = customSkinPng == null)
                            sendOwnSkin(playerId)
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
                        "Q" -> p.getOrNull(1)?.toIntOrNull()?.let { others.remove(it); playerSkins.remove(it); pendingSkins.remove(it) }
                        "SKIN_DEFAULT" -> p.getOrNull(1)?.toIntOrNull()?.let { playerSkins[it] = RemotePlayerSkin(isDefault = true); pendingSkins.remove(it) }
                        "SKIN_BEGIN" -> if (p.size == 3) {
                            val playerId = p[1].toIntOrNull(); val size = p[2].toIntOrNull()
                            if (playerId != null && size != null && playerId > 0 && size in 33..SkinWire.MAX_SKIN_BYTES)
                                pendingSkins[playerId] = IncomingSkin(size)
                        }
                        "SKIN_CHUNK" -> if (p.size == 4) {
                            val playerId = p[1].toIntOrNull(); val seq = p[2].toIntOrNull(); val bytes = SkinWire.decodeBase64(p[3])
                            val pending = playerId?.let { pendingSkins[it] }
                            if (pending != null && seq == pending.nextSequence && bytes != null && pending.data.size().toLong() + bytes.size <= pending.expectedBytes) {
                                pending.data.write(bytes); pending.nextSequence++
                            } else if (playerId != null) pendingSkins.remove(playerId)
                        }
                        "SKIN_END" -> p.getOrNull(1)?.toIntOrNull()?.let { playerId ->
                            val pending = pendingSkins.remove(playerId)
                            if (pending != null && pending.data.size() == pending.expectedBytes) {
                                val png = pending.data.toByteArray()
                                val texture = runCatching { SkinTexture.fromPng(png) }.getOrNull()
                                if (texture != null) playerSkins[playerId] = RemotePlayerSkin(isDefault = false, texture = texture, png = png)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Connection loss is reflected in state; callers can keep using the fallback skin.
            } finally {
                state = 2
                others.clear(); playerSkins.clear(); pendingSkins.clear()
            }
        }.apply { isDaemon = true; start() }
    }

    private data class IncomingSkin(val expectedBytes: Int, val data: ByteArrayOutputStream = ByteArrayOutputStream(), var nextSequence: Int = 0)
    private val pendingSkins = ConcurrentHashMap<Int, IncomingSkin>()

    private fun sendOwnSkin(playerId: Int) {
        val png = customSkinPng
        if (png == null) {
            playerSkins[playerId] = RemotePlayerSkin(isDefault = true)
            sendLine("SKIN_DEFAULT $playerId")
            return
        }
        val texture = runCatching { SkinTexture.fromPng(png) }.getOrNull() ?: return
        playerSkins[playerId] = RemotePlayerSkin(isDefault = false, texture = texture, png = png)
        synchronized(writeLock) {
            val writer = out ?: return
            writer.println("SKIN_BEGIN $playerId ${png.size}")
            var offset = 0; var sequence = 0
            while (offset < png.size) {
                val size = minOf(SkinWire.CHUNK_BYTES, png.size - offset)
                writer.println("SKIN_CHUNK $playerId ${sequence++} ${SkinWire.encodeBase64(png, offset, size)}")
                offset += size
            }
            writer.println("SKIN_END $playerId")
        }
    }

    private fun sendLine(line: String) { synchronized(writeLock) { out?.println(line) } }
    fun send(x: Double, y: Double, lv: Int) { if (id != 0) sendLine("P $id $x $y $lv") }
    fun close() {
        SkinLibrary.removeChangeListener(skinHook); skinSender.shutdown()
        try { sock?.close() } catch (_: IOException) {}
        others.clear(); playerSkins.clear(); pendingSkins.clear()
    }
}
