# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.3] - 2026-02-04

### Fixed
- Thread safety: use AtomicBoolean for recording state, @Volatile for singleton
- Replace deprecated onBackPressed() with OnBackPressedDispatcher
- Remove deprecated AccessibilityNodeInfo.recycle()/obtain() calls
- Fix mutex scoping and properly cancel debounce jobs
- Fix race condition in recognizer initialization

### Changed
- Use CopyOnWriteArrayList for file observers
- Replace Handler.postDelayed with coroutine delay
- Add explicit ServiceInfo type for Android 14+ foreground services
- Remove non-functional language setting
- Dismiss audio notification when closing transcription popup

## [1.2.2] - 2026-02-03

### Fixed
- Fix folder monitor 6-hour timeout on Android 15+ (switch to specialUse FGS type)
- Clean up dead code for minSdk 26 compatibility

### Changed
- Extract ServiceAlertNotification for better code organization
- Add documentation for Android 14/15/16 FGS restrictions

## [1.2.1] - 2026-02-03

### Fixed
- Harden service startup for Android 14+ boot restrictions
- Show notification when service fails to start at boot (tap to open app and restart)
- Request notification permission when enabling floating mic or folder monitor (Android 13+)

## [1.2.0] - 2026-02-02

### Added
- Import model from local folder instead of downloading over the internet
- Separate "Download model" and "Import from folder" options in UI
- Copy progress indicator ("Copying to app storage")
- User-friendly localized error messages for checksum, network, and storage errors
- Model file validation on resume and before loading
- NVIDIA Parakeet TDT CC-BY-4.0 attribution

### Fixed
- Wrap browser intent in try-catch for devices without a browser app
- Prevent UI from getting stuck if model files are deleted outside the app

## [1.1.1] - 2025-05-01

### Fixed
- Catch `UnsatisfiedLinkError` in speech recognizer initialization
- Guard foreground service startup against `IllegalStateException` on Android 12+
- Prevent `Application.onCreate()` crash if background services fail to start at boot

## [1.1.0] - 2025-04-15

### Added
- UI localization for 23 languages (Bulgarian, Croatian, Czech, Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish, Ukrainian)
- Store listings in German, French, Spanish, Italian, and Portuguese

### Fixed
- Language count in descriptions (25, not 26+)

## [1.0.2] - 2025-02-10

### Fixed
- Fix floating button crash when overlay permission is missing
- Overlay permission setting now always visible with tap-to-manage shortcut
- Folder monitor automatically restarts when adding or removing watched folders

## [1.0.1] - 2025-02-05

### Added
- Accessibility service disclosure dialog for Google Play compliance

### Fixed
- Fix model download source in privacy policy (HuggingFace, not GitHub)

## [1.0.0] - 2025-02-01

### Added
- Offline speech recognition using Parakeet TDT v3 model (~600MB)
- Floating microphone button (draggable overlay)
- Accessibility button support (system navigation bar)
- Voice message transcription — share audio files from WhatsApp, Signal, etc.
- Word corrections feature — fix recurring recognition errors with custom rules
- Audio monitor — watches Downloads folder for new voice messages
- Support for OPUS, AAC, OGG, M4A, MP3 audio formats
- Multi-language support (25 languages with auto-detection)
- Material 3 UI with dark/light theme support
- Keyboard integration for HeliBoard and others
- Model integrity verification with SHA256 checksums

[1.2.2]: https://github.com/hatsch/TranSlander/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/hatsch/TranSlander/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/hatsch/TranSlander/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/hatsch/TranSlander/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/hatsch/TranSlander/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/hatsch/TranSlander/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/hatsch/TranSlander/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/hatsch/TranSlander/releases/tag/v1.0.0
