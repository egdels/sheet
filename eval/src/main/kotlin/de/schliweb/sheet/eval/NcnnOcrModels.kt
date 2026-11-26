// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.ncnn.NcnnNet
import de.schliweb.sheet.core.ocr.OcrModel
import de.schliweb.sheet.core.ocr.OcrModels
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.core.ocr.Tensor
import de.schliweb.sheet.core.ocr.Vocab
import java.io.File

class NcnnOcrModels(private val dir: File) : OcrModels, AutoCloseable {
    private val nets = mutableMapOf<String, NcnnNet>()
    private val vocabs = mutableMapOf<RecModel, Vocab>()

    private fun model(name: String, detector: Boolean) = OcrModel { input ->
        val net = synchronized(nets) {
            nets.getOrPut(name) { NcnnNet.fromFiles(File(dir, name), maxOf(2, Runtime.getRuntime().availableProcessors() / 2)) }
        }
        val out = net.run(input.data, input.shape[1].toInt(), input.shape[2].toInt(), input.shape[3].toInt())
        val shape = if (detector) longArrayOf(1, 1, out.height.toLong(), out.width.toLong()) else longArrayOf(1, out.height.toLong(), out.width.toLong())
        Tensor(out.data, shape)
    }

    override val detector: OcrModel = model("det", detector = true)

    override fun recognizer(model: RecModel): OcrModel = model(model.asset, detector = false)

    override fun vocab(model: RecModel): Vocab = synchronized(vocabs) {
        vocabs.getOrPut(model) { File(dir, "${model.asset}_dict.txt").useLines { Vocab.fromLines(it) } }
    }

    override fun close() {
        nets.values.forEach { it.close() }
    }
}
