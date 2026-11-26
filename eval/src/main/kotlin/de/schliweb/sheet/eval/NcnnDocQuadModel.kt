// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.corners.DocQuad
import de.schliweb.sheet.core.corners.DocQuadModel
import de.schliweb.sheet.core.corners.DocQuadOutput
import de.schliweb.sheet.core.ncnn.NcnnNet
import java.io.File

class NcnnDocQuadModel(model: File) : DocQuadModel, AutoCloseable {
    private val net = NcnnNet.fromFiles(model, threads = 1)

    override fun run(input: FloatArray): DocQuadOutput {
        val (corners, mask) = net.run(input, 3, DocQuad.INPUT_SIZE, DocQuad.INPUT_SIZE, OUTPUTS)
        return DocQuadOutput(corners.data, mask.data)
    }

    override fun close() = net.close()

    private companion object {
        val OUTPUTS = arrayOf("out0", "out1")
    }
}
