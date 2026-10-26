// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.document

import de.schliweb.sheet.core.ocr.Box
import de.schliweb.sheet.core.ocr.OcrLine
import de.schliweb.sheet.core.ocr.OcrPage
import de.schliweb.sheet.core.ocr.OcrWord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTest {
    private val abc = Document().add("a").add("b").add("c")

    @Test
    fun moveKeepsTheOthersInOrder() {
        assertEquals(listOf("b", "c", "a"), abc.move("a", 2).pages.map { it.id })
        assertEquals(listOf("c", "a", "b"), abc.move("c", 0).pages.map { it.id })
        assertEquals(listOf("a", "c", "b"), abc.move("b", 99).pages.map { it.id })
        assertEquals(abc, abc.move("x", 0))
    }

    @Test
    fun turnsWrapAndRecutKeepsThem() {
        val q = de.schliweb.sheet.core.Quad.full(10, 20)
        val doc = Document().add("a", q).turned("a", 3).turned("a", 2)
        assertEquals(1, doc.pages[0].turns)
        assertEquals(2, doc.pages[0].revision)
        val recut = doc.recut("a", q.copy(topLeft = de.schliweb.sheet.core.Point(1.0, 1.0)))
        assertEquals(1, recut.pages[0].turns)
        assertEquals(3, recut.pages[0].revision)
    }

    @Test
    fun removeAndTouch() {
        assertEquals(listOf("a", "c"), abc.remove("b").pages.map { it.id })
        assertEquals(listOf(0, 1, 0), abc.touched("b").pages.map { it.revision })
    }

    private val text = OcrPage(100, 200, listOf(OcrLine(listOf(OcrWord("Hallo", Box(1.0, 2.0, 30.0, 12.0), 0.9f)), rtl = false)))

    @Test
    fun storeRoundTrip() {
        val dir = Files.createTempDirectory("doc").toFile()
        val store = DocumentStore(dir)
        assertTrue(store.load().isEmpty)
        val doc = abc.remove("c")
        doc.pages.forEach { store.image(it.id).writeText("jpeg") }
        store.save(doc)
        store.saveText(doc.pages[0], text)
        val loaded = store.load()
        assertEquals(doc, loaded)
        assertEquals(text, store.text(loaded.pages[0]))
        assertNull(store.text(loaded.pages[1]))
        assertNull(store.text(loaded.touched("a").pages[0]))
        dir.deleteRecursively()
    }

    @Test
    fun pageWithoutImageIsDropped() {
        val dir = Files.createTempDirectory("doc").toFile()
        val store = DocumentStore(dir)
        store.image("a").writeText("jpeg")
        store.save(abc)
        assertEquals(listOf("a"), store.load().pages.map { it.id })
        store.clear()
        assertTrue(store.load().isEmpty)
        dir.deleteRecursively()
    }
}
