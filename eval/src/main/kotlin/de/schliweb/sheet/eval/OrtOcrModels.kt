// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import de.schliweb.sheet.core.ocr.OcrModel
import de.schliweb.sheet.core.ocr.OcrModels
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.core.ocr.Tensor
import de.schliweb.sheet.core.ocr.Vocab
import java.io.File
import java.nio.FloatBuffer

class OrtOcrModels(private val dir: File) : OcrModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private val vocabs = mutableMapOf<RecModel, Vocab>()

    private fun model(name: String) = OcrModel { input ->
        val session = sessions.getOrPut(name) {
            env.createSession(
                File(dir, "$name.ort").path,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(maxOf(2, Runtime.getRuntime().availableProcessors() / 2)) },
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
        File(dir, "${model.asset}_dict.txt").useLines { Vocab.fromLines(it) }
    }

    override fun close() {
        sessions.values.forEach { it.close() }
    }
}
