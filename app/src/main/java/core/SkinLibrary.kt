package com.backrooms.game
// SkinLibrary.kt - the player's skins: the built-in default plus external files (.png, and .brpack / .mcpack skin packs).
// Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** One selectable skin. [png] is null for the built-in default (the network then sends "default skin"). */
class SkinEntry(val id: String, val name: String, val source: String, val png: ByteArray?, val texture: SkinTexture)

object SkinLibrary {
    const val DEFAULT_ID = "default"
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024  // a pack bigger than this is ignored / refused
    private val EXT = setOf("png", "brpack", "mcpack")

    /** External folders that are scanned, best first (Android: app external files, then private files; desktop: ~/.backrooms/skins). */
    @Volatile var dirs: List<File> = emptyList()
    @Volatile var selectedId = DEFAULT_ID
    @Volatile var entries: List<SkinEntry> = listOf(defaultEntry())
        private set

    fun init(folders: List<File>) { dirs = folders; folders.forEach { runCatching { it.mkdirs() } }; reload() }

    private fun defaultEntry() = SkinEntry(DEFAULT_ID, "Default", "built-in", null, DefaultPlayerSkin.texture)

    /** Re-reads the external folders. Broken files are skipped, never fatal. */
    fun reload(): List<SkinEntry> {
        val out = ArrayList<SkinEntry>(); out.add(defaultEntry())
        val seen = HashSet<String>()
        for (d in dirs) {
            val files = runCatching { d.listFiles()?.filter { it.isFile && it.length() in 1..MAX_FILE_BYTES }?.sortedBy { it.name.lowercase() } }.getOrNull() ?: continue
            for (f in files) {
                val ext = f.extension.lowercase(); if (ext !in EXT) continue
                runCatching {
                    if (ext == "png") {
                        val bytes = f.readBytes(); val tex = SkinTexture.fromPng(bytes)
                        val id = "png:" + f.name
                        if (seen.add(id)) out.add(SkinEntry(id, f.nameWithoutExtension, f.name, bytes, tex))
                    } else for (s in SkinPack.read(f)) {
                        val id = f.name + "#" + s.texturePath
                        if (seen.add(id)) out.add(SkinEntry(id, s.name, s.pack, s.png, SkinTexture.fromPng(s.png)))
                    }
                }
            }
        }
        entries = out
        if (out.none { it.id == selectedId }) selectedId = DEFAULT_ID // the chosen file was removed
        return out
    }

    fun selected(): SkinEntry = entries.firstOrNull { it.id == selectedId } ?: entries.first()

    fun select(e: SkinEntry, net: Net? = null) { selectedId = e.id; Store.saveProfile(); applyTo(net) }

    /** Tell a multiplayer connection which skin this player uses (call after connecting and after changing the skin). */
    fun applyTo(net: Net?) { if (net != null) runCatching { net.setCustomSkin(selected().png) } }

    /** Copy an external file into the first skins folder (a picked file, a download...). Returns the new file name, or null if refused. */
    fun import(fileName: String, input: InputStream): String? {
        val dir = dirs.firstOrNull() ?: return null
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ \\-]"), "_").trim().ifEmpty { "skin" }
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext !in EXT) return null
        return runCatching {
            dir.mkdirs()
            var target = File(dir, clean); var n = 1
            while (target.exists()) target = File(dir, clean.substringBeforeLast('.') + "_" + (n++) + "." + ext)
            var total = 0L
            target.outputStream().use { o ->
                val buf = ByteArray(16384)
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    total += r; if (total > MAX_FILE_BYTES) throw java.io.IOException("file too large")
                    o.write(buf, 0, r)
                }
            }
            val ok = runCatching { if (ext == "png") SkinTexture.fromPng(target.readBytes()) else check(SkinPack.read(target).isNotEmpty()) }.isSuccess
            if (!ok) { target.delete(); null } else { reload(); target.name }
        }.getOrNull()
    }
}

/** A skin found inside a pack. */
class PackSkin(val pack: String, val name: String, val texturePath: String, val png: ByteArray)

/**
 * Reads a .brpack (this game's skin pack) exactly like a Minecraft Bedrock .mcpack: a zip with an optional manifest.json
 * (header.name = pack name) and a skins.json listing {"localization_name", "texture"} entries whose PNGs are in the zip.
 * Without skins.json, every .png at the top of the zip is a skin. Limits keep a hostile zip from using lots of memory.
 */
object SkinPack {
    private const val MAX_ENTRIES = 1024; private const val MAX_SKINS = 200; private const val MAX_PNG = 8 * 1024 * 1024

    fun read(file: File): List<PackSkin> {
        val out = ArrayList<PackSkin>()
        ZipFile(file).use { zip ->
            val names = ArrayList<String>(); val en = zip.entries()
            while (en.hasMoreElements()) { if (names.size >= MAX_ENTRIES) break; val e = en.nextElement(); if (!e.isDirectory) names.add(e.name.replace('\\', '/')) }
            fun find(suffix: String) = names.filter { it.equals(suffix, true) || it.endsWith("/$suffix", true) }.minByOrNull { it.count { c -> c == '/' } }
            fun text(name: String): String? = zip.getEntry(name)?.takeIf { it.size in 0..1_000_000 }?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            fun png(name: String): ByteArray? {
                val e = zip.getEntry(name) ?: names.firstOrNull { it.equals(name, true) }?.let { zip.getEntry(it) } ?: return null
                if (e.size !in 33..MAX_PNG) return null
                return zip.getInputStream(e).use { it.readBytes() }.takeIf { it.size in 33..MAX_PNG }
            }
            val packName = find("manifest.json")?.let { text(it) }?.let { runCatching { ((MiniJson.parse(it) as? Map<*, *>)?.get("header") as? Map<*, *>)?.get("name") as? String }.getOrNull() }
                ?: file.nameWithoutExtension
            val skinsJson = find("skins.json")
            val list = skinsJson?.let { text(it) }?.let { runCatching { (MiniJson.parse(it) as? Map<*, *>)?.get("skins") as? List<*> }.getOrNull() }
            if (list != null) {
                val base = skinsJson.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                for (s in list) {
                    if (out.size >= MAX_SKINS) break
                    val m = s as? Map<*, *> ?: continue
                    val tex = (m["texture"] as? String)?.replace('\\', '/')?.removePrefix("./") ?: continue
                    if (tex.contains("..")) continue
                    val bytes = png(base + tex) ?: continue
                    val label = (m["localization_name"] as? String)?.takeIf { it.isNotBlank() } ?: tex.substringAfterLast('/').substringBeforeLast('.')
                    runCatching { SkinTexture.fromPng(bytes) }.onSuccess { out.add(PackSkin(packName, label, base + tex, bytes)) }
                }
            } else for (n in names) {
                if (out.size >= MAX_SKINS) break
                if (n.contains('/') || !n.endsWith(".png", true)) continue
                val bytes = png(n) ?: continue
                runCatching { SkinTexture.fromPng(bytes) }.onSuccess { out.add(PackSkin(packName, n.substringBeforeLast('.'), n, bytes)) }
            }
        }
        return out
    }
}
