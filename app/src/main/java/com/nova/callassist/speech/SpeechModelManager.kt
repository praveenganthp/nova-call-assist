package com.nova.callassist.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the offline Vosk speech recognition model lifecycle.
 * Models are stored locally in application storage and never streamed.
 * Supports one-tap in-app download for non-technical users.
 */
class SpeechModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(val modelPath: String, val model: Model) : ModelStatus()
        object NotInstalled : ModelStatus()
        data class Downloading(
            val progressPercent: Int,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : ModelStatus()
        object Unpacking : ModelStatus()
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

        // 1. Check internal files dir
        val internalModelDir = File(context.filesDir, "vosk-model")
        val foundInternal = findAcousticModelDir(internalModelDir)
        if (foundInternal != null) {
            return@withContext loadModelFromDirectory(foundInternal)
        }

        // 2. Check external files dir
        val externalModelDir = File(context.getExternalFilesDir(null), "model")
        val foundExternal = findAcousticModelDir(externalModelDir)
        if (foundExternal != null) {
            return@withContext loadModelFromDirectory(foundExternal)
        }

        // 3. Check if packaged in assets
        val hasAssetModel = try {
            val list = context.assets.list("model")
            !list.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }

        if (hasAssetModel) {
            _status.value = ModelStatus.Unpacking
            return@withContext unpackFromAssets("model", "vosk-model")
        }

        // 4. Model not yet present
        val notInstalled = ModelStatus.NotInstalled
        _status.value = notInstalled
        return@withContext notInstalled
    }

    /**
     * One-tap automated download and install of the small offline English model.
     * Makes setup effortless for non-technical users without needing a PC or ADB.
     */
    suspend fun downloadAndInstallModel(
        modelUrl: String = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    ): Result<Model> = withContext(Dispatchers.IO) {
        try {
            _status.value = ModelStatus.Downloading(0, 0, 41205931L)
            val targetZip = File(context.cacheDir, "vosk_model_temp.zip")
            if (targetZip.exists()) targetZip.delete()

            val url = URL(modelUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 40000
                instanceFollowRedirects = true
            }
            val totalLength = conn.contentLengthLong.let { if (it > 0) it else 41205931L }

            conn.inputStream.use { input ->
                FileOutputStream(targetZip).use { output ->
                    val buffer = ByteArray(16384)
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

            // Unpack zip file
            _status.value = ModelStatus.Unpacking
            val targetDir = File(context.filesDir, "vosk-model")
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            unzip(targetZip, targetDir)
            targetZip.delete()

            val modelDir = findAcousticModelDir(targetDir) ?: targetDir
            val model = Model(modelDir.absolutePath)
            loadedModel = model
            modelDirectoryPath = modelDir.absolutePath
            _status.value = ModelStatus.Ready(modelDir.absolutePath, model)
            Result.success(model)
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Failed to download model"
            _status.value = ModelStatus.Error(errorMsg)
            Result.failure(e)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val canonicalDest = targetDir.canonicalPath
            while (entry != null) {
                val entryFile = File(targetDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!entryFile.canonicalPath.startsWith(canonicalDest)) {
                    throw SecurityException("Zip entry is outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { fos ->
                        val buffer = ByteArray(16384)
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findAcousticModelDir(baseDir: File): File? {
        if (!baseDir.exists() || !baseDir.isDirectory) return null
        if (File(baseDir, "am").isDirectory || File(baseDir, "conf").isDirectory) {
            return baseDir
        }
        baseDir.listFiles()?.forEach { sub ->
            if (sub.isDirectory && (File(sub, "am").isDirectory || File(sub, "conf").isDirectory)) {
                return sub
            }
        }
        return null
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
            var resultStatus: ModelStatus = ModelStatus.Unpacking
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
