// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

object LetterForms {
    private val PERSIAN = mapOf('ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک') + ('٠'..'٩').zip('۰'..'۹')
    private const val NOT_ARABIC = "پچژگٹڈڑںے"
    private const val MIN_LETTERS = 2

    fun isPersian(page: OcrPage): Boolean = page.words.sumOf { w -> w.text.count { it in NOT_ARABIC } } >= MIN_LETTERS

    fun persian(text: String): String = String(CharArray(text.length) { PERSIAN[text[it]] ?: text[it] })

    fun persian(page: OcrPage): OcrPage =
        page.copy(lines = page.lines.map { line -> line.copy(words = line.words.map { it.copy(text = persian(it.text)) }) })
}
