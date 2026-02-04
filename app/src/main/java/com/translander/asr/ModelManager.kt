package com.translander.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

        // SHA256 checksums for integrity verification (from HuggingFace LFS pointer files)
        // These are verified against the file content after download
        private val FILE_CHECKSUMS = mapOf(
            "encoder.int8.onnx" to "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
            "decoder.int8.onnx" to "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
            "joiner.int8.onnx" to "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"
            // tokens.txt is not LFS-tracked, verified by file existence only
        )
    }

    enum class ErrorType {
        NETWORK,
        CHECKSUM_MISMATCH,
        MISSING_FILE,
        FOLDER_ACCESS,
        STORAGE,
        UNKNOWN
    }

    sealed class DownloadState {
        object NotStarted : DownloadState()
        data class Downloading(val progress: Int) : DownloadState()
        data class Copying(val progress: Int) : DownloadState()
        object Extracting : DownloadState()
        object Ready : DownloadState()
        data class Error(val type: ErrorType, val details: String? = null) : DownloadState()
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

    fun checkModelStatus() {
        _downloadState.value = if (isModelReady()) {
            DownloadState.Ready
        } else {
            DownloadState.NotStarted
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

                    // Verify checksum
                    val expectedChecksum = FILE_CHECKSUMS[remoteName]
                    if (expectedChecksum != null) {
                        val actualChecksum = calculateSha256(targetFile)
                        if (actualChecksum != expectedChecksum) {
                            throw SecurityException(
                                "Checksum mismatch for $remoteName: expected $expectedChecksum, got $actualChecksum"
                            )
                        }
                        Log.i(TAG, "Checksum verified for $remoteName")
                    }
                }

                if (isModelReady()) {
                    _downloadState.value = DownloadState.Ready
                    Log.i(TAG, "Model download complete")
                } else {
                    _downloadState.value = DownloadState.Error(ErrorType.STORAGE)
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Download failed: checksum mismatch", e)
                _downloadState.value = DownloadState.Error(ErrorType.CHECKSUM_MISMATCH)
                modelDir.deleteRecursively()
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Download failed: network error", e)
                _downloadState.value = DownloadState.Error(ErrorType.NETWORK)
                modelDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState.Error(ErrorType.UNKNOWN, e.message)
                modelDir.deleteRecursively()
            }
        }
    }

    private suspend fun downloadFile(url: String, targetFile: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Download failed: ${response.code}")
            }

            val body = response.body ?: throw java.io.IOException("Empty response body")
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
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
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Import model files from a user-selected local folder.
     * Accepts both original names (encoder.int8.onnx) and local names (encoder.onnx).
     */
    suspend fun importFromFolder(uri: Uri) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Ready
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _downloadState.value = DownloadState.Copying(0)

                val docFile = DocumentFile.fromTreeUri(context, uri)
                    ?: throw IllegalStateException("Cannot access selected folder")

                // Find each required model file in the selected folder
                val filesToCopy = mutableMapOf<DocumentFile, String>() // source -> local name
                for ((remoteName, localName) in MODEL_FILES) {
                    // Try remote name first (encoder.int8.onnx), then local name (encoder.onnx)
                    val sourceFile = docFile.findFile(remoteName)
                        ?: docFile.findFile(localName)
                        ?: throw java.io.FileNotFoundException(localName)
                    filesToCopy[sourceFile] = localName
                }

                modelDir.mkdirs()

                val progressPerFile = 100 / filesToCopy.size
                for ((index, entry) in filesToCopy.entries.withIndex()) {
                    val (sourceFile, localName) = entry
                    val targetFile = File(modelDir, localName)

                    Log.i(TAG, "Copying: ${sourceFile.name} -> $localName")

                    context.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            val fileSize = sourceFile.length()

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (fileSize > 0) {
                                    val fileProgress = ((totalBytesRead * 100) / fileSize).toInt()
                                    val overallProgress = (index * progressPerFile) + (fileProgress * progressPerFile / 100)
                                    _downloadState.value = DownloadState.Copying(overallProgress)
                                }
                            }
                        }
                    } ?: throw java.io.IOException("Cannot read file: ${sourceFile.name}")

                    // Verify checksum for ONNX files
                    val remoteName = MODEL_FILES.entries.find { it.value == localName }?.key
                    val expectedChecksum = remoteName?.let { FILE_CHECKSUMS[it] }
                    if (expectedChecksum != null) {
                        val actualChecksum = calculateSha256(targetFile)
                        if (actualChecksum != expectedChecksum) {
                            throw SecurityException(
                                "Checksum mismatch for $localName: expected $expectedChecksum, got $actualChecksum"
                            )
                        }
                        Log.i(TAG, "Checksum verified for $localName")
                    }
                }

                if (isModelReady()) {
                    _downloadState.value = DownloadState.Ready
                    Log.i(TAG, "Model import complete")
                } else {
                    _downloadState.value = DownloadState.Error(ErrorType.STORAGE)
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Import failed: checksum mismatch", e)
                _downloadState.value = DownloadState.Error(ErrorType.CHECKSUM_MISMATCH)
                modelDir.deleteRecursively()
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "Import failed: missing file", e)
                _downloadState.value = DownloadState.Error(ErrorType.MISSING_FILE, e.message)
                modelDir.deleteRecursively()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Import failed: folder access", e)
                _downloadState.value = DownloadState.Error(ErrorType.FOLDER_ACCESS)
                modelDir.deleteRecursively()
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Import failed: IO error", e)
                _downloadState.value = DownloadState.Error(ErrorType.STORAGE)
                modelDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _downloadState.value = DownloadState.Error(ErrorType.UNKNOWN, e.message)
                modelDir.deleteRecursively()
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
