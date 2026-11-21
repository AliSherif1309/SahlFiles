package com.sahl.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore

/**
 * Sahl Union door of Sahl Files (only Sahl-signed apps can open it):
 *   content://com.sahl.files.union/search?q=report -> files whose name matches (for Sahl Search)
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        if (uri.pathSegments.firstOrNull() != "search") return null
        val out = MatrixCursor(arrayOf("kind", "title", "subtitle", "date", "action", "key", "value", "package"))
        val q = (uri.getQueryParameter("q") ?: "").trim()
        if (q.length < 2) return out
        try {
            c.contentResolver.query(MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_MODIFIED),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
                arrayOf("%$q%"), MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")?.use {
                while (it.moveToNext() && out.count < 15) {
                    val path = it.getString(1) ?: continue
                    if (path.contains("/Android/data/") || path.contains("/.")) continue
                    val folder = path.substringBeforeLast('/').substringAfter("/storage/emulated/0/", path.substringBeforeLast('/'))
                    out.addRow(arrayOf<Any>("file", it.getString(0) ?: path.substringAfterLast('/'), folder, it.getLong(2) * 1000,
                        "com.sahl.files.action.OPEN", "path", path, c.packageName))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
