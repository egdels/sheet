// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.pdf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object GlyphlessFont {
    const val NAME = "GlyphLessFont"
    const val ADVANCE = 500
    private const val UNITS_PER_EM = 1000

    val bytes: ByteArray by lazy { build() }

    private fun build(): ByteArray {
        val tables = sortedMapOf(
            "cmap" to cmap(),
            "glyf" to ByteArray(0),
            "head" to head(),
            "hhea" to hhea(),
            "hmtx" to table { repeat(2) { writeShort(ADVANCE); writeShort(0) } },
            "loca" to table { repeat(3) { writeShort(0) } },
            "maxp" to maxp(),
            "name" to name(),
            "post" to post(),
        )
        val n = tables.size
        var pow = 1
        var log = 0
        while (pow * 2 <= n) {
            pow *= 2
            log++
        }
        val font = ByteArrayOutputStream()
        val out = DataOutputStream(font)
        out.writeInt(0x00010000)
        out.writeShort(n)
        out.writeShort(pow * 16)
        out.writeShort(log)
        out.writeShort(n * 16 - pow * 16)
        var offset = 12 + 16 * n
        for ((tag, data) in tables) {
            out.writeBytes(tag)
            out.writeInt(checksum(data).toInt())
            out.writeInt(offset)
            out.writeInt(data.size)
            offset += padded(data.size)
        }
        for (data in tables.values) {
            out.write(data)
            repeat(padded(data.size) - data.size) { out.write(0) }
        }
        val result = font.toByteArray()
        val adjustment = (0xB1B0AFBAL - checksum(result)) and 0xFFFFFFFFL
        var at = 12 + 16 * n
        for ((tag, data) in tables) {
            if (tag == "head") break
            at += padded(data.size)
        }
        for (i in 0 until 4) result[at + 8 + i] = (adjustment shr (24 - 8 * i)).toByte()
        return result
    }

    private fun padded(size: Int) = (size + 3) / 4 * 4

    internal fun checksum(data: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < data.size) {
            var word = 0L
            for (k in 0 until 4) word = (word shl 8) or ((if (i + k < data.size) data[i + k].toInt() else 0).toLong() and 0xFF)
            sum = (sum + word) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun table(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()

    private fun head() = table {
        writeInt(0x00010000)
        writeInt(0x00010000)
        writeInt(0)
        writeInt(0x5F0F3CF5)
        writeShort(0x000B)
        writeShort(UNITS_PER_EM)
        writeLong(0)
        writeLong(0)
        repeat(4) { writeShort(0) }
        writeShort(0)
        writeShort(3)
        writeShort(2)
        writeShort(0)
        writeShort(0)
    }

    private fun hhea() = table {
        writeInt(0x00010000)
        writeShort(UNITS_PER_EM)
        writeShort(0)
        writeShort(0)
        writeShort(ADVANCE)
        writeShort(0)
        writeShort(0)
        writeShort(0)
        writeShort(1)
        writeShort(0)
        writeShort(0)
        repeat(4) { writeShort(0) }
        writeShort(0)
        writeShort(2)
    }

    private fun maxp() = table {
        writeInt(0x00010000)
        writeShort(2)
        writeShort(0)
        writeShort(0)
        writeShort(0)
        writeShort(0)
        writeShort(2)
        repeat(8) { writeShort(0) }
    }

    private fun cmap() = table {
        writeShort(0)
        writeShort(1)
        writeShort(3)
        writeShort(1)
        writeInt(12)
        writeShort(4)
        writeShort(24)
        writeShort(0)
        writeShort(2)
        writeShort(2)
        writeShort(0)
        writeShort(0)
        writeShort(0xFFFF)
        writeShort(0)
        writeShort(0xFFFF)
        writeShort(1)
        writeShort(0)
    }

    private fun name() = table {
        val value = NAME.toByteArray(Charsets.UTF_16BE)
        val ids = listOf(1, 4, 6)
        writeShort(0)
        writeShort(ids.size)
        writeShort(6 + 12 * ids.size)
        for (id in ids) {
            writeShort(3)
            writeShort(1)
            writeShort(0x409)
            writeShort(id)
            writeShort(value.size)
            writeShort(0)
        }
        write(value)
    }

    private fun post() = table {
        writeInt(0x00030000)
        writeInt(0)
        writeShort(-100)
        writeShort(50)
        writeInt(0)
        repeat(4) { writeInt(0) }
    }
}
