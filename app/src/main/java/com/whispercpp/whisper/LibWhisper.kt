package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Whisper C++ requires single-threaded access per context
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun transcribeData(
        data: FloatArray,
        printTimestamp: Boolean = false,
        threads: Int? = null
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "Whisper context has been released" }
        val numThreads = threads ?: WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Running fullTranscribe with $numThreads threads on ${data.size} samples")
        WhisperLib.fullTranscribe(ptr, numThreads, data)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                val textSegment = WhisperLib.getTextSegment(ptr, i)
                if (printTimestamp) {
                    val t0 = toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))
                    val t1 = toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))
                    append("[$t0 --> $t1]: $textSegment\n")
                } else {
                    append(textSegment)
                }
            }
        }.trim()
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create Whisper context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create Whisper context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create Whisper context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                val cpuInfo = cpuInfo()
                if (cpuInfo != null && cpuInfo.contains("vfpv4")) {
                    loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                val cpuInfo = cpuInfo()
                if (cpuInfo != null && cpuInfo.contains("fphp")) {
                    loadV8fp16 = true
                }
            }

            try {
                if (loadVfpv4) {
                    System.loadLibrary("whisper_vfpv4")
                } else if (loadV8fp16) {
                    System.loadLibrary("whisper_v8fp16_va")
                } else {
                    System.loadLibrary("whisper")
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(LOG_TAG, "Fallback to default libwhisper: ${e.message}")
                System.loadLibrary("whisper")
            }
        }

        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS.isNotEmpty() && Build.SUPPORTED_ABIS[0] == "armeabi-v7a"
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS.isNotEmpty() && Build.SUPPORTED_ABIS[0] == "arm64-v8a"
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }
}
