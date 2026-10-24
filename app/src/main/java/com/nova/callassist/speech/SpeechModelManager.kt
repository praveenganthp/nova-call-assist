package com.nova.callassist.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the offline OpenAI Whisper model lifecycle.
 * Models are stored locally in application storage and never streamed.
 * Supports one-tap in-app download for non-technical users.
 */
class SpeechModelManager(
    private val context: Context,
    val speechEngine: SpeechRecognitionEngine = WhisperSpeechEngine()
) {

    companion object {
        private const val TAG = "SpeechModelManager"

        // Official fast quantized Whisper Tiny model (30.6 MB)
        const val DEFAULT_MODEL_NAME = "ggml-tiny.en-q5_1.bin"
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
        const val DEFAULT_MODEL_SIZE = 32166155L

        // Optional standard model fallback URL
        const val STANDARD_TINY_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
    }

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(
            val modelPath: String,
            val modelName: String,
            val fileSizeBytes: Long,
            val loadDurationMs: Long
        ) : ModelStatus()
        object NotInstalled : ModelStatus()
        data class Downloading(
            val progressPercent: Int,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var loadedModelPath: String? = null

    /**
     * Initializes and verifies the local offline Whisper model.
     */
    suspend fun initModel(): ModelStatus = withContext(Dispatchers.IO) {
        if (speechEngine.isInitialized && loadedModelPath != null) {
            val file = File(loadedModelPath!!)
            val ready = ModelStatus.Ready(
                modelPath = loadedModelPath!!,
                modelName = file.name,
                fileSizeBytes = file.length(),
                loadDurationMs = 0L
            )
            _status.value = ready
            return@withContext ready
        }

        _status.value = ModelStatus.Checking

        // 1. Check internal app files directory
        val internalDir = File(context.filesDir, "whisper-model")
        val foundInternal = findModelFile(internalDir)
        if (foundInternal != null) {
            return@withContext loadModel(foundInternal)
        }

        // 2. Check external files directory
        val externalDir = File(context.getExternalFilesDir(null), "whisper-model")
        val foundExternal = findModelFile(externalDir)
        if (foundExternal != null) {
            return@withContext loadModel(foundExternal)
        }

        // 3. Check assets for packaged model
        val assetModel = unpackFromAssetsIfPresent()
        if (assetModel != null) {
            return@withContext loadModel(assetModel)
        }

        // 4. Model not yet downloaded
        val notInstalled = ModelStatus.NotInstalled
        _status.value = notInstalled
        return@withContext notInstalled
    }

    /**
     * One-tap automated download and install of the lightweight quantized Whisper model.
     */
    suspend fun downloadAndInstallModel(
        modelUrl: String = DEFAULT_MODEL_URL,
        modelName: String = DEFAULT_MODEL_NAME
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            _status.value = ModelStatus.Downloading(0, 0, DEFAULT_MODEL_SIZE)
            val targetDir = File(context.filesDir, "whisper-model").apply { mkdirs() }
            val tempFile = File(context.cacheDir, "temp_$modelName")
            val targetFile = File(targetDir, modelName)

            if (tempFile.exists()) tempFile.delete()

            Log.i(TAG, "Downloading Whisper model from $modelUrl...")
            val url = URL(modelUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25000
                readTimeout = 60000
                instanceFollowRedirects = true
            }

            val totalLength = conn.contentLengthLong.let { if (it > 0) it else DEFAULT_MODEL_SIZE }

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val percent = if (totalLength > 0) ((totalRead * 100) / totalLength).toInt() else -1
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            _status.value = ModelStatus.Downloading(percent, totalRead, totalLength)
                        }
                    }
                }
            }

            if (tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("Downloaded model file is empty.")
            }

            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            Log.i(TAG, "Model downloaded successfully to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            val loadResult = loadModel(targetFile)
            if (loadResult is ModelStatus.Ready) {
                Result.success(targetFile.absolutePath)
            } else {
                Result.failure(IllegalStateException("Downloaded model could not be initialized."))
            }
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Failed to download Whisper model"
            Log.e(TAG, "Download error: $errorMsg", e)
            _status.value = ModelStatus.Error(errorMsg)
            Result.failure(e)
        }
    }

    private suspend fun loadModel(modelFile: File): ModelStatus = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        val success = speechEngine.initialize(modelFile.absolutePath)
        val duration = SystemClock.elapsedRealtime() - start

        if (success) {
            loadedModelPath = modelFile.absolutePath
            val ready = ModelStatus.Ready(
                modelPath = modelFile.absolutePath,
                modelName = modelFile.name,
                fileSizeBytes = modelFile.length(),
                loadDurationMs = duration
            )
            _status.value = ready
            ready
        } else {
            val err = ModelStatus.Error("Failed to initialize Whisper engine with ${modelFile.name}")
            _status.value = err
            err
        }
    }

    private fun findModelFile(dir: File): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val candidates = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".bin") && file.length() > 1000000L
        }
        return candidates?.maxByOrNull { it.lastModified() }
    }

    private fun unpackFromAssetsIfPresent(): File? {
        return try {
            val list = context.assets.list("models") ?: emptyArray()
            val modelName = list.firstOrNull { it.endsWith(".bin") } ?: return null
            val targetDir = File(context.filesDir, "whisper-model").apply { mkdirs() }
            val targetFile = File(targetDir, modelName)
            if (!targetFile.exists() || targetFile.length() == 0L) {
                context.assets.open("models/$modelName").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            targetFile
        } catch (e: Exception) {
            null
        }
    }

    fun isReady(): Boolean = speechEngine.isInitialized

    fun getLoadedModelPath(): String? = loadedModelPath

    fun release() {
        speechEngine.release()
        loadedModelPath = null
        _status.value = ModelStatus.NotInstalled
    }
}
