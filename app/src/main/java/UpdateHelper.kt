package com.backrooms.game
// UpdateHelper.kt - Android: check GitHub Releases for a newer build. Flow (Zalith-style):
//   1) Notify "Update available" with release notes
//   2) Player taps Download → confirm dialog
//   3) Open the asset URL in the system browser (browser starts the download; no in-app APK fetch/install)
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast

class UpdateHelper(private val act: Activity) {

    /** Reads this build's version from the package. Debug / local builds are "dev": no update checks. */
    fun loadVersion() {
        try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            val debug = (act.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            AppVersion.name = if (debug) "dev" else (pi.versionName ?: "dev")
            AppVersion.code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) {
            AppVersion.name = "dev"; AppVersion.code = 0
        }
    }

    /** Background check; shows the "update available" dialog on the UI thread when a newer release exists. */
    fun check() {
        if (!AppVersion.isRelease) return
        Thread {
            val r = try { Updater.latest("apk") } catch (_: Exception) { null } ?: return@Thread
            if (Updater.isNewer(r.tag, AppVersion.name)) act.runOnUiThread { if (!act.isFinishing) showAvailable(r) }
        }.apply { isDaemon = true; start() }
    }

    /** Step 1 — view the update (notes + version). Download is a separate action. */
    private fun showAvailable(r: Release) {
        val notes = r.notes.trim().lines().take(8).joinToString("\n").take(500)
        val body = buildString {
            append(r.tag); append(" is out (you have v"); append(AppVersion.name); append(").\n\n")
            append(r.title)
            if (notes.isNotBlank()) { append("\n\n"); append(notes) }
            append("\n\nDownload opens the official APK link in your browser.")
        }
        AlertDialog.Builder(act)
            .setTitle("Update available")
            .setMessage(body)
            .setPositiveButton("Download") { _, _ -> confirmOpenBrowser(r) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Step 2 — confirm before leaving the app. */
    private fun confirmOpenBrowser(r: Release) {
        val sizeMb = if (r.size > 0) String.format("%.1f MB", r.size / (1024.0 * 1024.0)) else "unknown size"
        AlertDialog.Builder(act)
            .setTitle("Open download link?")
            .setMessage(
                "Open this link in your browser to download ${r.assetName} ($sizeMb)?\n\n" +
                "The browser will start the download. Install the APK from Downloads when it finishes.\n\n" +
                r.assetUrl
            )
            .setPositiveButton("Open browser") { _, _ -> openInBrowser(r.assetUrl) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Step 3 — browser handles the GET on the release asset (direct content URL). */
    private fun openInBrowser(url: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(act, "No browser available to open the download link.", Toast.LENGTH_LONG).show()
        }
    }

    /** Kept so MainActivity.onResume still compiles; in-app install flow was removed. */
    fun onResume() { /* no-op */ }
}
