package com.backrooms.game
// UpdateProvider.kt - Android: lets the system installer read the downloaded .apk from our cache (a minimal content provider, no AndroidX needed).
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class UpdateProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "update").canonicalFile
        val f = File(dir, uri.lastPathSegment ?: throw FileNotFoundException()).canonicalFile
        if (f.parentFile != dir || !f.isFile) throw FileNotFoundException(uri.toString()) // only files directly inside cache/update
        return f
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).also { it.addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
