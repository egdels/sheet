// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import de.schliweb.sheet.core.pdf.PdfPage
import de.schliweb.sheet.core.pdf.SearchablePdf
import java.io.IOException

class SaveFolder(private val context: Context) {
    private val prefs = context.getSharedPreferences("sheet", Context.MODE_PRIVATE)

    val uri: Uri?
        get() {
            val stored = prefs.getString(KEY, null)?.let(Uri::parse) ?: return null
            val granted = context.contentResolver.persistedUriPermissions.any { it.uri == stored && it.isWritePermission }
            return stored.takeIf { granted }
        }

    fun set(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val old = prefs.getString(KEY, null)?.let(Uri::parse)
        if (old != null && old != tree) runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        prefs.edit { putString(KEY, tree.toString()) }
    }

    fun displayName(tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun writePdf(tree: Uri, name: String, pageCount: Int, pages: (Int) -> PdfPage): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/pdf", name)
            ?: throw IOException("could not create $name")
        try {
            context.contentResolver.openOutputStream(doc)?.buffered()?.use { out -> SearchablePdf.write(out, pageCount, pages) }
                ?: throw IOException("could not open $doc")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
            throw e
        }
        return doc
    }

    private companion object {
        const val KEY = "saveFolder"
    }
}
