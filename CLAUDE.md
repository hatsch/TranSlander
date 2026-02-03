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
- **Multi-language** - Supports 25 languages with auto-detection
- **Material 3 UI** - Modern settings with dark/light theme support

### How It Works
1. User taps mic button (floating or accessibility)
2. Audio recorded at 16kHz PCM
3. Parakeet ONNX model transcribes speech locally
4. Word corrections applied (if configured)
5. Text injected into currently focused field via Accessibility API
6. Falls back to clipboard if no field focused

## Build

**Requirements:** JDK 21+, Android SDK, Gradle 8.12.1 (via wrapper)

**Build stack:** Kotlin 2.0.21, AGP 8.7.2, Compose compiler plugin (Kotlin 2.0+)

```bash
# IMPORTANT: Use absolute paths, not ~ (tilde doesn't expand in all contexts)
export ANDROID_HOME=/home/hatsch/Android/Sdk
export JAVA_HOME=/home/hatsch/android-studio/jbr
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# If signature mismatch error, uninstall first:
adb uninstall at.webformat.translander && adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build Verification After Changes

Always run `./gradlew assembleDebug` after significant changes (localization, resource files, manifest edits). The F-Droid build only runs against tagged releases, so `fdroid build` is only needed before tagging a new version — not for every feature branch change.

**When to run F-Droid build:**
- Before tagging a new release (e.g., v1.0.3)
- After changing build config, NDK version, or sherpa-onnx srclib version
- NOT needed for resource-only changes on feature branches

**F-Droid build skips if APK exists:** fdroid checks `unsigned/at.webformat.translander_{versionCode}.apk`. Remove it to force a rebuild:
```bash
rm fdroiddata/unsigned/at.webformat.translander_3.apk
rm fdroiddata/unsigned/at.webformat.translander_3_src.tar.gz
```

### Gradle Daemon Issues
Stale Gradle daemons and corrupt cache cause `For input string: ""` errors. Clean before F-Droid builds:
```bash
rm -rf ~/.gradle/daemon
rm -rf /home/hatsch/claude/transcript/fdroiddata/build/at.webformat.translander/.gradle
```

### sherpa-onnx AAR
The sherpa-onnx native library is built from source and placed at `app/libs/sherpa-onnx-<version>.aar`.
Run `./build-sherpa-onnx-aar.sh` to build it. This location is outside the gradle `build/` directory
so it survives `gradle clean` (required for F-Droid builds).

### NDK
F-Droid build requires NDK. Currently using `27.0.12077973` (set in fdroiddata YAML `ndk:` field). Available locally:
- `27.0.12077973`
- `28.2.13676358`

The NDK version must match between the fdroiddata YAML and the local SDK. `ANDROID_HOME` must be set for fdroid to find it.

### Common Build Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `For input string: ""` | Stale Gradle daemon cache | `rm -rf ~/.gradle/daemon` |
| `No app metadata found` | Running `fdroid build` from wrong directory | Must run from fdroiddata root, not the app repo |
| fdroid build skips silently | APK already exists in `unsigned/` | Remove existing APK and src tarball |
| `Environment variable {env: serverwebroot}` | Missing fdroid config (warning, not fatal) | Ignore — only needed for `fdroid publish` |
| Lint `MissingTranslation` | New string added to `values/strings.xml` without translations | Add to all `values-{locale}/strings.xml` files |

**Translations workflow:** When adding new strings, defer translations until the feature is complete and stable. This avoids repeatedly updating 23 locale files during iterative development. Add all translations in one batch before the release.
| Lint `ExtraTranslation` | String in translation file not in default `strings.xml` | Remove from translation files |
| XML apostrophe crash | Unescaped `'` in strings.xml | Use `\'` — e.g., `there\'s` not `there's` |

## Release Checklist

When bumping a version, update all of these:
1. `app/build.gradle.kts` — `versionCode` and `versionName`
2. `CHANGELOG.md` — Add new version entry with date
3. `fastlane/metadata/android/en-US/changelogs/{versionCode}.txt` — Short changelog for F-Droid/store
4. `github-release.md` — Release notes for GitHub releases
5. Tag the release: `git tag v{versionName}`

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
- **Package**: `at.webformat.translander`
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
- [x] F-Droid metadata created (`fdroiddata/metadata/at.webformat.translander.yml`)
- [x] F-Droid local build verified with `fdroid build`
- [x] Add app screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/`
- [x] Create feature graphic at `fastlane/metadata/android/en-US/images/featureGraphic.png`
- [ ] Submit to F-Droid via GitLab RFP at https://gitlab.com/fdroid/rfp
- [ ] Consider using git submodules instead of srclibs for external repos (sherpa-onnx) — submodules can be updated without an MR to fdroiddata and are covered by the scanner
- [ ] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds) — allows users to switch between GitHub and F-Droid channels using the same signature
- [ ] Consider multiple APKs for native code — currently arm64-v8a only (36MB), could add armeabi-v7a/x86_64 as separate APKs

**Note:** F-Droid uses fast-forward merges. Always rebase the fdroiddata branch, never merge:
```bash
cd fdroiddata && git fetch origin master && git rebase origin/master && git push --force myfork add-translander
```
See [Git guide for fdroiddata contributors](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage).

**F-Droid build order:** `prebuild` → scanner → `build` → gradle. The scanner runs between `prebuild` and `build`. Anything compiled in `prebuild` (like AAR files) will be flagged by the scanner as binary artifacts. Use `build:` instead of `prebuild:` for compiling native code from srclibs — this way the scanner never sees the built artifacts and `scanignore` is not needed. See `com.github.xiaoshihou.jiyi` and `de.schliweb.makeacopy` in fdroiddata for reference.

**Commit field:** F-Droid maintainers require full commit hashes (not tags or short hashes) in the `commit:` field. Example: `commit: f4d24b55fcf203c0a85f0030fb98e59f162d61fc` not `commit: v1.1.1`.

**Metadata descriptions:** Don't put `Description:` in the fdroiddata YAML. F-Droid pulls it automatically from `fastlane/metadata/android/en-US/full_description.txt` in the source repo. About 53% of F-Droid apps use this approach.

**Git push:** Only use `--force` when history was actually rewritten (e.g., after rebase). A regular `git push` is sufficient for fast-forward updates.

**Local fdroid build:** Must run from fdroiddata root. Requires `ANDROID_HOME` for NDK:
```bash
rm -rf ~/.gradle/daemon
cd /home/hatsch/claude/transcript/fdroiddata
ANDROID_HOME=/home/hatsch/Android/Sdk fdroid build at.webformat.translander
```

**Testing unreleased commits (LOCAL ONLY — never commit this change):**
To test an F-Droid build before tagging a release, temporarily change `commit:` in the fdroiddata YAML to a branch name or commit hash:
```yaml
# Temporary — revert before committing!
commit: feature/localization   # or a specific commit hash
```
Then remove the existing APK from `unsigned/` and run `fdroid build`. Always revert the YAML change afterward.

### Google Play Publishing
- [x] Developer account created
- [x] App listing created with screenshots, descriptions, privacy policy
- [x] Accessibility service disclosure dialog added (required for permission approval)
- [x] 3 demo videos provided showing permission usage
- [x] Closed test release published (v1.0.2)
- [ ] Get 12 testers opted-in to closed test (required since late 2023 for new accounts)
- [ ] Run closed test for 14 days with 12+ testers
- [ ] Apply for production access and permission review

**Status:** On hold. Production requires 12 testers for 14 days before you can even apply. Closed test release is published and ready. Low priority — F-Droid + GitHub releases cover distribution for now.

### Known Issues
- Vanadium browser doesn't handle voice input results (browser bug, not fixable on our side)
- AOSP keyboard has no voice input support (use HeliBoard instead)

### Localization
The user interface has been machine-translated into 23 languages. If you spot translation errors or awkward phrasing, please open a pull request.
