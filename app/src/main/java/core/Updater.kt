package com.backrooms.game
// Updater.kt - asks GitHub Releases whether a newer build exists and downloads it. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

/** The running build's version. The clients fill it in (Android: package info, desktop: version.properties inside the jar).
 *  "dev" = a local or debug build: no update checks. */
object AppVersion {
    @Volatile var name = "dev"
    @Volatile var code = 0
    val isRelease get() = code > 0 && !name.contains("dev")
}

/** A published release: its tag (v1.0.27), title and notes, and the file to download (the .apk or the .jar). */
class Release(val tag: String, val title: String, val notes: String, val assetName: String, val assetUrl: String, val size: Long)

object Updater {
    const val REPO = "TerAlone6300/Backroom"

    /** The newest published release that has a file ending in `.ext`, or null (offline, rate limited, nothing published...). */
    fun latest(ext: String, apiUrl: String = "https://api.github.com/repos/$REPO/releases/latest"): Release? {
        val con = java.net.URI(apiUrl).toURL().openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 6000; con.readTimeout = 8000
            con.setRequestProperty("Accept", "application/vnd.github+json"); con.setRequestProperty("User-Agent", "Backrooms-updater")
            if (con.responseCode != 200) return null
            return parse(con.inputStream.bufferedReader().readText(), ext)
        } finally { con.disconnect() }
    }

    fun parse(json: String, ext: String): Release? {
        val j = MiniJson.parse(json) as? Map<*, *> ?: return null
        if (j["draft"] == true || j["prerelease"] == true) return null
        val tag = j["tag_name"] as? String ?: return null
        val asset = (j["assets"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { (it["name"] as? String)?.endsWith(".$ext", true) == true } ?: return null
        return Release(tag, (j["name"] as? String) ?: tag, (j["body"] as? String) ?: "", asset["name"] as String, asset["browser_download_url"] as? String ?: return null, (asset["size"] as? Double)?.toLong() ?: 0L)
    }

    /** True when `tag` (v1.0.27) is newer than `current` (1.0.26): the dotted numbers are compared one by one. */
    fun isNewer(tag: String, current: String): Boolean {
        fun nums(s: String) = s.trim().removePrefix("v").removePrefix("V").split('.', '-', '+').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = nums(tag); val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    /** Download to `dest` (written as dest.part first). progress(done, total); total is 0 when unknown. */
    fun download(url: String, dest: File, progress: (Long, Long) -> Unit): Boolean {
        val part = File(dest.path + ".part")
        try {
            dest.parentFile?.mkdirs()
            val con = java.net.URI(url).toURL().openConnection() as HttpURLConnection
            con.connectTimeout = 10000; con.readTimeout = 20000; con.setRequestProperty("User-Agent", "Backrooms-updater")
            try {
                if (con.responseCode != 200) return false
                val total = con.contentLengthLong.coerceAtLeast(0L); var done = 0L; var last = 0L
                con.inputStream.use { ins -> FileOutputStream(part).use { out ->
                    val buf = ByteArray(32768)
                    while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                        if (done - last > 65536) { last = done; progress(done, total) } }
                } }
                progress(done, total)
                if (total > 0 && done != total) return false
            } finally { con.disconnect() }
            dest.delete(); return part.renameTo(dest)
        } catch (_: Exception) { return false } finally { part.delete() }
    }
}

/** A small JSON reader (objects, arrays, strings, numbers, booleans, null): enough for the GitHub API, no library needed. */
object MiniJson {
    fun parse(s: String): Any? = try { Reader(s).value() } catch (_: Exception) { null }
    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); val c = s[i]
            return when {
                c == '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
                c == '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l.add(value()); ws(); if (s[i++] == ']') break }; l }
                c == '"' -> str()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() }
            }
        }
        fun str(): String {
            i++; val sb = StringBuilder()
            while (true) { val c = s[i++]
                if (c == '"') break
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }; else -> sb.append(e) } }
            return sb.toString()
        }
    }
}
