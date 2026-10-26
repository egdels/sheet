// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import java.util.Locale

enum class RecModel(val asset: String) {
    EN("en_PP-OCRv5_mobile_rec"),
    LATIN("latin_PP-OCRv5_mobile_rec"),
    ESLAV("eslav_PP-OCRv5_mobile_rec"),
    CYRILLIC("cyrillic_PP-OCRv5_mobile_rec"),
    ARABIC("arabic_PP-OCRv5_mobile_rec"),
    DEVANAGARI("devanagari_PP-OCRv5_mobile_rec"),
    THAI("th_PP-OCRv5_mobile_rec"),
    GREEK("el_PP-OCRv5_mobile_rec"),
    MULTI("PP-OCRv5_mobile_rec"),
    ;

    val hasMultiFallback: Boolean get() = this == EN || this == LATIN

    companion object {
        private val BY_LANGUAGE: Map<String, RecModel> = buildMap {
            put("en", EN)
            for (l in listOf(
                "de", "fr", "es", "it", "pt", "nl", "da", "sv", "nb", "nn", "no", "fi", "cs", "pl", "hu", "ro",
                "tr", "hr", "sl", "sk", "ca", "eu", "gl", "lt", "lv", "et", "is", "ga", "cy", "mt", "af", "id",
                "ms", "sw", "tl", "fil", "vi", "la", "bs", "sq",
            )) put(l, LATIN)
            for (l in listOf("ru", "uk", "be")) put(l, ESLAV)
            for (l in listOf("bg", "mk", "sr", "kk", "ky", "mn")) put(l, CYRILLIC)
            for (l in listOf("ar", "fa", "ur", "ps")) put(l, ARABIC)
            for (l in listOf("hi", "mr", "ne", "sa")) put(l, DEVANAGARI)
            put("th", THAI)
            put("el", GREEK)
            for (l in listOf("zh", "ja", "ko")) put(l, MULTI)
        }

        fun forLocale(locale: Locale): RecModel {
            val language = locale.language.lowercase(Locale.ROOT)
            if (language == "sr" && locale.script.equals("Latn", ignoreCase = true)) return LATIN
            return BY_LANGUAGE[language] ?: MULTI
        }
    }
}
