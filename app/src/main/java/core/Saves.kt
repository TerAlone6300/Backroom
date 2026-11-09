package com.backrooms.game
// Saves.kt - unlocked levels, save games and the profile file. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.io.*
import kotlin.math.*

object Progress {
    private val ids: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().also { it.add(0) } // level numbers discovered (persisted by Store)
    fun has(lv: Int) = lv in ids
    fun all(): List<Int> = ids.sorted()
    fun unlock(lv: Int) { if (levelExists(lv) && ids.add(lv)) Store.saveProfile() }
    /** Used by Store when loading the profile: no write back. */
    internal fun load(lv: Int) { if (levelExists(lv)) ids.add(lv) }
    internal fun reset() { ids.clear(); ids.add(0) }
}

/** Before level ids became the Backrooms level numbers, files stored positions in this list (0..12); files without "ids=2" are converted with it. */
private val LEGACY_LEVELS = intArrayOf(0, 1, 2, 3, 4, 5, 37, 6, 75, 139, 182, 201, 11)
private fun legacyId(old: Int) = LEGACY_LEVELS.getOrElse(old) { 0 }

class SaveData(val id: String, val stamp: Long, val level: Int, val x: Double, val y: Double, val a: Double,
               val pitch: Double, val stamina: Double, val playSec: Double,
               val hunger: Double = 1.0, val rations: Int = 0, val water: Int = 0, val lobbyT: Double = 0.0) {
    fun info(): String {
        val m = (playSec / 60).toInt()
        val t = if (m < 1) "<1 min" else if (m < 60) "$m min" else "${m / 60}h ${"%02d".format(m % 60)}m"
        return "${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))}  ·  $t played"
    }
}

/** Plain .properties files in a folder each platform provides: profile.properties (FOV + unlocked levels) and saves/<id>.properties. */
object Store {
    private var dir: File? = null
    private fun load(f: File) = java.util.Properties().also { p -> f.inputStream().use { p.load(it) } }
    private fun put(f: File, p: java.util.Properties) {
        val tmp = File(f.path + ".tmp"); tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun init(d: File) {
        dir = d; File(d, "saves").mkdirs()
        runCatching {
            val f = File(d, "profile.properties")
            if (f.exists()) {
                val p = load(f)
                p.getProperty("fov")?.toIntOrNull()?.let { Settings.set(it) }
                p.getProperty("fog")?.toIntOrNull()?.let { Settings.putFog(it) }
                p.getProperty("diff")?.toIntOrNull()?.let { Settings.putDiff(it) }
                val legacy = p.getProperty("ids") != "2"
                p.getProperty("found")?.split(",")?.forEach { s -> s.trim().toIntOrNull()?.let { Progress.load(if (legacy) legacyId(it) else it) } }
            }
        }
    }

    fun saveProfile() {
        val d = dir ?: return
        runCatching {
            put(File(d, "profile.properties"), java.util.Properties().apply {
                setProperty("fov", Settings.fov.toString()); setProperty("fog", Settings.fog.toString()); setProperty("diff", Settings.diff.toString())
                setProperty("ids", "2"); setProperty("found", Progress.all().joinToString(","))
            })
        }
    }

    fun list(): List<SaveData> {
        val d = dir ?: return emptyList()
        return (File(d, "saves").listFiles { f -> f.name.endsWith(".properties") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val p = load(f)
                SaveData(f.name.removeSuffix(".properties"), p.getProperty("stamp").toLong(), p.getProperty("level").toInt().let { if (p.getProperty("ids") == "2") it else legacyId(it) },
                    p.getProperty("x").toDouble(), p.getProperty("y").toDouble(), p.getProperty("a").toDouble(),
                    p.getProperty("pitch").toDouble(), p.getProperty("stamina").toDouble(), p.getProperty("playSec").toDouble(),
                    p.getProperty("hunger")?.toDouble() ?: 1.0, p.getProperty("rations")?.toInt() ?: 0, p.getProperty("water")?.toInt() ?: 0, p.getProperty("lobbyT")?.toDouble() ?: 0.0)
            }.getOrNull()
        }.sortedByDescending { it.stamp }
    }

    fun write(s: SaveData) {
        val d = dir ?: return
        runCatching {
            put(File(File(d, "saves"), "${s.id}.properties"), java.util.Properties().apply {
                setProperty("ids", "2"); setProperty("stamp", s.stamp.toString()); setProperty("level", s.level.toString())
                setProperty("x", s.x.toString()); setProperty("y", s.y.toString()); setProperty("a", s.a.toString())
                setProperty("pitch", s.pitch.toString()); setProperty("stamina", s.stamina.toString()); setProperty("playSec", s.playSec.toString())
                setProperty("hunger", s.hunger.toString()); setProperty("rations", s.rations.toString()); setProperty("water", s.water.toString()); setProperty("lobbyT", s.lobbyT.toString())
            })
        }
    }

    fun delete(id: String) { val d = dir ?: return; File(File(d, "saves"), "$id.properties").delete() }
}
