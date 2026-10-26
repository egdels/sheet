// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.corners

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import de.schliweb.sheet.core.corners.DocQuad
import de.schliweb.sheet.core.corners.DocQuadModel
import de.schliweb.sheet.core.corners.DocQuadOutput
import java.nio.FloatBuffer

class OrtDocQuadModel(context: Context) : DocQuadModel, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(ASSET).use { it.readBytes() }.let { bytes ->
        env.createSession(
            bytes,
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            },
        )
    }

    @Synchronized
    override fun run(input: FloatArray): DocQuadOutput {
        val shape = longArrayOf(1, 3, DocQuad.INPUT_SIZE.toLong(), DocQuad.INPUT_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf("input" to tensor)).use { result ->
                fun flat(name: String): FloatArray {
                    val b = (result.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
                return DocQuadOutput(flat("corner_heatmaps"), flat("mask_logits"))
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val ASSET = "docquad/docquadnet256.ort"
    }
}
