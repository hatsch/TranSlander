package com.voicekeyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.voicekeyboard.asr.ModelManager
import com.voicekeyboard.service.FloatingMicService
import com.voicekeyboard.service.TextInjectionService
import com.voicekeyboard.ui.theme.VoiceKeyboardTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkAndStartService()
        }
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
                    onRequestMicPermission = { requestMicPermission() },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onStartService = { startFloatingService() },
                    onStopService = { stopFloatingService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission states when returning from settings
    }

    private fun requestMicPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
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

    val hasMicPermission = remember { mutableStateOf(false) }
    val hasOverlayPermission = remember { mutableStateOf(false) }
    val hasAccessibilityEnabled = remember { mutableStateOf(false) }

    // Check permissions
    LaunchedEffect(Unit) {
        hasMicPermission.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasOverlayPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        hasAccessibilityEnabled.value = TextInjectionService.isEnabled()
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
                    onClick = { onOpenAccessibilitySettings() }
                )

                PermissionItem(
                    title = stringResource(R.string.permission_mic_title),
                    subtitle = if (hasMicPermission.value) "Granted" else "Required for voice input",
                    icon = Icons.Default.Mic,
                    isGranted = hasMicPermission.value,
                    onClick = { if (!hasMicPermission.value) onRequestMicPermission() }
                )
            }

            // Optional Floating Mic Button
            SettingsSection(title = "Floating Mic Button (Optional)") {
                SwitchSettingItem(
                    title = "Enable Floating Button",
                    subtitle = if (serviceEnabled) "Shows red when recording" else "Additional mic button overlay",
                    icon = Icons.Default.RadioButtonChecked,
                    checked = serviceEnabled,
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
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
fun PermissionItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            if (isGranted) {
                Icon(Icons.Default.Check, "Granted", tint = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onClick) {
                    Text("Grant")
                }
            }
        },
        modifier = Modifier.clickable(enabled = !isGranted, onClick = onClick)
    )
}

@Composable
fun ModelSettingItem(
    downloadState: ModelManager.DownloadState,
    isRecognizerReady: Boolean,
    isRecognizerLoading: Boolean,
    onDownload: () -> Unit,
    onLoadModel: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_model)) },
        supportingContent = {
            when {
                downloadState is ModelManager.DownloadState.NotStarted -> Text("Parakeet v3 (~600 MB)")
                downloadState is ModelManager.DownloadState.Downloading -> Text("Downloading: ${downloadState.progress}%")
                downloadState is ModelManager.DownloadState.Extracting -> Text("Extracting...")
                downloadState is ModelManager.DownloadState.Error -> Text("Error: ${downloadState.message}")
                isRecognizerLoading -> Text("Loading model...")
                isRecognizerReady -> Text("Model loaded and ready")
                downloadState is ModelManager.DownloadState.Ready -> Text("Downloaded - tap Load to activate")
            }
        },
        leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
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
                    Icon(Icons.Default.Check, "Ready", tint = MaterialTheme.colorScheme.primary)
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
