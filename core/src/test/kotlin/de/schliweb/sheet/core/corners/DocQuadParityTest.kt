// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocQuadParityTest {
    private class Case(line: String) {
        val f = line.trim().split(" ")
        val name = f[0]
        val width = f[1].toInt()
        val height = f[2].toInt()
        val fromMask = f[3] == "MASK"
        val chosenValid = f[5].toBoolean()
        val cornersValid = f[6].toBoolean()
        val confidence = f[7].toDouble() * f[8].toDouble()
        val chosen = quad(9)
        val corners = quad(17)

        private fun quad(at: Int): Quad {
            val p = List(4) { Point(f[at + 2 * it].toDouble(), f[at + 2 * it + 1].toDouble()) }
            return Quad(p[0], p[1], p[2], p[3])
        }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/docquad/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private fun output(name: String): DocQuadOutput {
        val floats = ByteBuffer.wrap(resource("$name.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val heatmaps = FloatArray(4 * DocQuad.CELLS).also { floats.get(it) }
        val mask = FloatArray(DocQuad.CELLS).also { floats.get(it) }
        return DocQuadOutput(heatmaps, mask)
    }

    private fun assertQuad(expected: Quad, actual: Quad, what: String) {
        for ((e, a) in expected.corners.zip(actual.corners)) {
            assertEquals(e.x, a.x, 1e-9, what)
            assertEquals(e.y, a.y, 1e-9, what)
        }
    }

    @Test
    fun matchesMakeACopy() {
        val cases = resource("expected.txt").decodeToString().lines().filter { it.isNotBlank() }.map(::Case)
        assertEquals(10, cases.size)
        assertTrue(cases.any { it.fromMask } && cases.any { !it.fromMask })
        for (c in cases) {
            val d = DocQuad.postprocess(output(c.name), Letterbox.of(c.width, c.height))
            assertEquals(c.fromMask, d.fromMask, "${c.name}: source")
            assertQuad(c.chosen, d.quad, "${c.name}: chosen outline")
            assertQuad(c.corners, d.corners, "${c.name}: corner outline")
            assertEquals(c.confidence, d.confidence, 1e-12, "${c.name}: confidence")
            assertEquals(c.chosenValid, d.quad.isPlausibleIn(c.width, c.height), "${c.name}: chosen plausible")
            assertEquals(c.cornersValid, d.corners.isPlausibleIn(c.width, c.height), "${c.name}: corners plausible")
        }
    }
}
