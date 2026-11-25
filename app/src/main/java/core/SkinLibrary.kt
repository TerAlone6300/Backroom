package com.backrooms.game
// SkinLibrary.kt - the player's skins.
//   * the built-in default skin;
//   * skin packs (.brpack, or Bedrock .mcpack): SAVED in the skins folder, one pack can hold many named skins;
//   * ONE external skin (a loose .png): NOT saved - it lives in memory in a single slot and a newer external skin replaces it.
// Every change of the worn skin is announced through [addChangeListener]; Net uses it to send the skin to the server.
// Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** One selectable skin. [png] is null for the built-in default (the network then sends "default skin"). [pack] is the skin pack name, or null. */
class SkinEntry(val id: String, val name: String, val pack: String?, val source: String, val png: ByteArray?, val texture: SkinTexture)

/** One line of the Dressing Room list: a pack title ([header] + number of skins) or a skin ([entry]). */
class SkinRow(val header: String?, val count: Int, val entry: SkinEntry?)

/** What an import did: kind is "pack" (saved) or "external" (the single unsaved slot). */
class ImportResult(val kind: String, val label: String, val skins: Int)

object SkinLibrary {
    const val DEFAULT_ID = "default"
    const val EXTERNAL_ID = "external"
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024  // a pack bigger than this is ignored / refused
    private val PACK_EXT = setOf("brpack", "mcpack")

    /** Folders that hold the saved skin packs, best first (Android: app external files, then private files; desktop: ~/.backrooms/skins). */
    @Volatile var dirs: List<File> = emptyList()
    @Volatile var selectedId = DEFAULT_ID
    @Volatile var entries: List<SkinEntry> = listOf(defaultEntry())
        private set
    @Volatile private var external: SkinEntry? = null   // the one unsaved external skin
    private val listeners = CopyOnWriteArrayList<(SkinEntry) -> Unit>()

    fun init(folders: List<File>) { dirs = folders; folders.forEach { runCatching { it.mkdirs() } }; reload() }

    private fun defaultEntry() = SkinEntry(DEFAULT_ID, "Default", null, "built-in", null, DefaultPlayerSkin.texture)

    /** Called (on the thread that changed the skin) every time the worn skin changes. */
    fun addChangeListener(l: (SkinEntry) -> Unit) { listeners.addIfAbsent(l) }
    fun removeChangeListener(l: (SkinEntry) -> Unit) { listeners.remove(l) }
    private fun fireChanged() { val e = selected(); for (l in listeners) runCatching { l(e) } }

    /** Re-reads the saved packs. Broken files are skipped, never fatal. */
    fun reload(): List<SkinEntry> {
        val out = ArrayList<SkinEntry>(); out.add(defaultEntry())
        external?.let { out.add(it) }
        val locale = if (I18n.isVi) "vi_VN" else "en_US"
        val seen = HashSet<String>()
        for (d in dirs) {
            val files = runCatching { d.listFiles()?.filter { it.isFile && it.extension.lowercase() in PACK_EXT && it.length() in 1..MAX_FILE_BYTES }?.sortedBy { it.name.lowercase() } }.getOrNull() ?: continue
            for (f in files) runCatching {
                for (s in SkinPack.read(f, locale)) {
                    val id = f.name + "#" + s.texturePath
                    if (seen.add(id)) out.add(SkinEntry(id, s.name, s.pack, f.name, s.png, SkinTexture.fromPng(s.png)))
                }
            }
        }
        entries = out
        if (out.none { it.id == selectedId }) { selectedId = DEFAULT_ID; fireChanged() } // the chosen pack was removed
        return out
    }

    fun selected(): SkinEntry = entries.firstOrNull { it.id == selectedId } ?: entries.first()

    /** The Dressing Room list: default, the external skin, then each pack under its own title. */
    fun rows(): List<SkinRow> {
        val rows = ArrayList<SkinRow>()
        for (e in entries) {
            val p = e.pack
            if (p != null && rows.none { it.header == p }) rows.add(SkinRow(p, entries.count { it.pack == p }, null))
            rows.add(SkinRow(null, 0, e))
        }
        return rows
    }

    fun select(e: SkinEntry) { selectedId = e.id; Store.saveProfile(); fireChanged() }

    /** Puts a loose skin picture into the single external slot (the previous external skin disappears) and wears it. Nothing is saved. */
    fun setExternal(png: ByteArray, label: String): SkinEntry {
        val tex = SkinTexture.fromPng(png)
        val e = SkinEntry(EXTERNAL_ID, label.ifBlank { "External" }, null, "external · not saved", png.copyOf(), tex)
        external = e
        entries = entries.filter { it.id != EXTERNAL_ID }.let { l -> listOf(l.first(), e) + l.drop(1) }
        selectedId = EXTERNAL_ID; fireChanged()
        return e
    }

    /**
     * Imports a picked file. .brpack / .mcpack: copied into the skins folder (saved) and added to the list.
     * .png: becomes the external skin (not saved, replaces the previous external skin). Returns null if the file is refused.
     */
    fun import(fileName: String, input: InputStream): ImportResult? {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ \\-]"), "_").trim().ifEmpty { "skin" }
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext == "png") return runCatching {
            val bytes = input.readNBytes(SkinWire.MAX_SKIN_BYTES + 1)
            if (bytes.size > SkinWire.MAX_SKIN_BYTES) return null
            setExternal(bytes, clean.substringBeforeLast('.')); ImportResult("external", clean.substringBeforeLast('.'), 1)
        }.getOrNull()
        if (ext !in PACK_EXT) return null
        val dir = dirs.firstOrNull() ?: return null
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
            val skins = runCatching { SkinPack.read(target, if (I18n.isVi) "vi_VN" else "en_US") }.getOrNull()
            if (skins.isNullOrEmpty()) { target.delete(); null } else { reload(); ImportResult("pack", skins.first().pack, skins.size) }
        }.getOrNull()
    }
}

/** A skin found inside a pack: [pack] and [name] are display names. */
class PackSkin(val pack: String, val name: String, val texturePath: String, val png: ByteArray)

/**
 * Reads a .brpack (this game's skin pack) exactly like a Minecraft Bedrock .mcpack: a zip with an optional manifest.json
 * (header.name = pack name), a skins.json ({"serialize_name", "localization_name", "skins": [{"localization_name", "texture"}]})
 * and optional texts/<locale>.lang files that give the display names (skinpack.<pack> = pack name, skin.<pack>.<skin> = skin name).
 * One pack can hold many skins. Without skins.json, every .png at the top of the zip is a skin. Limits keep a hostile zip from using lots of memory.
 */
object SkinPack {
    private const val MAX_ENTRIES = 1024; private const val MAX_SKINS = 200; private const val MAX_PNG = 8 * 1024 * 1024

    fun read(file: File, locale: String = "en_US"): List<PackSkin> {
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
            // display names: texts/<locale>.lang, else en_US, else any .lang (Bedrock format: key=value, "#" starts a comment)
            val langFiles = names.filter { it.endsWith(".lang", true) && it.contains("texts/", true) }
            val langName = langFiles.firstOrNull { it.substringAfterLast('/').equals("$locale.lang", true) }
                ?: langFiles.firstOrNull { it.substringAfterLast('/').equals("en_US.lang", true) } ?: langFiles.firstOrNull()
            val lang = HashMap<String, String>()
            langName?.let { text(it) }?.lineSequence()?.forEach { raw ->
                val l = raw.substringBefore("\t#").trim()
                if (l.isEmpty() || l.startsWith("#")) return@forEach
                val i = l.indexOf('='); if (i > 0) lang[l.substring(0, i).trim()] = l.substring(i + 1).trim()
            }
            val skinsJson = find("skins.json")
            val root = skinsJson?.let { text(it) }?.let { MiniJson.parse(it) } as? Map<*, *>
            val serialize = (root?.get("serialize_name") as? String)?.takeIf { it.isNotBlank() }
            val packLoc = (root?.get("localization_name") as? String)?.takeIf { it.isNotBlank() }
            val packKey = serialize ?: packLoc
            val manifestName = find("manifest.json")?.let { text(it) }?.let { runCatching { ((MiniJson.parse(it) as? Map<*, *>)?.get("header") as? Map<*, *>)?.get("name") as? String }.getOrNull() }
            val packName = listOfNotNull(packKey?.let { lang["skinpack.$it"] }, packLoc?.let { lang["skinpack.$it"] }, manifestName?.takeIf { it.isNotBlank() && !it.startsWith("pack.") },
                serialize, packLoc).firstOrNull() ?: file.nameWithoutExtension
            val list = root?.get("skins") as? List<*>
            if (list != null) {
                val base = skinsJson!!.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                for (s in list) {
                    if (out.size >= MAX_SKINS) break
                    val m = s as? Map<*, *> ?: continue
                    val tex = (m["texture"] as? String)?.replace('\\', '/')?.removePrefix("./") ?: continue
                    if (tex.contains("..")) continue
                    val bytes = png(base + tex) ?: continue
                    val loc = (m["localization_name"] as? String)?.takeIf { it.isNotBlank() }
                    val label = listOfNotNull(loc?.let { l -> packKey?.let { lang["skin.$it.$l"] } }, loc?.let { l -> packLoc?.let { lang["skin.$it.$l"] } }, loc).firstOrNull()
                        ?: tex.substringAfterLast('/').substringBeforeLast('.')
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
