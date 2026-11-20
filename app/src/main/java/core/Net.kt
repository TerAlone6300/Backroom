package com.backrooms.game
// Net.kt - multiplayer server and client. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

// ---------- Server: relays positions between all clients ----------
fun server(port: Int = PORT) {
    val clients = ConcurrentHashMap<Int, PrintWriter>()
    val seed = randomWorldSeed() // the world of this server: every client plays in it
    var next = 1
    val ss = ServerSocket(port)
    println("Backrooms server listening on $port  ·  world seed $seed")
    while (true) {
        val s = ss.accept(); val id = next++
        Thread {
            val out = PrintWriter(s.getOutputStream(), true)
            clients[id] = out; out.println("ID $id"); out.println("SEED $seed")
            try {
                s.getInputStream().bufferedReader().forEachLine { l ->
                    clients.forEach { (i, w) -> if (i != id) w.println(l) }
                }
            } catch (_: IOException) {}
            clients.remove(id)
            clients.values.forEach { it.println("Q $id") }
        }.start()
    }
}

// ---------- Client networking (background thread: Android-safe) ----------
class Net(private val host: String, private val port: Int = PORT) {
    val others = ConcurrentHashMap<Int, DoubleArray>() // id -> [x, y, level]
    @Volatile var id = 0
    @Volatile var state = 0 // 0 connecting, 1 online, 2 failed/lost
    @Volatile private var seed: Int? = null
    private var seedTaken = false
    /** The server's world seed, once, when it has arrived (game loop thread). A server from before worlds were random never sends one and means the original world. */
    fun takeSeed(): Int? { val s = seed; if (s == null || seedTaken) return null; seedTaken = true; return s }
    @Volatile private var out: PrintWriter? = null
    @Volatile private var sock: Socket? = null

    fun start() {
        Thread {
            try {
                val s = Socket(); s.connect(InetSocketAddress(host, port), 5000)
                sock = s; out = PrintWriter(s.getOutputStream(), true); state = 1
                Thread { Thread.sleep(2000); if (seed == null) seed = LEGACY_SEED }.apply { isDaemon = true; start() } // an old server sends no SEED
                s.getInputStream().bufferedReader().forEachLine { l ->
                    val p = l.split(" ")
                    when (p[0]) {
                        "ID" -> id = p[1].toInt()
                        "SEED" -> seed = p[1].toInt()
                        "P" -> others[p[1].toInt()] = doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
                        "Q" -> others.remove(p[1].toInt())
                    }
                }
            } catch (_: Exception) {}
            state = 2
        }.apply { isDaemon = true; start() }
    }

    fun send(x: Double, y: Double, lv: Int) { if (id != 0) out?.println("P $id $x $y $lv") }
    fun close() { try { sock?.close() } catch (_: IOException) {} }
}
