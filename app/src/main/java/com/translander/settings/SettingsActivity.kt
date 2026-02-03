package com.translander.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.translander.R
import com.translander.TranslanderApp
import com.translander.asr.DictionaryManager
import com.translander.asr.ModelManager
import com.translander.service.FloatingMicService
import com.translander.service.TextInjectionService
import com.translander.transcribe.TranscribeManager
import android.content.ActivityNotFoundException
import android.widget.Toast
import com.translander.ui.theme.TranslanderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val refreshTrigger = mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Just refresh UI - don't auto-start service
    }

    private var onModelFolderSelected: ((Uri) -> Unit)? = null

    private val modelFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { onModelFolderSelected?.invoke(it) }
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
                    onStartService = {
                        requestNotificationPermissionIfNeeded()
                        startFloatingService()
                    },
                    onStopService = { stopFloatingService() },
                    onStartAudioMonitor = { requestNotificationPermissionIfNeeded() },
                    onRestartService = { restartFloatingService() },
                    onPickFolder = { callback -> openFolderPicker(callback) },
                    onPickModelFolder = { callback ->
                        onModelFolderSelected = callback
                        modelFolderPickerLauncher.launch(null)
                    },
                    isVoiceImeEnabled = { isVoiceImeEnabled() },
                    onOpenInputMethodSettings = { openInputMethodSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger permission state refresh in Compose
        refreshTrigger.value++

        // Revalidate model files on disk
        TranslanderApp.instance.modelManager.checkModelStatus()

        // Sync floating service state and restart services if needed (Android 14+ boot workaround)
        val app = TranslanderApp.instance
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

            // Restart audio monitor if enabled (needed on Android 14+ where boot start is blocked)
            val audioMonitorEnabled = app.settingsRepository.audioMonitorEnabled.first()
            if (audioMonitorEnabled) {
                app.transcribeManager.setAudioMonitorEnabled(true)
            }

            // Dismiss the boot notification now that services are started
            app.serviceAlertNotification.dismiss()
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

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before Android 13
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun isVoiceImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        return enabledMethods.any { it.packageName == packageName }
    }

    private fun openInputMethodSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        startActivity(intent)
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
    onStartAudioMonitor: () -> Unit,
    onPickFolder: ((String) -> Unit) -> Unit,
    onPickModelFolder: ((Uri) -> Unit) -> Unit,
    isVoiceImeEnabled: () -> Boolean,
    onOpenInputMethodSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = TranslanderApp.instance.settingsRepository
    val modelManager = TranslanderApp.instance.modelManager

    val serviceEnabled by settingsRepository.serviceEnabled.collectAsStateWithLifecycle(initialValue = false)
    val preferredLanguage by settingsRepository.preferredLanguage.collectAsStateWithLifecycle(initialValue = SettingsRepository.LANGUAGE_AUTO)
    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)
    val autoLoadModel by settingsRepository.autoLoadModel.collectAsStateWithLifecycle(initialValue = false)
    val downloadState by modelManager.downloadState.collectAsStateWithLifecycle()
    val recognizerManager = TranslanderApp.instance.recognizerManager
    val isRecognizerReady by recognizerManager.isReady.collectAsStateWithLifecycle()
    val isRecognizerLoading by recognizerManager.isLoading.collectAsStateWithLifecycle()
    val dictionaryManager = TranslanderApp.instance.dictionaryManager
    val dictionaryEnabled by settingsRepository.dictionaryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val replacementRules by dictionaryManager.rules.collectAsStateWithLifecycle()
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    val audioMonitorEnabled by settingsRepository.audioMonitorEnabled.collectAsStateWithLifecycle(initialValue = false)
    val monitoredFolders by settingsRepository.monitoredFolders.collectAsStateWithLifecycle(initialValue = emptySet())
    val floatingButtonSize by settingsRepository.floatingButtonSize.collectAsStateWithLifecycle(initialValue = SettingsRepository.BUTTON_SIZE_MEDIUM)
    val transcribeManager = TranslanderApp.instance.transcribeManager

    val hasMicPermission = remember { mutableStateOf(false) }
    val hasOverlayPermission = remember { mutableStateOf(false) }
    val hasAccessibilityEnabled = remember { mutableStateOf(false) }
    val hasAudioPermission = remember { mutableStateOf(false) }
    val hasVoiceImeEnabled = remember { mutableStateOf(false) }

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

        hasVoiceImeEnabled.value = isVoiceImeEnabled()
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
            // Speech Model (required for all voice input)
            SettingsSection(title = stringResource(R.string.section_speech_model)) {
                ModelSettingItem(
                    downloadState = downloadState,
                    isRecognizerReady = isRecognizerReady,
                    isRecognizerLoading = isRecognizerLoading,
                    onDownload = {
                        scope.launch {
                            modelManager.downloadModel()
                        }
                    },
                    onLoadLocal = {
                        onPickModelFolder { uri ->
                            scope.launch {
                                modelManager.importFromFolder(uri)
                            }
                        }
                    },
                    onLoadModel = {
                        modelManager.checkModelStatus()
                        if (modelManager.isModelReady()) {
                            scope.launch {
                                recognizerManager.initialize()
                            }
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

                val context = LocalContext.current
                Text(
                    text = stringResource(R.string.model_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3")))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
                            }
                        }
                )

                SwitchSettingItem(
                    title = stringResource(R.string.model_auto_load_title),
                    subtitle = if (autoLoadModel) stringResource(R.string.model_auto_load_on) else stringResource(R.string.model_auto_load_off),
                    icon = Icons.Default.Speed,
                    checked = autoLoadModel,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setAutoLoadModel(enabled)
                        }
                    }
                )

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

            // Microphone Permission (required for all voice input)
            SettingsSection(title = stringResource(R.string.section_microphone)) {
                PermissionItem(
                    title = stringResource(R.string.permission_mic_title),
                    subtitle = if (hasMicPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_voice),
                    icon = Icons.Default.Mic,
                    isGranted = hasMicPermission.value,
                    onClick = { if (hasMicPermission.value) onOpenAppSettings() else onRequestMicPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )
            }

            // Keyboard Integration Section
            SettingsSection(title = stringResource(R.string.section_keyboard)) {
                PermissionItem(
                    title = stringResource(R.string.keyboard_voice_ime),
                    subtitle = if (hasVoiceImeEnabled.value)
                        stringResource(R.string.keyboard_ime_enabled)
                    else stringResource(R.string.keyboard_ime_disabled),
                    icon = Icons.Default.Keyboard,
                    isGranted = hasVoiceImeEnabled.value,
                    onClick = { onOpenInputMethodSettings() },
                    onRevokeClick = { onOpenInputMethodSettings() }
                )
            }

            // Accessibility Service
            SettingsSection(title = stringResource(R.string.section_accessibility)) {
                PermissionItem(
                    title = stringResource(R.string.accessibility_text_injection),
                    subtitle = if (hasAccessibilityEnabled.value)
                        stringResource(R.string.accessibility_enabled)
                        else stringResource(R.string.accessibility_disabled),
                    icon = Icons.Default.Accessibility,
                    isGranted = hasAccessibilityEnabled.value,
                    onClick = {
                        if (hasAccessibilityEnabled.value) {
                            onOpenAccessibilitySettings()
                        } else {
                            showAccessibilityDisclosure = true
                        }
                    },
                    onRevokeClick = { onOpenAccessibilitySettings() }
                )
            }

            // Accessibility Disclosure Dialog
            if (showAccessibilityDisclosure) {
                AlertDialog(
                    onDismissRequest = { showAccessibilityDisclosure = false },
                    icon = { Icon(Icons.Default.Accessibility, contentDescription = null) },
                    title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(stringResource(R.string.accessibility_disclosure_body))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAccessibilityDisclosure = false
                            onOpenAccessibilitySettings()
                        }) {
                            Text(stringResource(R.string.accessibility_disclosure_agree))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAccessibilityDisclosure = false }) {
                            Text(stringResource(R.string.accessibility_disclosure_decline))
                        }
                    }
                )
            }

            // Optional Floating Mic Button
            SettingsSection(title = stringResource(R.string.section_floating_mic)) {
                SwitchSettingItem(
                    title = stringResource(R.string.floating_enable),
                    subtitle = when {
                        !hasMicPermission.value -> stringResource(R.string.floating_grant_mic_first)
                        !hasOverlayPermission.value -> stringResource(R.string.floating_grant_overlay_first)
                        !isRecognizerReady -> stringResource(R.string.floating_load_model_first)
                        serviceEnabled -> stringResource(R.string.floating_shows_red)
                        else -> stringResource(R.string.floating_additional)
                    },
                    icon = Icons.Default.RadioButtonChecked,
                    checked = serviceEnabled,
                    enabled = hasMicPermission.value && hasOverlayPermission.value && isRecognizerReady,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setServiceEnabled(enabled)
                            if (enabled) onStartService() else onStopService()
                        }
                    }
                )

                PermissionItem(
                    title = stringResource(R.string.setting_overlay),
                    subtitle = if (hasOverlayPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_overlay),
                    icon = Icons.Default.Layers,
                    isGranted = hasOverlayPermission.value,
                    onClick = { onRequestOverlayPermission() },
                    onRevokeClick = { onRequestOverlayPermission() }
                )

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

            // Transcription Section
            SettingsSection(title = stringResource(R.string.section_transcription)) {
                PermissionItem(
                    title = stringResource(R.string.transcription_audio_access),
                    subtitle = if (hasAudioPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_audio),
                    icon = Icons.Default.AudioFile,
                    isGranted = hasAudioPermission.value,
                    onClick = { if (hasAudioPermission.value) onOpenAppSettings() else onRequestAudioFilesPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )

                SwitchSettingItem(
                    title = stringResource(R.string.transcription_monitor_folders),
                    subtitle = when {
                        !hasAudioPermission.value -> stringResource(R.string.transcription_grant_audio_first)
                        audioMonitorEnabled -> {
                            val count = monitoredFolders.size
                            if (count > 0) stringResource(R.string.transcription_watching_folders, count)
                            else stringResource(R.string.transcription_watching_downloads)
                        }
                        else -> stringResource(R.string.transcription_notify_downloaded)
                    },
                    icon = Icons.Default.FolderOpen,
                    checked = audioMonitorEnabled,
                    enabled = hasAudioPermission.value,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            if (enabled) onStartAudioMonitor()
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
                                            // Restart service to stop watching removed folder
                                            if (audioMonitorEnabled) {
                                                transcribeManager.setAudioMonitorEnabled(false)
                                                transcribeManager.setAudioMonitorEnabled(true)
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(R.string.transcription_default_folder),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }

                    // Add folder button
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.transcription_add_folder)) },
                        supportingContent = { Text(stringResource(R.string.transcription_add_folder_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onPickFolder { path ->
                                scope.launch {
                                    // If adding first custom folder, include default Downloads
                                    val baseFolders = monitoredFolders.ifEmpty {
                                        setOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                                    }
                                    val newFolders = baseFolders + path
                                    settingsRepository.setMonitoredFolders(newFolders)
                                    // Restart service to pick up new folder
                                    if (audioMonitorEnabled) {
                                        transcribeManager.setAudioMonitorEnabled(false)
                                        transcribeManager.setAudioMonitorEnabled(true)
                                    }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text(stringResource(R.string.transcription_share_open)) },
                    supportingContent = { Text(stringResource(R.string.transcription_share_open_desc)) },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) }
                )
            }

            // Word Corrections Section
            SettingsSection(title = stringResource(R.string.section_word_corrections)) {
                SwitchSettingItem(
                    title = stringResource(R.string.corrections_enable),
                    subtitle = if (dictionaryEnabled) stringResource(R.string.corrections_enabled_desc) else stringResource(R.string.corrections_disabled_desc),
                    icon = Icons.Default.Spellcheck,
                    checked = dictionaryEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setDictionaryEnabled(enabled)
                        }
                    }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.corrections_manage)) },
                    supportingContent = { Text(stringResource(R.string.corrections_count, replacementRules.size)) },
                    leadingContent = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.corrections_manage))
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
            SettingsSection(title = stringResource(R.string.section_appearance)) {
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
                Icon(Icons.Default.Check, stringResource(R.string.permission_granted_manage), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.action_grant_permission), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onLoadLocal: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column {
        ListItem(
            headlineContent = { Text("Parakeet TDT v3") },
            supportingContent = {
                when {
                    downloadState is ModelManager.DownloadState.NotStarted -> Text(stringResource(R.string.model_not_downloaded) + "\n" + stringResource(R.string.model_size))
                    downloadState is ModelManager.DownloadState.Downloading -> Text(stringResource(R.string.model_downloading, downloadState.progress))
                    downloadState is ModelManager.DownloadState.Copying -> Text(stringResource(R.string.model_copying, downloadState.progress))
                    downloadState is ModelManager.DownloadState.Extracting -> Text(stringResource(R.string.model_extracting))
                    downloadState is ModelManager.DownloadState.Error -> Text(
                        when (downloadState.type) {
                            ModelManager.ErrorType.NETWORK -> stringResource(R.string.model_error_network)
                            ModelManager.ErrorType.CHECKSUM_MISMATCH -> stringResource(R.string.model_error_checksum)
                            ModelManager.ErrorType.MISSING_FILE -> stringResource(R.string.model_error_missing_file, downloadState.details ?: "")
                            ModelManager.ErrorType.FOLDER_ACCESS -> stringResource(R.string.model_error_folder_access)
                            ModelManager.ErrorType.STORAGE -> stringResource(R.string.model_error_storage)
                            ModelManager.ErrorType.UNKNOWN -> stringResource(R.string.model_error, downloadState.details ?: "")
                        }
                    )
                    isRecognizerLoading -> Text(stringResource(R.string.model_loading))
                    isRecognizerReady -> Text(stringResource(R.string.model_loaded))
                    downloadState is ModelManager.DownloadState.Ready -> Text(stringResource(R.string.model_downloaded))
                }
            },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            trailingContent = {
                when {
                    downloadState is ModelManager.DownloadState.NotStarted ||
                    downloadState is ModelManager.DownloadState.Error -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Button(onClick = onDownload) {
                                Text(stringResource(R.string.action_download))
                            }
                            TextButton(onClick = { showImportDialog = true }) {
                                Text(stringResource(R.string.action_load_local))
                            }
                        }
                    }
                    downloadState is ModelManager.DownloadState.Downloading ||
                    downloadState is ModelManager.DownloadState.Copying -> {
                        val progress = when (downloadState) {
                            is ModelManager.DownloadState.Downloading -> downloadState.progress
                            is ModelManager.DownloadState.Copying -> downloadState.progress
                            else -> 0
                        }
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    downloadState is ModelManager.DownloadState.Extracting || isRecognizerLoading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    isRecognizerReady -> {
                        TextButton(onClick = onUnloadModel) {
                            Text(stringResource(R.string.model_unload))
                        }
                    }
                    downloadState is ModelManager.DownloadState.Ready -> {
                        Button(onClick = onLoadModel) {
                            Text(stringResource(R.string.model_load))
                        }
                    }
                }
            }
        )

    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.model_import_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.model_import_instructions))
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.model_import_link))))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.model_import_open_link))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    onLoadLocal()
                }) {
                    Text(stringResource(R.string.action_select_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
    val selectedLangName = languages.find { it.code == selectedLanguage }?.displayName ?: stringResource(R.string.setting_language_auto)

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
        SettingsRepository.THEME_SYSTEM to stringResource(R.string.setting_theme_system),
        SettingsRepository.THEME_LIGHT to stringResource(R.string.setting_theme_light),
        SettingsRepository.THEME_DARK to stringResource(R.string.setting_theme_dark)
    )
    val selectedThemeName = themes.find { it.first == selectedTheme }?.second ?: stringResource(R.string.setting_theme_system)

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
        SettingsRepository.BUTTON_SIZE_SMALL to stringResource(R.string.floating_size_small),
        SettingsRepository.BUTTON_SIZE_MEDIUM to stringResource(R.string.floating_size_medium),
        SettingsRepository.BUTTON_SIZE_LARGE to stringResource(R.string.floating_size_large)
    )
    val selectedSizeName = sizes.find { it.first == selectedSize }?.second ?: stringResource(R.string.floating_size_medium)

    ListItem(
        headlineContent = { Text(stringResource(R.string.floating_button_size)) },
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
        title = { Text(stringResource(R.string.corrections_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Add new rule form
                Text(
                    stringResource(R.string.corrections_add_new),
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
                        label = { Text(stringResource(R.string.corrections_from)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text(stringResource(R.string.corrections_to)) },
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
                        Icon(Icons.Default.Add, stringResource(R.string.action_add))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Existing rules list
                Text(
                    stringResource(R.string.corrections_current_rules, rules.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (rules.isEmpty()) {
                    Text(
                        stringResource(R.string.corrections_no_rules),
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
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
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
                                            contentDescription = stringResource(R.string.action_remove),
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
                Text(stringResource(R.string.corrections_done))
            }
        }
    )
}
