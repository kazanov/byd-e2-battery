package com.example.bydbattery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Отдаёт файлы сканов другим приложениям (Telegram, почта и т.п.) только на чтение. */
class ScanFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.bydbattery.files"
        private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]+$")

        fun dir(ctx: android.content.Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        fun uriFor(file: File): Uri = Uri.parse("content://$AUTHORITY/${file.name}")
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!SAFE_NAME.matches(name)) return null
        val f = File(dir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".csv") == true) "text/csv" else "text/plain"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols: Array<String> = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row: Array<Any?> = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
