package me.magnum.melonds.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

val DocumentFile.nameWithoutExtension get() = name?.substringBeforeLast('.')

val DocumentFile.extension get() = name?.substringAfterLast('.')

/**
 * Resolves a [DocumentFile] cleanly for both `file://` URIs and SAF `content://` URIs.
 */
fun Uri.toSingleDocumentFile(context: Context): DocumentFile? {
    return if (scheme == ContentResolver.SCHEME_FILE) {
        path?.let { DocumentFile.fromFile(File(it)) }
    } else {
        DocumentFile.fromSingleUri(context, this)
    }
}
