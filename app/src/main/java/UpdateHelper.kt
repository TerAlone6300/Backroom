package com.backrooms.game
// UpdateHelper.kt - Android: at launch ask GitHub Releases for a newer build; if there is one and the player agrees, download the .apk into the
// cache and hand it to the system installer, where the player confirms the install.
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File

class UpdateHelper(private val act: Activity) {
    private var waitingForPermission: File? = null

    /** Reads this build's version from the package. Debug builds and local builds are "dev": no update checks (their signature would not match anyway). */
    fun loadVersion() {
        try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            val debug = (act.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            AppVersion.name = if (debug) "dev" else (pi.versionName ?: "dev")
            AppVersion.code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) { AppVersion.name = "dev"; AppVersion.code = 0 }
    }

    /** In the background; asks the player on the UI thread when a newer release exists. */
    fun check() {
        if (!AppVersion.isRelease) return
        Thread {
            val r = try { Updater.latest("apk") } catch (_: Exception) { null } ?: return@Thread
            if (Updater.isNewer(r.tag, AppVersion.name)) act.runOnUiThread { if (!act.isFinishing) ask(r) }
        }.apply { isDaemon = true; start() }
    }

    private fun ask(r: Release) {
        val notes = r.notes.trim().lines().take(8).joinToString("\n").take(500)
        AlertDialog.Builder(act).setTitle("Update available")
            .setMessage("${r.tag} is out (you have v${AppVersion.name}).\n\n${r.title}\n$notes\n\nDownload it now? After the download you confirm the install.")
            .setPositiveButton("Update") { _, _ -> download(r) }.setNegativeButton("Later", null).show()
    }

    private fun download(r: Release) {
        val dlg = AlertDialog.Builder(act).setTitle("Downloading ${r.tag}").setMessage("0%").setCancelable(false).show()
        Thread {
            val dir = File(act.cacheDir, "update"); dir.mkdirs(); dir.listFiles()?.forEach { it.delete() } // one update at a time
            val f = File(dir, r.assetName)
            val ok = Updater.download(r.assetUrl, f) { d, t -> act.runOnUiThread { dlg.setMessage(if (t > 0) "${d * 100 / t}%   (${d / 1024} KB)" else "${d / 1024} KB") } }
            act.runOnUiThread { dlg.dismiss(); if (ok) install(f) else Toast.makeText(act, "The download failed. Try again next time.", Toast.LENGTH_LONG).show() }
        }.apply { isDaemon = true; start() }
    }

    /** Android asks for "install unknown apps" once per app: if it is not allowed yet, send the player to that setting and continue when they come back. */
    private fun install(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = f
            AlertDialog.Builder(act).setTitle("Allow installing the update")
                .setMessage("The update is downloaded. Android needs your permission for Backrooms to open the installer: turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ -> act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))) }
                .setNegativeButton("Cancel") { _, _ -> waitingForPermission = null }.show()
            return
        }
        openInstaller(f)
    }

    /** Called from the activity's onResume: finishes the install if the player has just granted the permission. */
    fun onResume() {
        val f = waitingForPermission ?: return
        if (Build.VERSION.SDK_INT < 26 || act.packageManager.canRequestPackageInstalls()) { waitingForPermission = null; openInstaller(f) }
    }

    private fun openInstaller(f: File) {
        val uri = Uri.parse("content://${act.packageName}.update/${Uri.encode(f.name)}")
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { Toast.makeText(act, "Could not open the installer.", Toast.LENGTH_LONG).show() }
    }
}
