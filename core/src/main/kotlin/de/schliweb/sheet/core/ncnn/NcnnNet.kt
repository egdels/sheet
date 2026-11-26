// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ncnn

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class NcnnNet private constructor(private var handle: Long) : AutoCloseable {
    class Output(val data: FloatArray, val channels: Int, val height: Int, val width: Int)

    private val lock = ReentrantReadWriteLock()

    fun run(chw: FloatArray, channels: Int, height: Int, width: Int): Output = run(chw, channels, height, width, FIRST_OUTPUT)[0]

    fun run(chw: FloatArray, channels: Int, height: Int, width: Int, outputs: Array<String>): List<Output> {
        require(channels > 0 && height > 0 && width > 0 && chw.size == channels * height * width) {
            "input has ${chw.size} values, expected $channels x $height x $width"
        }
        val dims = IntArray(outputs.size * 3)
        val data = lock.read {
            check(handle != 0L) { "NcnnNet is closed" }
            nativeRun(handle, chw, channels, height, width, outputs, dims)
        } ?: error("NCNN returned no result")
        return List(outputs.size) { i -> Output(data[i], dims[i * 3], dims[i * 3 + 1], dims[i * 3 + 2]) }
    }

    override fun close() = lock.write {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    companion object {
        const val PARAM_SUFFIX = ".ncnn.param"
        const val BIN_SUFFIX = ".ncnn.bin"
        const val LIBRARY_PROPERTY = "sheet.ncnn.library"
        private val FIRST_OUTPUT = arrayOf("out0")

        init {
            val file = System.getProperty(LIBRARY_PROPERTY)
            if (file != null) System.load(file) else System.loadLibrary("sheet_ncnn")
        }

        fun fromAssets(assetManager: Any, basePath: String, threads: Int): NcnnNet {
            val handle = nativeLoadAssets(assetManager, basePath + PARAM_SUFFIX, basePath + BIN_SUFFIX, maxOf(1, threads))
            check(handle != 0L) { "cannot load NCNN model from assets: $basePath" }
            return NcnnNet(handle)
        }

        fun fromFiles(basePath: File, threads: Int): NcnnNet {
            val handle = nativeLoadFiles(basePath.path + PARAM_SUFFIX, basePath.path + BIN_SUFFIX, maxOf(1, threads))
            check(handle != 0L) { "cannot load NCNN model from files: $basePath" }
            return NcnnNet(handle)
        }

        @JvmStatic
        private external fun nativeLoadAssets(assetManager: Any, paramPath: String, binPath: String, threads: Int): Long

        @JvmStatic
        private external fun nativeLoadFiles(paramPath: String, binPath: String, threads: Int): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeRun(
            handle: Long,
            input: FloatArray,
            channels: Int,
            height: Int,
            width: Int,
            outputNames: Array<String>,
            dims: IntArray,
        ): Array<FloatArray>?
    }
}
