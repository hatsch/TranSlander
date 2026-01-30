# Privacy Policy for Translander

**Last updated:** January 2026

## Overview

Translander is a voice-to-text application that performs all speech recognition **entirely on your device**. We are committed to protecting your privacy.

## Data Collection

**We do not collect any personal data.**

- No audio recordings are stored or transmitted
- No transcribed text is stored or transmitted
- No usage analytics or telemetry
- No account required
- No advertisements

## Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| Microphone | Record speech for on-device transcription |
| Accessibility Service | Inject transcribed text into other apps |
| Display Over Other Apps | Show floating microphone button |
| Internet | One-time download of speech recognition model (~600MB) |
| Storage/Media | Access audio files for voice message transcription |
| Notifications | Show service status |

## Speech Recognition Model

- The speech recognition model (Parakeet TDT v3) is downloaded once from HuggingFace
- After download, all processing happens offline on your device
- No audio or text is ever sent to any server

## Third-Party Services

- **HuggingFace**: Used only for initial model download (Parakeet TDT v3)
- No other third-party services are used

## Data Storage

All data remains on your device:
- Speech recognition model stored in app's private storage
- User preferences stored locally
- Word correction rules stored locally

## Children's Privacy

This app does not knowingly collect any information from children under 13.

## Changes to This Policy

We may update this privacy policy from time to time. Changes will be posted in this document with an updated date.

## Contact

For questions about this privacy policy, please open an issue at:
https://github.com/hatsch/translander/issues

## Open Source

Translander is open source software licensed under Apache 2.0. You can review the complete source code to verify these privacy claims.
