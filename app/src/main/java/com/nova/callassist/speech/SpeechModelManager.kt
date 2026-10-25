package com.nova.callassist.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the offline OpenAI Whisper model lifecycle.
 * Models are stored locally in application storage and never streamed.
 * Supports selectable models (Tiny English ~31MB, Base English ~57MB).
 */
class SpeechModelManager(
    private val context: Context,
    val speechEngine: SpeechRecognitionEngine = WhisperSpeechEngine()
) {

    companion object {
        private const val TAG = "SpeechModelManager"

        const val DEFAULT_MODEL_NAME = "ggml-tiny.en-q5_1.bin"
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
        const val DEFAULT_MODEL_SIZE = 32166155L
    }

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(
            val modelPath: String,
            val modelName: String,
            val modelOption: WhisperModelOption,
            val fileSizeBytes: Long,
            val loadDurationMs: Long
        ) : ModelStatus()
        data class NotInstalled(
            val targetOption: WhisperModelOption = WhisperModelOption.DEFAULT
        ) : ModelStatus()
        data class Downloading(
            val targetOption: WhisperModelOption,
            val progressPercent: Int,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var activeModelOption: WhisperModelOption = WhisperModelOption.DEFAULT
    private var loadedModelPath: String? = null
    private var downloadJob: Job? = null
    private val isDownloading = AtomicBoolean(false)

    /**
     * Returns whether the given model option is downloaded and present on-device.
     */
    fun isModelInstalled(option: WhisperModelOption): Boolean {
        val file = getModelFile(option)
        return file.exists() && file.length() >= (option.expectedSizeBytes * 0.90)
    }

    fun getModelFile(option: WhisperModelOption): File {
        val targetDir = File(context.filesDir, "whisper-model").apply { mkdirs() }
        return File(targetDir, option.fileName)
    }

    /**
     * Initializes and verifies the local offline Whisper model.
     * Prefers preferredModelId if specified and installed; falls back to any installed model.
     */
    suspend fun initModel(preferredModelId: String? = null): ModelStatus = withContext(Dispatchers.IO) {
        val preferredOption = preferredModelId?.let { WhisperModelOption.fromId(it) } ?: activeModelOption

        if (speechEngine.isInitialized && loadedModelPath != null) {
            val file = File(loadedModelPath!!)
            val currentOption = WhisperModelOption.fromFileName(file.name) ?: preferredOption
            if (currentOption == preferredOption && file.exists()) {
                val ready = ModelStatus.Ready(
                    modelPath = loadedModelPath!!,
                    modelName = file.name,
                    modelOption = currentOption,
                    fileSizeBytes = file.length(),
                    loadDurationMs = 0L
                )
                _status.value = ready
                return@withContext ready
            }
        }

        _status.value = ModelStatus.Checking

        // 1. Check if preferred model is installed
        if (isModelInstalled(preferredOption)) {
            activeModelOption = preferredOption
            return@withContext loadModel(getModelFile(preferredOption), preferredOption)
        }

        // 2. Check if any other supported model is installed
        for (option in WhisperModelOption.values()) {
            if (isModelInstalled(option)) {
                activeModelOption = option
                return@withContext loadModel(getModelFile(option), option)
            }
        }

        // 3. Check assets for packaged model
        val assetModel = unpackFromAssetsIfPresent()
        if (assetModel != null) {
            val option = WhisperModelOption.fromFileName(assetModel.name) ?: WhisperModelOption.DEFAULT
            activeModelOption = option
            return@withContext loadModel(assetModel, option)
        }

        // 4. Model not yet downloaded
        val notInstalled = ModelStatus.NotInstalled(targetOption = preferredOption)
        _status.value = notInstalled
        return@withContext notInstalled
    }

    /**
     * Safely selects and loads an already-installed model.
     */
    suspend fun selectAndLoadModel(option: WhisperModelOption): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isModelInstalled(option)) {
            val notInstalled = ModelStatus.NotInstalled(targetOption = option)
            _status.value = notInstalled
            return@withContext Result.failure(IllegalStateException("${option.displayName} is not yet downloaded."))
        }

        activeModelOption = option
        val file = getModelFile(option)
        val status = loadModel(file, option)
        if (status is ModelStatus.Ready) {
            Result.success(true)
        } else {
            Result.failure(IllegalStateException("Failed to load ${option.displayName}"))
        }
    }

    /**
     * Downloads and installs a specified Whisper model with atomic rename and validation.
     * Keeps previous model active if download or validation fails.
     */
    suspend fun downloadAndInstallModel(
        option: WhisperModelOption = activeModelOption
    ): Result<String> = withContext(Dispatchers.IO) {
        if (isDownloading.getAndSet(true)) {
            return@withContext Result.failure(IllegalStateException("A download is already in progress."))
        }

        val previousStatus = _status.value
        val targetFile = getModelFile(option)
        val tempFile = File(context.cacheDir, "temp_${option.fileName}")

        try {
            _status.value = ModelStatus.Downloading(
                targetOption = option,
                progressPercent = 0,
                bytesDownloaded = 0,
                totalBytes = option.expectedSizeBytes
            )

            if (tempFile.exists()) tempFile.delete()

            Log.i(TAG, "Downloading ${option.displayName} from ${option.downloadUrl}...")
            val url = URL(option.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25000
                readTimeout = 60000
                instanceFollowRedirects = true
            }

            val totalLength = conn.contentLengthLong.let { if (it > 0) it else option.expectedSizeBytes }

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
                            _status.value = ModelStatus.Downloading(
                                targetOption = option,
                                progressPercent = percent,
                                bytesDownloaded = totalRead,
                                totalBytes = totalLength
                            )
                        }
                    }
                }
            }

            // Validation
            if (tempFile.length() < (option.expectedSizeBytes * 0.90)) {
                val len = tempFile.length()
                tempFile.delete()
                throw IllegalStateException("Downloaded file is incomplete ($len / ${option.expectedSizeBytes} bytes).")
            }

            // Atomic activation
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                // Fallback copy if cross-device rename fails
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Model downloaded successfully to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            activeModelOption = option
            val loadResult = loadModel(targetFile, option)
            if (loadResult is ModelStatus.Ready) {
                Result.success(targetFile.absolutePath)
            } else {
                Result.failure(IllegalStateException("Downloaded model could not be loaded into Whisper engine."))
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            _status.value = previousStatus
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            val errorMsg = e.localizedMessage ?: "Failed to download ${option.displayName}"
            Log.e(TAG, "Download error: $errorMsg", e)
            _status.value = ModelStatus.Error(errorMsg)
            Result.failure(e)
        } finally {
            isDownloading.set(false)
        }
    }

    fun cancelDownload() {
        if (isDownloading.get()) {
            downloadJob?.cancel()
            downloadJob = null
            isDownloading.set(false)
            initModelInternal()
        }
    }

    private fun initModelInternal() {
        val file = loadedModelPath?.let { File(it) }
        if (speechEngine.isInitialized && file != null && file.exists()) {
            _status.value = ModelStatus.Ready(
                modelPath = file.absolutePath,
                modelName = file.name,
                modelOption = activeModelOption,
                fileSizeBytes = file.length(),
                loadDurationMs = 0L
            )
        } else {
            _status.value = ModelStatus.NotInstalled(targetOption = activeModelOption)
        }
    }

    private suspend fun loadModel(modelFile: File, option: WhisperModelOption): ModelStatus = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        val success = speechEngine.initialize(modelFile.absolutePath)
        val duration = SystemClock.elapsedRealtime() - start

        if (success) {
            loadedModelPath = modelFile.absolutePath
            activeModelOption = option
            val ready = ModelStatus.Ready(
                modelPath = modelFile.absolutePath,
                modelName = modelFile.name,
                modelOption = option,
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

    fun getActiveModelOption(): WhisperModelOption = activeModelOption

    fun isReady(): Boolean = speechEngine.isInitialized

    fun getLoadedModelPath(): String? = loadedModelPath

    fun release() {
        speechEngine.release()
        loadedModelPath = null
        _status.value = ModelStatus.NotInstalled(targetOption = activeModelOption)
    }
}
