package com.translander.transcribe

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.translander.R
import com.translander.TranslanderApp
import com.translander.settings.SettingsRepository
import com.translander.ui.theme.TranslanderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranscribeActivity : ComponentActivity() {

    companion object {
        const val ACTION_TRANSCRIBE = "at.webformat.translander.action.TRANSCRIBE"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_FILE_PATH = "file_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioUri = extractAudioUri(intent)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

        setContent {
            val settingsRepository = TranslanderApp.instance.settingsRepository
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.THEME_SYSTEM
            )

            val isDarkTheme = when (themeMode) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> null
            }

            TranslanderTheme(
                darkTheme = isDarkTheme ?: isSystemInDarkTheme()
            ) {
                TranscribeScreen(
                    audioUri = audioUri,
                    filePath = filePath,
                    onDismiss = { finish() },
                    onCopy = { text -> copyToClipboard(text) },
                    onShare = { text -> shareText(text) }
                )
            }
        }
    }

    private fun extractAudioUri(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            Intent.ACTION_VIEW -> {
                intent.data
            }
            ACTION_TRANSCRIBE -> {
                getParcelableExtraCompat(intent, EXTRA_AUDIO_URI, Uri::class.java)
                    ?: intent.data
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            intent.getParcelableExtra(name) as? T
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.transcribe_share_title)))
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss the "audio detected" notification when activity closes
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AudioMonitorService.AUDIO_DETECTED_NOTIFICATION_ID)
    }
}

sealed class TranscribeState {
    data object CheckingModel : TranscribeState()
    data class Decoding(val progress: Int) : TranscribeState()
    data object Transcribing : TranscribeState()
    data class Success(val text: String) : TranscribeState()
    data class Error(val message: String) : TranscribeState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(
    audioUri: Uri?,
    filePath: String? = null,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit
) {
    var state by remember { mutableStateOf<TranscribeState>(TranscribeState.CheckingModel) }
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    val app = TranslanderApp.instance
    val recognizerManager = app.recognizerManager
    val modelManager = app.modelManager
    val settingsRepository = app.settingsRepository

    // Start transcription when URI or file path is available
    LaunchedEffect(audioUri, filePath) {
        if (audioUri == null && filePath == null) {
            state = TranscribeState.Error("No audio file provided")
            return@LaunchedEffect
        }

        // Check if model is ready
        state = TranscribeState.CheckingModel
        if (!modelManager.isModelReady()) {
            state = TranscribeState.Error("Speech model not downloaded. Please download it in Settings first.")
            return@LaunchedEffect
        }

        // Initialize recognizer if needed (run blocking operations off main thread)
        val isReady = withContext(Dispatchers.IO) {
            recognizerManager.isReady.first()
        }
        if (!isReady) {
            recognizerManager.initialize()
            // Wait for initialization
            withContext(Dispatchers.IO) {
                recognizerManager.isReady.first { it }
            }
        }

        // Decode audio - prefer file path if available (from folder monitor)
        state = TranscribeState.Decoding(0)
        val decoder = AudioDecoder(app)
        val decodeResult = withContext(Dispatchers.IO) {
            if (filePath != null) {
                decoder.decode(filePath) { progress ->
                    state = TranscribeState.Decoding(progress)
                }
            } else {
                decoder.decode(audioUri!!) { progress ->
                    state = TranscribeState.Decoding(progress)
                }
            }
        }

        when (decodeResult) {
            is AudioDecoder.DecodingState.Error -> {
                state = TranscribeState.Error(decodeResult.message)
                return@LaunchedEffect
            }
            is AudioDecoder.DecodingState.Success -> {
                // Transcribe audio
                state = TranscribeState.Transcribing
                val language = withContext(Dispatchers.IO) {
                    settingsRepository.preferredLanguage.first()
                        .takeIf { it != SettingsRepository.LANGUAGE_AUTO }
                }

                val result = withContext(Dispatchers.IO) {
                    recognizerManager.transcribe(decodeResult.audioData, language)
                }
                if (result != null) {
                    state = TranscribeState.Success(result)
                } else {
                    state = TranscribeState.Error("Transcription failed")
                }
            }
            else -> {
                state = TranscribeState.Error("Unexpected decoding state")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.transcribe_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Content based on state
                    when (val currentState = state) {
                        is TranscribeState.CheckingModel -> {
                            LoadingContent(stringResource(R.string.transcribe_checking_model))
                        }
                        is TranscribeState.Decoding -> {
                            LoadingContent(
                                stringResource(R.string.transcribe_decoding, currentState.progress)
                            )
                            LinearProgressIndicator(
                                progress = { currentState.progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                        is TranscribeState.Transcribing -> {
                            LoadingContent(stringResource(R.string.transcribe_transcribing))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                        is TranscribeState.Success -> {
                            SuccessContent(
                                text = currentState.text,
                                onCopy = {
                                    onCopy(currentState.text)
                                    showCopiedSnackbar = true
                                },
                                onShare = { onShare(currentState.text) }
                            )
                        }
                        is TranscribeState.Error -> {
                            ErrorContent(
                                message = currentState.message,
                                onDismiss = onDismiss
                            )
                        }
                    }
                }
            }

            // Snackbar for copy confirmation
            if (showCopiedSnackbar) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showCopiedSnackbar = false
                }
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.transcribe_copied))
                }
            }
        }
    }
}

@Composable
fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SuccessContent(
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Transcription result
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 300.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = onCopy
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.transcribe_copy))
            }

            Button(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.transcribe_share))
            }
        }
    }
}

@Composable
fun ErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onDismiss) {
            Text(stringResource(R.string.transcribe_close))
        }
    }
}
