// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.document

import de.schliweb.sheet.core.ocr.OcrPage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DocumentStore(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Document {
        val file = File(dir, DOCUMENT)
        if (!file.exists()) return Document()
        val doc = runCatching { json.decodeFromString(Document.serializer(), file.readText()) }.getOrNull() ?: return recover(file)
        return doc.copy(pages = doc.pages.filter { plain(it.id).exists() || image(it.id).exists() })
    }

    private fun recover(file: File): Document {
        file.renameTo(File(dir, "$DOCUMENT.bad"))
        val plains = dir.listFiles { f -> f.name.endsWith(PLAIN) }.orEmpty().sortedBy { it.lastModified() }
        val ids = plains.map { it.name.removeSuffix(PLAIN) }
        ids.forEach { textFile(it).delete() }
        return Document(ids.map { Document.Page(it) }).also(::save)
    }

    fun save(document: Document) = write(File(dir, DOCUMENT), Document.serializer(), document)

    fun image(id: String): File = File(dir, "$id.jpg")

    fun plain(id: String): File = File(dir, "$id$PLAIN")

    fun photo(id: String): File = File(dir, "$id.photo.jpg")

    fun text(page: Document.Page): PageText? {
        val file = textFile(page.id)
        if (!file.exists()) return null
        val stored = runCatching { json.decodeFromString(StoredText.serializer(), file.readText()) }.getOrNull() ?: return null
        return PageText(stored.text, stored.turns).takeIf { stored.cut == page.cut }
    }

    fun saveText(page: Document.Page, text: OcrPage, turns: Int) = write(textFile(page.id), StoredText.serializer(), StoredText(page.cut, text, turns.mod(4)))

    fun delete(id: String) {
        image(id).delete()
        plain(id).delete()
        photo(id).delete()
        textFile(id).delete()
    }

    fun prune(keep: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.')
            if (f.name.startsWith(DOCUMENT) || id in keep) return@forEach
            f.delete()
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun textFile(id: String) = File(dir, "$id.txt.json")

    @Synchronized
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        dir.mkdirs()
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(serializer, value).toByteArray())
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    class PageText(val text: OcrPage, val turns: Int)

    @kotlinx.serialization.Serializable
    private class StoredText(val cut: Int = -1, val text: OcrPage, val turns: Int = 0)

    private companion object {
        const val DOCUMENT = "document.json"
        const val PLAIN = ".plain.jpg"
    }
}
