package com.voicekeyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicekeyboard.R
import com.voicekeyboard.VoiceKeyboardApp
import com.voicekeyboard.asr.DictionaryManager
import com.voicekeyboard.asr.ModelManager
import com.voicekeyboard.service.FloatingMicService
import com.voicekeyboard.service.TextInjectionService
import com.voicekeyboard.transcribe.TranscribeManager
import com.voicekeyboard.ui.theme.VoiceKeyboardTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val refreshTrigger = mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Just refresh UI - don't auto-start service
    }

    private var onFolderSelected: ((String) -> Unit)? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permission for the folder
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Convert to a displayable path
            val path = getPathFromUri(it)
            if (path != null) {
                onFolderSelected?.invoke(path)
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)

        // Handle primary storage paths
        if (docId.startsWith("primary:")) {
            val relativePath = docId.removePrefix("primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
        }

        // For other storage (SD card, etc.), try to extract path
        if (docId.contains(":")) {
            val parts = docId.split(":")
            if (parts.size == 2) {
                return "/storage/${parts[0]}/${parts[1]}"
            }
        }

        // Fallback to URI string
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun openFolderPicker(callback: (String) -> Unit) {
        onFolderSelected = callback
        folderPickerLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settingsRepository = VoiceKeyboardApp.instance.settingsRepository
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.THEME_SYSTEM
            )

            val isDarkTheme = when (themeMode) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> null
            }

            VoiceKeyboardTheme(
                darkTheme = isDarkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()
            ) {
                SettingsScreen(
                    refreshTrigger = refreshTrigger.value,
                    onRequestMicPermission = { requestMicPermission() },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestAudioFilesPermission = { requestAudioFilesPermission() },
                    hasAudioFilesPermission = { hasAudioFilesPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenAppSettings = { openAppSettings() },
                    onStartService = { startFloatingService() },
                    onStopService = { stopFloatingService() },
                    onRestartService = { restartFloatingService() },
                    onPickFolder = { callback -> openFolderPicker(callback) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger permission state refresh in Compose
        refreshTrigger.value++

        // Sync floating service state
        val app = VoiceKeyboardApp.instance
        app.applicationScope.launch {
            val serviceEnabled = app.settingsRepository.serviceEnabled.first()
            val recognizerReady = app.recognizerManager.isInitialized()
            val hasMic = hasMicPermission()
            val hasOverlay = hasOverlayPermission()

            if (serviceEnabled) {
                if (!recognizerReady || !hasMic || !hasOverlay) {
                    // Conditions no longer met - disable
                    app.settingsRepository.setServiceEnabled(false)
                    stopFloatingService()
                } else {
                    // Conditions met - ensure service is running
                    startFloatingService()
                }
            }
        }
    }

    private fun requestMicPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestAudioFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasAudioFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun checkAndStartService() {
        if (hasMicPermission() && hasOverlayPermission()) {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingMicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingMicService::class.java).apply {
            action = FloatingMicService.ACTION_STOP
        }
        startService(intent)
    }

    private fun restartFloatingService() {
        // Stop without ACTION_STOP so preference isn't cleared, then start
        stopService(Intent(this, FloatingMicService::class.java))
        startFloatingService()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    refreshTrigger: Int,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAudioFilesPermission: () -> Unit,
    hasAudioFilesPermission: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRestartService: () -> Unit,
    onPickFolder: ((String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = VoiceKeyboardApp.instance.settingsRepository
    val modelManager = VoiceKeyboardApp.instance.modelManager

    val serviceEnabled by settingsRepository.serviceEnabled.collectAsStateWithLifecycle(initialValue = false)
    val preferredLanguage by settingsRepository.preferredLanguage.collectAsStateWithLifecycle(initialValue = SettingsRepository.LANGUAGE_AUTO)
    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)
    val autoLoadModel by settingsRepository.autoLoadModel.collectAsStateWithLifecycle(initialValue = false)
    val downloadState by modelManager.downloadState.collectAsStateWithLifecycle()
    val recognizerManager = VoiceKeyboardApp.instance.recognizerManager
    val isRecognizerReady by recognizerManager.isReady.collectAsStateWithLifecycle()
    val isRecognizerLoading by recognizerManager.isLoading.collectAsStateWithLifecycle()
    val dictionaryManager = VoiceKeyboardApp.instance.dictionaryManager
    val dictionaryEnabled by settingsRepository.dictionaryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val replacementRules by dictionaryManager.rules.collectAsStateWithLifecycle()
    var showDictionaryDialog by remember { mutableStateOf(false) }

    val audioMonitorEnabled by settingsRepository.audioMonitorEnabled.collectAsStateWithLifecycle(initialValue = false)
    val monitoredFolders by settingsRepository.monitoredFolders.collectAsStateWithLifecycle(initialValue = emptySet())
    val floatingButtonSize by settingsRepository.floatingButtonSize.collectAsStateWithLifecycle(initialValue = SettingsRepository.BUTTON_SIZE_MEDIUM)
    val transcribeManager = VoiceKeyboardApp.instance.transcribeManager

    val hasMicPermission = remember { mutableStateOf(false) }
    val hasOverlayPermission = remember { mutableStateOf(false) }
    val hasAccessibilityEnabled = remember { mutableStateOf(false) }
    val hasAudioPermission = remember { mutableStateOf(false) }

    // Check permissions - refresh on every onResume via refreshTrigger
    LaunchedEffect(refreshTrigger) {
        hasMicPermission.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasOverlayPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        hasAccessibilityEnabled.value = TextInjectionService.isEnabled()

        hasAudioPermission.value = hasAudioFilesPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Accessibility Service (required)
            SettingsSection(title = "Accessibility Service") {
                PermissionItem(
                    title = "Voice Keyboard Service",
                    subtitle = if (hasAccessibilityEnabled.value)
                        "Enabled - Use accessibility button to record"
                        else "Enable in Settings to use voice input",
                    icon = Icons.Default.Accessibility,
                    isGranted = hasAccessibilityEnabled.value,
                    onClick = { onOpenAccessibilitySettings() },
                    onRevokeClick = { onOpenAccessibilitySettings() }
                )

                PermissionItem(
                    title = stringResource(R.string.permission_mic_title),
                    subtitle = if (hasMicPermission.value) "Granted - tap to manage" else "Required for voice input",
                    icon = Icons.Default.Mic,
                    isGranted = hasMicPermission.value,
                    onClick = { if (hasMicPermission.value) onOpenAppSettings() else onRequestMicPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )
            }

            // Optional Floating Mic Button
            SettingsSection(title = "Floating Mic Button (Optional)") {
                SwitchSettingItem(
                    title = "Enable Floating Button",
                    subtitle = when {
                        !hasMicPermission.value -> "Grant microphone permission first"
                        !isRecognizerReady -> "Load model first"
                        serviceEnabled -> "Shows red when recording"
                        else -> "Additional mic button overlay"
                    },
                    icon = Icons.Default.RadioButtonChecked,
                    checked = serviceEnabled,
                    enabled = hasMicPermission.value && isRecognizerReady,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setServiceEnabled(enabled)
                            if (enabled) onStartService() else onStopService()
                        }
                    }
                )

                if (!hasOverlayPermission.value) {
                    PermissionItem(
                        title = stringResource(R.string.setting_overlay),
                        subtitle = "Required for floating button",
                        icon = Icons.Default.Layers,
                        isGranted = false,
                        onClick = { onRequestOverlayPermission() }
                    )
                }

                ButtonSizeSettingItem(
                    selectedSize = floatingButtonSize,
                    onSizeSelected = { size ->
                        scope.launch {
                            settingsRepository.setFloatingButtonSize(size)
                            // Restart service to apply new size (without clearing preference)
                            if (serviceEnabled) {
                                onRestartService()
                            }
                        }
                    }
                )
            }

            // Model Section
            SettingsSection(title = "Speech Model") {
                ModelSettingItem(
                    downloadState = downloadState,
                    isRecognizerReady = isRecognizerReady,
                    isRecognizerLoading = isRecognizerLoading,
                    onDownload = {
                        scope.launch {
                            modelManager.downloadModel()
                        }
                    },
                    onLoadModel = {
                        scope.launch {
                            recognizerManager.initialize()
                        }
                    },
                    onUnloadModel = {
                        recognizerManager.release()
                        // Stop floating service when model is unloaded
                        if (serviceEnabled) {
                            scope.launch {
                                settingsRepository.setServiceEnabled(false)
                            }
                            onStopService()
                        }
                    }
                )

                SwitchSettingItem(
                    title = "Auto-load on startup",
                    subtitle = if (autoLoadModel) "Model loads when app starts" else "Load model manually",
                    icon = Icons.Default.Speed,
                    checked = autoLoadModel,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setAutoLoadModel(enabled)
                        }
                    }
                )
            }

            // Language Section
            SettingsSection(title = "Recognition") {
                LanguageSettingItem(
                    selectedLanguage = preferredLanguage,
                    languages = settingsRepository.getSupportedLanguages(),
                    onLanguageSelected = { lang ->
                        scope.launch {
                            settingsRepository.setPreferredLanguage(lang)
                        }
                    }
                )
            }

            // Transcription Section
            SettingsSection(title = "Voice Message Transcription") {
                PermissionItem(
                    title = "Audio Files Access",
                    subtitle = if (hasAudioPermission.value) "Granted - tap to manage" else "Required to read audio files",
                    icon = Icons.Default.AudioFile,
                    isGranted = hasAudioPermission.value,
                    onClick = { if (hasAudioPermission.value) onOpenAppSettings() else onRequestAudioFilesPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )

                SwitchSettingItem(
                    title = "Monitor Folders",
                    subtitle = when {
                        !hasAudioPermission.value -> "Grant audio files permission first"
                        audioMonitorEnabled -> "Watching ${monitoredFolders.size.takeIf { it > 0 } ?: "Downloads"} folder(s)"
                        else -> "Notify when voice messages are downloaded"
                    },
                    icon = Icons.Default.FolderOpen,
                    checked = audioMonitorEnabled,
                    enabled = hasAudioPermission.value,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setAudioMonitorEnabled(enabled)
                            transcribeManager.setAudioMonitorEnabled(enabled)
                        }
                    }
                )

                // Watched folders list
                if (audioMonitorEnabled || monitoredFolders.isNotEmpty()) {
                    val displayFolders = monitoredFolders.ifEmpty {
                        setOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                    }

                    displayFolders.forEach { folder ->
                        val folderName = folder.substringAfterLast("/")
                        val isDefault = monitoredFolders.isEmpty()
                        ListItem(
                            headlineContent = { Text(folderName) },
                            supportingContent = { Text(folder) },
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            trailingContent = {
                                if (!isDefault) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val newFolders = monitoredFolders - folder
                                            settingsRepository.setMonitoredFolders(newFolders)
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text(
                                        "Default",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }

                    // Add folder button
                    ListItem(
                        headlineContent = { Text("Add folder") },
                        supportingContent = { Text("Watch additional folders for voice messages") },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onPickFolder { path ->
                                scope.launch {
                                    val newFolders = monitoredFolders + path
                                    settingsRepository.setMonitoredFolders(newFolders)
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("Share or Open Audio") },
                    supportingContent = { Text("Use Share or Open With from other apps") },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) }
                )
            }

            // Word Corrections Section
            SettingsSection(title = "Word Corrections") {
                SwitchSettingItem(
                    title = "Enable Corrections",
                    subtitle = if (dictionaryEnabled) "Auto-correct recognized words" else "Corrections disabled",
                    icon = Icons.Default.Spellcheck,
                    checked = dictionaryEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setDictionaryEnabled(enabled)
                        }
                    }
                )

                ListItem(
                    headlineContent = { Text("Manage Corrections") },
                    supportingContent = { Text("${replacementRules.size} replacement rule(s)") },
                    leadingContent = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Manage")
                    },
                    modifier = Modifier.clickable { showDictionaryDialog = true }
                )
            }

            // Dictionary Dialog
            if (showDictionaryDialog) {
                DictionaryDialog(
                    rules = replacementRules,
                    onDismiss = { showDictionaryDialog = false },
                    onAddRule = { from, to ->
                        scope.launch {
                            dictionaryManager.addRule(from, to)
                        }
                    },
                    onRemoveRule = { from ->
                        scope.launch {
                            dictionaryManager.removeRule(from)
                        }
                    }
                )
            }

            // Appearance Section
            SettingsSection(title = "Appearance") {
                ThemeSettingItem(
                    selectedTheme = themeMode,
                    onThemeSelected = { theme ->
                        scope.launch {
                            settingsRepository.setThemeMode(theme)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SwitchSettingItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        supportingContent = { Text(subtitle, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    )
}

@Composable
fun PermissionItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isGranted: Boolean,
    onClick: () -> Unit,
    onRevokeClick: (() -> Unit)? = null
) {
    val actualClick = if (isGranted && onRevokeClick != null) onRevokeClick else onClick
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isGranted) {
                Icon(Icons.Default.Check, "Granted", tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ChevronRight, "Grant", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = actualClick)
    )
}

@Composable
fun ModelSettingItem(
    downloadState: ModelManager.DownloadState,
    isRecognizerReady: Boolean,
    isRecognizerLoading: Boolean,
    onDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit
) {
    Column {
        ListItem(
            headlineContent = { Text("Parakeet TDT v3") },
            supportingContent = {
                when {
                    downloadState is ModelManager.DownloadState.NotStarted -> Text("Not downloaded (~600 MB)")
                    downloadState is ModelManager.DownloadState.Downloading -> Text("Downloading: ${downloadState.progress}%")
                    downloadState is ModelManager.DownloadState.Extracting -> Text("Extracting...")
                    downloadState is ModelManager.DownloadState.Error -> Text("Error: ${downloadState.message}")
                    isRecognizerLoading -> Text("Loading model...")
                    isRecognizerReady -> Text("Loaded and ready")
                    downloadState is ModelManager.DownloadState.Ready -> Text("Downloaded - tap Load to activate")
                }
            },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            trailingContent = {
                when {
                    downloadState is ModelManager.DownloadState.NotStarted ||
                    downloadState is ModelManager.DownloadState.Error -> {
                        Button(onClick = onDownload) {
                            Text(stringResource(R.string.action_download))
                        }
                    }
                    downloadState is ModelManager.DownloadState.Downloading -> {
                        CircularProgressIndicator(
                            progress = { downloadState.progress / 100f },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    downloadState is ModelManager.DownloadState.Extracting || isRecognizerLoading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    isRecognizerReady -> {
                        TextButton(onClick = onUnloadModel) {
                            Text("Unload")
                        }
                    }
                    downloadState is ModelManager.DownloadState.Ready -> {
                        Button(onClick = onLoadModel) {
                            Text("Load")
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun LanguageSettingItem(
    selectedLanguage: String,
    languages: List<SettingsRepository.Language>,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLangName = languages.find { it.code == selectedLanguage }?.displayName ?: "Auto-detect"

    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_language)) },
        supportingContent = { Text(selectedLangName) },
        leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        languages.forEach { lang ->
            DropdownMenuItem(
                text = { Text(lang.displayName) },
                onClick = {
                    onLanguageSelected(lang.code)
                    expanded = false
                },
                leadingIcon = if (lang.code == selectedLanguage) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
fun ThemeSettingItem(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val themes = listOf(
        SettingsRepository.THEME_SYSTEM to "System",
        SettingsRepository.THEME_LIGHT to "Light",
        SettingsRepository.THEME_DARK to "Dark"
    )
    val selectedThemeName = themes.find { it.first == selectedTheme }?.second ?: "System"

    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_theme)) },
        supportingContent = { Text(selectedThemeName) },
        leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        themes.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    onThemeSelected(code)
                    expanded = false
                },
                leadingIcon = if (code == selectedTheme) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
fun ButtonSizeSettingItem(
    selectedSize: String,
    onSizeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sizes = listOf(
        SettingsRepository.BUTTON_SIZE_SMALL to "Small",
        SettingsRepository.BUTTON_SIZE_MEDIUM to "Medium",
        SettingsRepository.BUTTON_SIZE_LARGE to "Large"
    )
    val selectedSizeName = sizes.find { it.first == selectedSize }?.second ?: "Medium"

    ListItem(
        headlineContent = { Text("Button Size") },
        supportingContent = { Text(selectedSizeName) },
        leadingContent = { Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null) },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        sizes.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    onSizeSelected(code)
                    expanded = false
                },
                leadingIcon = if (code == selectedSize) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
fun DictionaryDialog(
    rules: List<DictionaryManager.ReplacementRule>,
    onDismiss: () -> Unit,
    onAddRule: (String, String) -> Unit,
    onRemoveRule: (String) -> Unit
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Word Corrections") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Add new rule form
                Text(
                    "Add new correction",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("To") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (fromText.isNotBlank() && toText.isNotBlank()) {
                                onAddRule(fromText.trim(), toText.trim())
                                fromText = ""
                                toText = ""
                            }
                        },
                        enabled = fromText.isNotBlank() && toText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, "Add")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Existing rules list
                Text(
                    "Current rules (${rules.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (rules.isEmpty()) {
                    Text(
                        "No correction rules yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        rules.forEach { rule ->
                            ListItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(rule.from)
                                        @Suppress("DEPRECATION")
                                        Icon(
                                            Icons.Default.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp)
                                                .size(16.dp)
                                        )
                                        Text(rule.to)
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { onRemoveRule(rule.from) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
