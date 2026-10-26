// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import de.schliweb.sheet.core.ocr.OcrModel
import de.schliweb.sheet.core.ocr.OcrModels
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.core.ocr.Tensor
import de.schliweb.sheet.core.ocr.Vocab
import java.nio.FloatBuffer

class AssetOcrModels(private val context: Context) : OcrModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private val vocabs = mutableMapOf<RecModel, Vocab>()

    private fun model(name: String) = OcrModel { input ->
        val session = sessions.getOrPut(name) {
            val bytes = context.assets.open("$DIR/$name.ort").use { it.readBytes() }
            env.createSession(
                bytes,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                },
            )
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input.data), input.shape).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { r ->
                val out = r.get(0) as OnnxTensor
                val b = out.floatBuffer
                Tensor(FloatArray(b.remaining()).also { b.get(it) }, out.info.shape)
            }
        }
    }

    override val detector: OcrModel = model("det")

    override fun recognizer(model: RecModel): OcrModel = model(model.asset)

    override fun vocab(model: RecModel): Vocab = vocabs.getOrPut(model) {
        context.assets.open("$DIR/${model.asset}_dict.txt").bufferedReader().useLines { Vocab.fromLines(it) }
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private companion object {
        const val DIR = "paddleocr"
    }
}
