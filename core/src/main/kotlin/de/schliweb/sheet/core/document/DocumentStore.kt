// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.document

import de.schliweb.sheet.core.ocr.OcrPage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class DocumentStore(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Document {
        val file = File(dir, DOCUMENT)
        if (!file.exists()) return Document()
        val doc = json.decodeFromString(Document.serializer(), file.readText())
        return doc.copy(pages = doc.pages.filter { image(it.id).exists() })
    }

    fun save(document: Document) = write(File(dir, DOCUMENT), Document.serializer(), document)

    fun image(id: String): File = File(dir, "$id.jpg")

    fun photo(id: String): File = File(dir, "$id.photo.jpg")

    fun text(page: Document.Page): OcrPage? {
        val file = textFile(page.id)
        if (!file.exists()) return null
        val stored = runCatching { json.decodeFromString(StoredText.serializer(), file.readText()) }.getOrNull() ?: return null
        return stored.text.takeIf { stored.revision == page.revision }
    }

    fun saveText(page: Document.Page, text: OcrPage) = write(textFile(page.id), StoredText.serializer(), StoredText(page.revision, text))

    fun delete(id: String) {
        image(id).delete()
        photo(id).delete()
        textFile(id).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun textFile(id: String) = File(dir, "$id.txt.json")

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        dir.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(serializer, value))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.path}")
        }
    }

    @kotlinx.serialization.Serializable
    private class StoredText(val revision: Int, val text: OcrPage)

    private companion object {
        const val DOCUMENT = "document.json"
    }
}
