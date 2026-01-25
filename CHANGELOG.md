# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Refactored settings UI and fixed deprecation warnings

## [1.0.0-alpha2] - 2025-01-24

### Added
- Keyboard integration - use TranSlander as voice input for any keyboard with mic button
- SpeechRecognitionService for Android system voice input API
- VoiceInputMethodService for direct keyboard integration
- Model integrity verification with SHA256 checksums
- Privacy policy documentation
- Apache 2.0 license

### Changed
- Improved code quality throughout

## [1.0.0-alpha1] - 2025-01-20

### Added
- Offline speech recognition using Parakeet TDT v3 model (~600MB)
- Floating microphone button (draggable overlay)
- Accessibility button support (system navigation bar)
- Voice message transcription - share audio files from WhatsApp, Signal, etc.
- Word corrections feature - fix recurring recognition errors with custom rules
- Audio monitor - watches Downloads folder for new voice messages
- Support for OPUS, AAC, OGG, M4A, MP3 audio formats
- Multi-language support (26+ languages with auto-detection)
- Material 3 UI with dark/light theme support
- GitHub Actions CI/CD workflow

### Technical
- Uses sherpa-onnx for on-device ONNX inference
- Text injection via Android Accessibility API
- Model auto-download from HuggingFace on first launch
- APK optimized for arm64-v8a architecture

[Unreleased]: https://github.com/hatsch/TranSlander/compare/v1.0.0-alpha2...HEAD
[1.0.0-alpha2]: https://github.com/hatsch/TranSlander/compare/v1.0.0-alpha1...v1.0.0-alpha2
[1.0.0-alpha1]: https://github.com/hatsch/TranSlander/releases/tag/v1.0.0-alpha1
