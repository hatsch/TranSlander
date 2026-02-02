# TranSlander

A fully offline voice typing app for Android. Speak into your phone and text appears in any app — no internet required, no cloud processing, your voice data never leaves your device.

[<img src="assets/get-it-on-github.svg" alt="Get it on GitHub" height="60">](https://github.com/hatsch/translander/releases/latest)

> **Note:** This started as a fun project. I really hate typing on touch screens and I hate voice messages even more. Then I got to know Parakeet and found it quite handy. As I am not an Android developer, I played around with Claude Code and came to the point where I thought it might be useful for others as well. So here we are.

## Features

### Offline Speech Recognition
- Uses [Parakeet TDT v3](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) neural model (~600MB) running locally via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- Supports 25 languages with auto-detection
- No internet connection needed after model download

### Works in Any App
- Uses Android Accessibility API to inject text directly into any focused text field
- Falls back to clipboard if no text field is focused

### Multiple Input Methods
- **Accessibility Button** — System navigation bar button, works system-wide
- **Floating Mic Button** — Draggable overlay, always visible (optional)
- **Keyboard Mic Button** — Integrates with keyboards like HeliBoard

### Voice Message Transcription
- **Share** audio files from any app
- **Open with** audio files from file managers
- **Folder monitoring** — Watch Downloads or custom folders for new voice messages
- Automatic notifications when voice messages are detected
- Supports OPUS, AAC, OGG, M4A, MP3, WAV formats

**Example: Signal voice messages**
1. Open Translander Settings → Voice Message Transcription
2. Add watch folder: `Music/Signal` (where Signal saves voice messages)
3. Enable "Monitor Folders"
4. In Signal, long-press a voice message → Save
5. Transcription popup appears automatically

### Word Corrections
- Custom dictionary to fix recurring recognition errors
- Example: "Tamtam" → "Tamdam"
- Whole-word matching with case-insensitive option
- Manage rules easily in Settings

### Modern UI
- Material 3 design with Jetpack Compose
- Dark and Light theme support
- System theme auto-detection

### Keyboard Integration
Translander provides three APIs for voice input integration:

| Component | API | Use Case |
|-----------|-----|----------|
| `VoiceInputMethodService` | InputMethodService (voice IME) | Keyboard mic buttons (HeliBoard) |
| `SpeechRecognitionService` | RecognitionService | Apps using SpeechRecognizer class |
| `VoiceInputActivity` | RECOGNIZE_SPEECH intent | Apps launching voice input via intent |

**Setup for keyboard mic button (HeliBoard):**
1. Open Translander → Keyboard Integration → Tap "Voice Input Method"
2. Enable "Translander" in the system keyboard list
3. In HeliBoard settings, enable "Voice input key"
4. Mic button should now appear on keyboard toolbar

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                     Voice Input Flow                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Floating Mic ─┐                                             │
│                ├─→ AudioRecorder (16kHz PCM)                 │
│  Accessibility ┤          ↓                                  │
│  Button       ─┤   Parakeet ONNX Model (offline)             │
│                │          ↓                                  │
│  Keyboard Mic ─┘   Word Corrections (optional)               │
│                           ↓                                  │
│                ┌──────────┴──────────┐                       │
│                ↓                     ↓                       │
│         Accessibility API      Keyboard IME                  │
│         (inject into apps)     (direct input)                │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                Voice Message Transcription                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Share/Open audio file  OR  Folder monitor detects file      │
│       ↓                                                      │
│  AudioDecoder (MediaCodec → 16kHz mono PCM)                  │
│       ↓                                                      │
│  Parakeet ONNX Model                                         │
│       ↓                                                      │
│  Result displayed with Copy/Share options                    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Supported Languages

Auto-detect, English, German, French, Spanish, Italian, Portuguese, Dutch, Polish, Russian, Ukrainian, Czech, Slovak, Hungarian, Romanian, Bulgarian, Croatian, Slovenian, Greek, Danish, Swedish, Finnish, Estonian, Latvian, Lithuanian, Maltese

The user interface is available in all supported languages. Translations were machine-generated and may contain errors or awkward phrasing — pull requests welcome.

## Build

### Prerequisites
- Android Studio (or standalone Android SDK)
- JDK 21+

### Commands
```bash
# Set environment (adjust paths as needed)
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=~/android-studio/jbr
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Capture voice for transcription |
| `SYSTEM_ALERT_WINDOW` | Display floating mic button overlay |
| `FOREGROUND_SERVICE_MICROPHONE` | Keep recording while in background |
| `FOREGROUND_SERVICE_DATA_SYNC` | Monitor folders for voice messages |
| `BIND_ACCESSIBILITY_SERVICE` | Inject text into apps |
| `POST_NOTIFICATIONS` | Show recording status and voice message alerts |
| `INTERNET` | Download speech model (one-time) |
| `READ_MEDIA_AUDIO` | Access audio files for transcription (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Access audio files for transcription (Android ≤12) |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart floating mic after device reboot |
| `BIND_INPUT_METHOD` | Register as voice input method for keyboards |

## Project Structure

```
app/src/main/java/com/translander/
├── TranslanderApp.kt         # Application class, dependency injection
├── asr/                      # Speech recognition
│   ├── AudioRecorder.kt      # 16kHz PCM recording
│   ├── DictionaryManager.kt  # Word correction rules
│   ├── ModelManager.kt       # Model download from HuggingFace
│   ├── ParakeetRecognizer.kt # ONNX inference wrapper
│   └── RecognizerManager.kt  # Shared recognizer singleton
├── ime/
│   └── VoiceInputMethodService.kt # Voice IME for keyboard integration
├── receiver/
│   └── BootReceiver.kt       # Auto-restart service after reboot
├── service/
│   ├── FloatingMicService.kt      # Draggable overlay button
│   ├── SpeechRecognitionService.kt # System RecognitionService API
│   ├── TextInjectionService.kt    # Accessibility service
│   └── VoiceInputActivity.kt      # RECOGNIZE_SPEECH intent handler
├── settings/
│   ├── SettingsActivity.kt   # Jetpack Compose UI
│   └── SettingsRepository.kt # DataStore preferences
├── transcribe/               # Voice message transcription
│   ├── AudioDecoder.kt       # Decode audio to 16kHz PCM
│   ├── AudioMonitorService.kt # Folder watching service
│   ├── TranscribeActivity.kt # Transcription UI
│   └── TranscribeManager.kt  # Extensible trigger system
└── ui/                       # UI components
    ├── RecordingOverlay.kt   # Recording state overlay
    └── theme/                # Material 3 theming
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Speech Recognition:** sherpa-onnx (Parakeet TDT v3)
- **Audio Processing:** Android MediaCodec, AudioRecord
- **Persistence:** DataStore Preferences
- **Build:** Gradle 8.12.1, AGP 8.7.2, Kotlin 2.0.21, Java 21

## Roadmap

- [ ] Quick Settings tile for transcription
- [ ] Home screen widget
- [ ] Hotwords boosting (pending sherpa-onnx TDT support)
- [ ] Export/import word correction rules

## Disclaimer

This app is provided as-is for personal use. Speech recognition accuracy depends on audio quality, accent, and background noise.

**Model Attribution:** Speech recognition uses [NVIDIA Parakeet TDT](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), licensed under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). ONNX conversion by [csukuangfj/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

**Privacy:** All speech processing happens locally on your device. No audio data is ever sent to any server. After the [speech model](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) is downloaded, you can revoke the Network permission in Android Settings to ensure the app can never access the internet.

---

*This project was developed with the assistance of [Claude Code](https://claude.ai/claude-code), an AI coding assistant by Anthropic.*
