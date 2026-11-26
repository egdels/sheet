// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.corners

import android.content.Context
import de.schliweb.sheet.core.corners.DocQuad
import de.schliweb.sheet.core.corners.DocQuadModel
import de.schliweb.sheet.core.corners.DocQuadOutput
import de.schliweb.sheet.core.ncnn.NcnnNet

class NcnnDocQuadModel(context: Context) : DocQuadModel, AutoCloseable {
    private val net = NcnnNet.fromAssets(context.assets, ASSET, threads = 1)

    override fun run(input: FloatArray): DocQuadOutput {
        val (corners, mask) = net.run(input, 3, DocQuad.INPUT_SIZE, DocQuad.INPUT_SIZE, OUTPUTS)
        return DocQuadOutput(corners.data, mask.data)
    }

    override fun close() = net.close()

    companion object {
        const val ASSET = "docquad/docquadnet256"

        private val OUTPUTS = arrayOf("out0", "out1")
    }
}
