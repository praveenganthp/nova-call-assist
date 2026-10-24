package com.nova.callassist.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File

/**
 * Manages the offline Vosk speech recognition model lifecycle.
 * Models are stored locally in application storage and never streamed.
 */
class SpeechModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(val modelPath: String, val model: Model) : ModelStatus()
        object NotInstalled : ModelStatus()
        data class Installing(val progressPercent: Int = -1) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var loadedModel: Model? = null
    private var modelDirectoryPath: String? = null

    /**
     * Initializes and verifies the local offline model.
     */
    suspend fun initModel(): ModelStatus = withContext(Dispatchers.IO) {
        if (loadedModel != null && modelDirectoryPath != null) {
            val ready = ModelStatus.Ready(modelDirectoryPath!!, loadedModel!!)
            _status.value = ready
            return@withContext ready
        }

        _status.value = ModelStatus.Checking

        // 1. Check if model already unpacked in internal files dir
        val internalModelDir = File(context.filesDir, "vosk-model")
        if (isValidModelDirectory(internalModelDir)) {
            return@withContext loadModelFromDirectory(internalModelDir)
        }

        // 2. Check external files dir
        val externalModelDir = File(context.getExternalFilesDir(null), "model")
        if (isValidModelDirectory(externalModelDir)) {
            return@withContext loadModelFromDirectory(externalModelDir)
        }

        // 3. Check if packaged in assets
        val hasAssetModel = try {
            val list = context.assets.list("model")
            !list.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }

        if (hasAssetModel) {
            _status.value = ModelStatus.Installing(-1)
            return@withContext unpackFromAssets("model", "vosk-model")
        }

        // 4. Model not yet present
        val notInstalled = ModelStatus.NotInstalled
        _status.value = notInstalled
        return@withContext notInstalled
    }

    private fun isValidModelDirectory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        // Vosk models contain am/final.mdl or similar acoustic model files
        val contents = dir.list() ?: return false
        return contents.isNotEmpty()
    }

    private fun loadModelFromDirectory(dir: File): ModelStatus {
        return try {
            val model = Model(dir.absolutePath)
            loadedModel = model
            modelDirectoryPath = dir.absolutePath
            val ready = ModelStatus.Ready(dir.absolutePath, model)
            _status.value = ready
            ready
        } catch (e: Exception) {
            val error = ModelStatus.Error("Failed to load offline model: ${e.message}")
            _status.value = error
            error
        }
    }

    private suspend fun unpackFromAssets(source: String, target: String): ModelStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            var resultStatus: ModelStatus = ModelStatus.Installing(-1)
            StorageService.unpack(
                context,
                source,
                target,
                { model ->
                    loadedModel = model
                    val path = File(context.filesDir, target).absolutePath
                    modelDirectoryPath = path
                    resultStatus = ModelStatus.Ready(path, model)
                    _status.value = resultStatus
                },
                { exception ->
                    resultStatus = ModelStatus.Error("Unpack failed: ${exception.message}")
                    _status.value = resultStatus
                }
            )
            resultStatus
        } catch (e: Exception) {
            val error = ModelStatus.Error("Model setup error: ${e.message}")
            _status.value = error
            error
        }
    }

    /**
     * Retrieves currently loaded model if ready.
     */
    fun getLoadedModel(): Model? = loadedModel

    /**
     * Releases the native Vosk model memory.
     */
    fun release() {
        try {
            loadedModel = null
            modelDirectoryPath = null
            _status.value = ModelStatus.NotInstalled
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
