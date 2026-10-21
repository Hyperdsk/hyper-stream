package com.lagradost.cloudstream4.desktop.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets the TV home screen show the picture of where playback stopped on its Continue watching
 * cards. Read-only, and only serves the snapshot JPEGs by name, nothing else in the app's files.
 */
class SnapshotProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.matches(Regex("[A-Za-z0-9-]+\\.jpg"))) throw FileNotFoundException(name)
        val dir = File(File(context!!.filesDir, "data"), "snapshots")
        return File(dir, name).takeIf { it.isFile } ?: throw FileNotFoundException(name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Snapshots are read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uriFor(packageName: String, file: File, version: Long): Uri =
            Uri.parse("content://$packageName.snapshots/${file.name}?v=$version")
    }
}
