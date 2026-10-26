// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.pdf

object Jpeg {
    fun size(jpeg: ByteArray): Pair<Int, Int> {
        require(jpeg.size > 4 && u8(jpeg, 0) == 0xFF && u8(jpeg, 1) == 0xD8) { "not a JPEG" }
        var i = 2
        while (i + 9 < jpeg.size) {
            if (u8(jpeg, i) != 0xFF) {
                i++
                continue
            }
            val marker = u8(jpeg, i + 1)
            if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD7) {
                i++
                continue
            }
            val length = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = (u8(jpeg, i + 5) shl 8) or u8(jpeg, i + 6)
                val w = (u8(jpeg, i + 7) shl 8) or u8(jpeg, i + 8)
                return w to h
            }
            i += 2 + length
        }
        throw IllegalArgumentException("no JPEG frame header")
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
