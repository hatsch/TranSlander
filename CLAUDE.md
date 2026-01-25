# Voice Keyboard - Project Context

## What Is This App?

An Android voice typing app that works **completely offline**. Speak into your phone and text appears in any app - no internet, no cloud, no data leaving your device.

### Features
- **Offline speech recognition** - Uses Parakeet v3 neural model (~600MB) running locally via sherpa-onnx
- **Works in any app** - Uses Android Accessibility to inject text into any focused text field
- **Two input methods**:
  - Accessibility button (system nav bar) - works system-wide
  - Floating mic button (draggable overlay) - optional, always visible
- **Word corrections** - Custom dictionary to fix recurring recognition errors (e.g., "Tamtam" → "Tamdam")
- **Voice message transcription** - Share or open audio files (OPUS, AAC, OGG, M4A, MP3) to transcribe them
- **Multi-language** - Supports 26+ languages with auto-detection
- **Material 3 UI** - Modern settings with dark/light theme support

### How It Works
1. User taps mic button (floating or accessibility)
2. Audio recorded at 16kHz PCM
3. Parakeet ONNX model transcribes speech locally
4. Word corrections applied (if configured)
5. Text injected into currently focused field via Accessibility API
6. Falls back to clipboard if no field focused

## Build
```bash
# IMPORTANT: Use absolute paths, not ~ (tilde doesn't expand in all contexts)
export ANDROID_HOME=/home/hatsch/Android/Sdk
export JAVA_HOME=/home/hatsch/android-studio/jbr
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# If signature mismatch error, uninstall first:
adb uninstall com.translander && adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

### Core Components
| Component | Purpose |
|-----------|---------|
| `ParakeetRecognizer` | Offline speech recognition (sherpa-onnx, Parakeet v3 model ~600MB) |
| `FloatingMicService` | Draggable floating mic button overlay |
| `TextInjectionService` | Accessibility service for injecting text into focused fields |
| `DictionaryManager` | Post-processing word corrections/replacements |
| `SettingsActivity` | Jetpack Compose settings UI |

### Data Flow
```
Tap mic → AudioRecorder (16kHz PCM) → ParakeetRecognizer (ONNX)
    → DictionaryManager.applyReplacements() → TextInjectionService → Focused text field
```

## Key Directories
```
app/src/main/java/com/translander/
├── asr/                  # Speech recognition
│   ├── AudioRecorder.kt
│   ├── ModelManager.kt       # Downloads Parakeet model from HuggingFace
│   ├── ParakeetRecognizer.kt # ONNX inference
│   ├── RecognizerManager.kt  # Shared recognizer singleton
│   └── DictionaryManager.kt  # Word replacement rules
├── service/
│   ├── FloatingMicService.kt
│   └── TextInjectionService.kt
├── settings/
│   ├── SettingsActivity.kt
│   └── SettingsRepository.kt # DataStore preferences
├── transcribe/           # Voice message transcription
│   ├── AudioDecoder.kt       # Decode audio files to 16kHz PCM
│   ├── AudioMonitorService.kt # Watch folders for new audio
│   ├── TranscribeActivity.kt # UI for shared/opened audio
│   └── TranscribeManager.kt  # Extensible trigger system
└── k2fsa/sherpa/onnx/    # Native ONNX bindings
```

## Recently Added: Dictionary/Word Corrections Feature

### What It Does
Post-processing text replacements after speech recognition. User defines rules like "Tamtam" → "Tamdam" and they're applied automatically.

### Files Added/Modified
- **NEW**: `asr/DictionaryManager.kt` - Stores rules in `replacements.json`, applies whole-word regex matching
- `TranslanderApp.kt` - Added `dictionaryManager` instance
- `asr/RecognizerManager.kt` - Calls `applyReplacements()` after transcription
- `settings/SettingsRepository.kt` - Added `dictionaryEnabled` preference
- `settings/SettingsActivity.kt` - Added "Word Corrections" UI section with dialog

### Storage
Rules stored in `context.filesDir/replacements.json`:
```json
{
  "version": 1,
  "rules": [
    {"from": "Tamtam", "to": "Tamdam", "caseSensitive": false}
  ]
}
```

### UI Location
Settings → Word Corrections → Manage Corrections

## Voice Message Transcription Feature

### What It Does
Transcribe voice messages from apps like WhatsApp, Signal, or any downloaded audio files. Supports OPUS, AAC, OGG, M4A, MP3 formats.

### Access Methods

**Implemented:**
1. **Share intent** - Share audio from WhatsApp/Signal → Voice Transcribe appears in share sheet
2. **Open with** - Open audio files from file manager → Voice Transcribe in app picker
3. **Folder monitoring** - Background service watches Downloads folder, shows notification when audio detected

**Future options (not yet implemented):**
- Quick Settings tile
- Home screen widget
- File picker button in Settings

### Architecture
```
TranscribeManager (extensible trigger system)
    ├── AudioMonitorService (folder watching trigger)
    └── [Future: QuickSettingsTile, Widget, etc.]

TranscribeActivity (UI for all access methods)
    ↓
AudioDecoder (MediaCodec → 16kHz mono PCM)
    ↓
RecognizerManager.transcribe() (reuses existing recognition)
```

### Files
| File | Purpose |
|------|---------|
| `AudioDecoder.kt` | Decodes audio files using MediaExtractor/MediaCodec, resamples to 16kHz mono |
| `TranscribeActivity.kt` | Compose dialog for share/open/monitor intents, shows progress and result |
| `AudioMonitorService.kt` | Foreground service using FileObserver to watch Downloads folder |
| `TranscribeManager.kt` | Interface for adding new transcription triggers |

### Settings
- `audioMonitorEnabled` - Toggle folder monitoring
- `monitoredFolders` - Folders to watch (default: Downloads)

### UI Location
Settings → Voice Message Transcription → Monitor Downloads toggle

## Hotwords Feature (BLOCKED)

Sherpa-onnx supports hotwords boosting but **NOT for Parakeet TDT models**:
- Requires `modified_beam_search` decoding (TDT models don't support it yet)
- Requires `bpe.vocab` file (not available for Parakeet v3)
- Monitor sherpa-onnx releases for TDT beam search support

When available, config would use:
```kotlin
OfflineRecognizerConfig(
    decodingMethod = "modified_beam_search",
    hotwordsFile = "/path/to/hotwords.txt",
    hotwordsScore = 2.0f
)
```

## Model Info
- **Model**: Parakeet TDT v3 (nemo_transducer)
- **Source**: `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`
- **Size**: ~600MB
- **Location**: `context.filesDir/parakeet-v3/`
- **Files**: encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt

## Permissions Required
- RECORD_AUDIO
- SYSTEM_ALERT_WINDOW (floating button)
- FOREGROUND_SERVICE_MICROPHONE
- FOREGROUND_SERVICE_DATA_SYNC (audio monitor)
- BIND_ACCESSIBILITY_SERVICE
- POST_NOTIFICATIONS
- INTERNET (model download)
- READ_EXTERNAL_STORAGE (API < 33, audio monitor)
- READ_MEDIA_AUDIO (API >= 33, audio monitor)

## Package Info
- **Package**: `com.translander`
- **App Class**: `TranslanderApp.kt`
- **Main Activity**: `SettingsActivity.kt`

## Testing
1. Build and install (see Build section above)
2. Test floating mic: Enable in Settings → tap to transcribe
3. Test accessibility button: Enable in Android Settings → Accessibility → Translander
4. Test share: WhatsApp → share voice message → Translander appears in share sheet
5. Test open with: File manager → select audio → Open with Translander
6. Test folder monitoring: Enable in Settings → new audio in Downloads → auto-transcription
7. Test formats: OPUS, AAC, M4A, OGG, MP3

## Open Tasks

### F-Droid Publishing
- [ ] Add app screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/`
- [ ] Create feature graphic (1024x500) at `fastlane/metadata/android/en-US/images/featureGraphic.png` (optional)
- [ ] Submit to F-Droid via GitLab RFP at https://gitlab.com/fdroid/rfp

### Known Issues
- Vanadium browser doesn't handle voice input results (browser bug, not fixable on our side)
- AOSP keyboard has no voice input support (use HeliBoard instead)
