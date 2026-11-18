package com.backrooms.game
// DesktopUpdate.kt - desktop: update check at launch, download, signature check against the running jar, restart into the new jar.
import java.awt.Component
import java.awt.FlowLayout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** The jar we are running from, or null when started from class files (no updates then). */
fun runningJar(): File? = try {
    val f = File(Updater::class.java.protectionDomain.codeSource.location.toURI()); if (f.isFile && f.name.endsWith(".jar", true)) f else null
} catch (_: Exception) { null }

/** The file the new jar is downloaded to, next to the running one. */
fun updateJarOf(cur: File) = File(cur.parentFile, cur.nameWithoutExtension + ".update.jar")

/** Reads the version.properties the build put into the jar. A local build has none: it stays "dev" and never checks for updates. */
fun loadDesktopVersion() {
    val p = java.util.Properties()
    try { Updater::class.java.getResourceAsStream("/version.properties")?.use { p.load(it) } } catch (_: Exception) {}
    AppVersion.name = p.getProperty("versionName") ?: "dev"; AppVersion.code = p.getProperty("versionCode")?.toIntOrNull() ?: 0
}

/** SHA-256 fingerprints of the signer certificates when EVERY file in the jar is signed and intact, else null (unsigned, damaged or tampered with). */
fun jarSigners(f: File): Set<String>? {
    try {
        JarFile(f, true).use { jf ->
            val out = HashSet<String>(); val md = MessageDigest.getInstance("SHA-256"); var files = 0
            for (e in jf.entries()) {
                if (e.isDirectory) continue
                jf.getInputStream(e).use { ins -> val buf = ByteArray(8192); while (ins.read(buf) >= 0) { /* reading checks the digest: SecurityException if the file was changed */ } }
                if (e.name.startsWith("META-INF/")) continue // the signature files themselves are not signed
                val cs = e.codeSigners; if (cs == null || cs.isEmpty()) return null
                for (s in cs) out.add(md.digest(s.signerCertPath.certificates[0].encoded).joinToString("") { "%02x".format(it) })
                files++
            }
            return if (files == 0 || out.isEmpty()) null else out
        }
    } catch (_: Exception) { return null }
}

/** null = the new jar is signed by the same key as the running one; otherwise the reason to refuse it. */
fun checkUpdateJar(newJar: File, curJar: File): String? {
    val a = jarSigners(curJar) ?: return "The game you are running is not a signed build, so an update cannot be verified."
    val b = jarSigners(newJar) ?: return "The download is not signed, or it is damaged."
    return if (a == b) null else "The download is signed with a different key than the game you are running."
}

private fun javaExe() = File(System.getProperty("java.home"), "bin/" + (if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")).path

/** Start the downloaded jar in apply mode and end this process: the new jar waits for us to exit, copies itself over the old file, and starts from there. */
fun restartIntoUpdate(newJar: File, cur: File) {
    ProcessBuilder(javaExe(), "-cp", newJar.path, "com.backrooms.game.DesktopKt", "--apply-update", cur.path).start()
    exitProcess(0)
}

/** Runs in the NEW jar: replace `oldJar` with this file (retrying while the old process is still shutting down), then start again from `oldJar`. */
fun applyUpdate(oldJar: File, me: File, relaunch: Boolean = true): Boolean {
    repeat(40) {
        try {
            Files.copy(me.toPath(), oldJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (relaunch) { ProcessBuilder(javaExe(), "-jar", oldJar.path).start(); exitProcess(0) }
            return true
        } catch (_: IOException) { Thread.sleep(500) }
    }
    return false
}

/** At launch: ask GitHub Releases (in the background); if a newer jar exists, ask the player, download it, verify its signature and restart. */
fun startUpdateCheck(parent: () -> Component?) {
    val cur = runningJar() ?: return
    if (!AppVersion.isRelease) return
    File(cur.parentFile, cur.nameWithoutExtension + ".update.jar").takeIf { it.exists() && it != cur }?.delete() // leftover of the last update
    Thread {
        val r = try { Updater.latest("jar") } catch (_: Exception) { null } ?: return@Thread
        if (!Updater.isNewer(r.tag, AppVersion.name)) return@Thread
        SwingUtilities.invokeLater {
            val notes = r.notes.trim().lines().take(8).joinToString("\n").take(500)
            val yes = JOptionPane.showConfirmDialog(parent(), "A new version is out: ${r.tag}   (you have v${AppVersion.name}).\n\n${r.title}\n$notes\n\nDownload it and restart the game now?",
                "Backrooms update", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
            if (yes) downloadAndRestart(r, cur, parent())
        }
    }.apply { isDaemon = true; start() }
}

private fun downloadAndRestart(r: Release, cur: File, parent: Component?) {
    val bar = JProgressBar(0, 100).apply { isStringPainted = true }
    val dlg = JDialog(SwingUtilities.getWindowAncestor(parent), "Downloading ${r.tag}").apply {
        layout = FlowLayout(); add(JLabel("Downloading the update...")); add(bar); setSize(340, 100); setLocationRelativeTo(parent); defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE; isVisible = true }
    Thread {
        val target = updateJarOf(cur)
        val ok = Updater.download(r.assetUrl, target) { d, t -> SwingUtilities.invokeLater { if (t > 0) bar.value = (d * 100 / t).toInt() else bar.isIndeterminate = true } }
        val problem = if (!ok) "The download failed." else checkUpdateJar(target, cur)
        SwingUtilities.invokeLater {
            dlg.dispose()
            if (problem != null) { target.delete(); JOptionPane.showMessageDialog(parent, "$problem\nThe update was not installed.", "Backrooms update", JOptionPane.ERROR_MESSAGE) }
            else restartIntoUpdate(target, cur)
        }
    }.apply { isDaemon = true; start() }
}
