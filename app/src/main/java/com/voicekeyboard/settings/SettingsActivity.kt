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
    val downloadState by modelManager.downloadState.collectAsStateWithLifecycle()

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
            // Service Toggle
            SettingsSection(title = "Service") {
                SwitchSettingItem(
                    title = stringResource(R.string.setting_service_enabled),
                    subtitle = if (serviceEnabled) "Floating mic is active" else "Tap to enable",
                    icon = Icons.Default.Mic,
                    checked = serviceEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setServiceEnabled(enabled)
                            if (enabled) onStartService() else onStopService()
                        }
                    }
                )
            }

            // Permissions Section
            SettingsSection(title = "Permissions") {
                PermissionItem(
                    title = stringResource(R.string.permission_mic_title),
                    subtitle = if (hasMicPermission.value) "Granted" else "Required for voice input",
                    icon = Icons.Default.Mic,
                    isGranted = hasMicPermission.value,
                    onClick = { if (!hasMicPermission.value) onRequestMicPermission() }
                )

                PermissionItem(
                    title = stringResource(R.string.setting_overlay),
                    subtitle = if (hasOverlayPermission.value) "Granted" else stringResource(R.string.setting_overlay_desc),
                    icon = Icons.Default.Layers,
                    isGranted = hasOverlayPermission.value,
                    onClick = { if (!hasOverlayPermission.value) onRequestOverlayPermission() }
                )

                PermissionItem(
                    title = stringResource(R.string.setting_accessibility),
                    subtitle = if (hasAccessibilityEnabled.value) "Enabled" else stringResource(R.string.setting_accessibility_desc),
                    icon = Icons.Default.Accessibility,
                    isGranted = hasAccessibilityEnabled.value,
                    onClick = { onOpenAccessibilitySettings() }
                )
            }

            // Model Section
            SettingsSection(title = "Speech Model") {
                ModelSettingItem(
                    downloadState = downloadState,
                    onDownload = {
                        scope.launch {
                            modelManager.downloadModel()
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
    onDownload: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_model)) },
        supportingContent = {
            when (downloadState) {
                is ModelManager.DownloadState.NotStarted -> Text("Parakeet v3 (~600 MB)")
                is ModelManager.DownloadState.Downloading -> Text("Downloading: ${downloadState.progress}%")
                is ModelManager.DownloadState.Extracting -> Text("Extracting...")
                is ModelManager.DownloadState.Ready -> Text(stringResource(R.string.setting_model_ready))
                is ModelManager.DownloadState.Error -> Text("Error: ${downloadState.message}")
            }
        },
        leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
        trailingContent = {
            when (downloadState) {
                is ModelManager.DownloadState.NotStarted,
                is ModelManager.DownloadState.Error -> {
                    Button(onClick = onDownload) {
                        Text(stringResource(R.string.action_download))
                    }
                }
                is ModelManager.DownloadState.Downloading -> {
                    CircularProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.size(24.dp)
                    )
                }
                is ModelManager.DownloadState.Extracting -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                is ModelManager.DownloadState.Ready -> {
                    Icon(Icons.Default.Check, "Ready", tint = MaterialTheme.colorScheme.primary)
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
