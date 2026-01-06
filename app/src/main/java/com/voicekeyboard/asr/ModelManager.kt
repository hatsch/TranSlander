package com.voicekeyboard.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_DIR_NAME = "parakeet-v3"

        // Official k2-fsa sherpa-onnx model repo
        private const val HF_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

        // Model files to download (remote name -> local name)
        private val MODEL_FILES = mapOf(
            "encoder.int8.onnx" to "encoder.onnx",
            "decoder.int8.onnx" to "decoder.onnx",
            "joiner.int8.onnx" to "joiner.onnx",
            "tokens.txt" to "tokens.txt"
        )
    }

    sealed class DownloadState {
        object NotStarted : DownloadState()
        data class Downloading(val progress: Int) : DownloadState()
        object Extracting : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        checkModelStatus()
    }

    private fun checkModelStatus() {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Ready
        }
    }

    fun isModelReady(): Boolean {
        if (!modelDir.exists()) return false

        return MODEL_FILES.values.all { localName ->
            File(modelDir, localName).exists()
        }
    }

    fun getModelPath(): String = modelDir.absolutePath

    suspend fun downloadModel(onProgress: (Int) -> Unit = {}) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Ready
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _downloadState.value = DownloadState.Downloading(0)

                // Create model directory
                modelDir.mkdirs()

                // Download each model file individually from HuggingFace
                val progressPerFile = 100 / MODEL_FILES.size

                for ((index, entry) in MODEL_FILES.entries.withIndex()) {
                    val (remoteName, localName) = entry
                    val url = "$HF_BASE_URL/$remoteName"
                    val targetFile = File(modelDir, localName)

                    Log.i(TAG, "Downloading: $remoteName -> $localName")

                    downloadFile(url, targetFile) { fileProgress ->
                        val overallProgress = (index * progressPerFile) + (fileProgress * progressPerFile / 100)
                        _downloadState.value = DownloadState.Downloading(overallProgress)
                        onProgress(overallProgress)
                    }
                }

                if (isModelReady()) {
                    _downloadState.value = DownloadState.Ready
                    Log.i(TAG, "Model download complete")
                } else {
                    _downloadState.value = DownloadState.Error("Model files incomplete")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
                // Clean up partial download
                modelDir.deleteRecursively()
            }
        }
    }

    private fun downloadFile(url: String, targetFile: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Download failed: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }
            }
        }
    }

    suspend fun deleteModel() {
        withContext(Dispatchers.IO) {
            modelDir.deleteRecursively()
            _downloadState.value = DownloadState.NotStarted
        }
    }

    fun getModelSize(): Long {
        if (!modelDir.exists()) return 0
        return modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getFormattedModelSize(): String {
        val size = getModelSize()
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "%.2f GB".format(size / (1024.0 * 1024 * 1024))
        }
    }
}
