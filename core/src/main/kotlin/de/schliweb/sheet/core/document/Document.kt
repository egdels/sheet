// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.document

import de.schliweb.sheet.core.Quad
import kotlinx.serialization.Serializable

@Serializable
data class Document(val pages: List<Page> = emptyList()) {

    @Serializable
    data class Page(val id: String, val revision: Int = 0, val quad: Quad? = null, val turns: Int = 0)

    val isEmpty: Boolean get() = pages.isEmpty()

    fun indexOf(id: String): Int = pages.indexOfFirst { it.id == id }

    fun add(id: String, quad: Quad? = null): Document = copy(pages = pages + Page(id, quad = quad))

    fun remove(id: String): Document = copy(pages = pages.filterNot { it.id == id })

    fun move(id: String, index: Int): Document {
        val from = indexOf(id)
        if (from < 0) return this
        val rest = pages.toMutableList()
        val page = rest.removeAt(from)
        rest.add(index.coerceIn(0, rest.size), page)
        return copy(pages = rest)
    }

    fun touched(id: String): Document = change(id) { it.copy(revision = it.revision + 1) }

    fun turned(id: String, quarters: Int): Document = change(id) { it.copy(revision = it.revision + 1, turns = (it.turns + quarters).mod(4)) }

    fun recut(id: String, quad: Quad): Document = change(id) { it.copy(revision = it.revision + 1, quad = quad) }

    private fun change(id: String, f: (Page) -> Page): Document = copy(pages = pages.map { if (it.id == id) f(it) else it })
}
