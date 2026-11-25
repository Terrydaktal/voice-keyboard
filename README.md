# Voice Keyboard

Standalone Android voice dictation keyboard (`io.github.terrydaktal.whisperkeyboard`) with pluggable transcription backends (**Rambler** and optional **Whisper**), built-in keyboard microphone handoff, SwiftKey-positioned Backspace & editor-aware Enter keys, and a persistent retry queue.

## Features

- **Rambler (default):** live-encodes 16 kHz mono Ogg/Opus via Android `MediaCodec` (`c2.android.opus.encoder`) and streams over HTTP/2 gRPC (`S3Bidi`) to Google’s `beyond-a2a-recognizer` (Gemini v3, profanity filter off, `en-GB` default), with automatic 3–4 minute rolling segments. Works out of the box with no server setup; the top-right corner always displays **Rambler ▾** (showing only **Rambler** in the dropdown until a Whisper URL is configured).
- **Whisper (optional):** enter any local or Tailscale `whisper_service.py` URL in Setup (e.g. `http://<host>:5001/transcribe_raw`) to enable switching between **Rambler** and **Whisper** from the top-right dropdown or Setup. Clear the URL to return to Rambler-only mode.
- **Keyboard mic handoff & configurable return keyboard:** built directly into the same APK (`SpeechBridgeActivity` + `SwiftKeyBridgeService`) — opens from SwiftKey or any active keyboard firing `ACTION_RECOGNIZE_SPEECH`, matches the calling keyboard’s height, and lets you choose which keyboard the top-left `← <Keyboard>` back button returns to (or **Auto (last keyboard)**) in Setup.
- **Backspace & editor-aware Enter:** SwiftKey-positioned bottom-right column — tap Backspace for a character, hold to delete one word at a time; Enter dispatches the active field’s IME action (`Send`, `Search`, `Go`, `Next`, `Done`) or inserts a newline in multi-line fields.
- **Crash-safe retry queue:** finalized WAVs enter `filesDir/pending_dictations/` on Stop (capped at 20 files / 50 MB) and are auto-deleted immediately after `commitText()` delivers the transcript. Failed uploads stay queued across process kills and reboots, auto-retry while the keyboard is open, and can be retried via the bottom-left **↻ Retry (N)** button on either backend.

## Project Structure

```text
voice-keyboard/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/io/github/terrydaktal/whisperkeyboard/
        │   │   ├── WhisperKeyboardService.kt    # IME service
        │   │   ├── WhisperRecordingService.kt   # Foreground audio capture, retry loop & queue orchestration
        │   │   ├── PendingRecordingStore.kt     # Bounded persistent FIFO WAV store (20 files / 50 MB)
        │   │   ├── TranscriptionBackend.kt      # Backend interface, WhisperBackend & registry
        │   │   ├── MicrophonePanel.kt           # Keyboard UI (waveform, ← <Keyboard>, backend, Retry, Backspace, Enter)
        │   │   ├── BackspaceDeletion.kt         # Surrogate-safe char & SwiftKey-style word deletion
        │   │   ├── EnterAction.kt               # EditorInfo-aware Enter action dispatcher
        │   │   ├── SetupActivity.kt             # Permissions, status checks, return keyboard, locale, Whisper URL & queue controls
        │   │   ├── SpeechBridgeActivity.kt      # Keyboard ACTION_RECOGNIZE_SPEECH receiver
        │   │   ├── SwiftKeyBridgeService.kt     # Accessibility service measuring keyboard height & switching IME
        │   │   ├── KeyboardSwitch.kt            # IME lookup & WRITE_SECURE_SETTINGS switcher
        │   │   ├── KeyboardLayoutStore.kt       # SharedPreferences for height, last caller & return keyboard
        │   │   ├── DictationSettings.kt         # Backend selector (Rambler/Whisper) & locale settings
        │   │   ├── WavRecording.kt              # 16 kHz mono PCM WAV recorder with live chunk callback
        │   │   ├── WhisperEndpoint.kt           # Optional HTTP POST client for /transcribe_raw
        │   │   ├── RamblerBackend.kt            # TranscriptionBackend adapter for RamblerLiveSession
        │   │   ├── RamblerS3Proto.kt            # S3Bidi protobuf wire codec & gRPC frame splitter
        │   │   ├── OggOpusMuxer.kt              # RFC 7845 / 3533 Ogg/Opus streaming page muxer
        │   │   ├── OpusOggEncoder.kt            # Android MediaCodec audio/opus encoder wrapper
        │   │   ├── RamblerS3Client.kt           # OkHttp HTTP/2 duplex S3Bidi client
        │   │   └── RamblerLiveSession.kt        # Rolling 3–4 min live-streaming Rambler session & WAV retry
        │   └── res/
        └── test/java/io/github/terrydaktal/whisperkeyboard/
            ├── BackspaceDeletionTest.kt
            ├── DictationDiagnosticsTest.kt
            ├── EnterActionTest.kt
            ├── KeyboardLayoutStoreTest.kt
            ├── OggOpusMuxerTest.kt
            ├── PendingRecordingStoreTest.kt
            ├── RamblerS3ProtoTest.kt
            └── WhisperEndpointTest.kt
```

## Build & Test

Use JDK 17 and Android SDK Platform 36. Point `sdk.dir` in `local.properties` at your Android SDK (or set `ANDROID_HOME`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

APK output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Install & Set Up

```sh
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open **Voice Keyboard**, allow microphone access, and enable **Voice Keyboard** in Android’s keyboard settings.
2. *(Optional — keyboard mic handoff)*:
   - In **SwiftKey Settings → Rich input**, leave **Voice typing** on and turn **Multi-modal voice typing** off. In Android’s on-screen keyboard settings, turn off **Google Voice Typing**.
   - Grant keyboard-switching permission over ADB:
     ```sh
     adb -s DEVICE shell pm grant io.github.terrydaktal.whisperkeyboard android.permission.WRITE_SECURE_SETTINGS
     ```
   - Under **Settings → Accessibility**, enable **Voice Keyboard** (on sideloaded APKs, open App Info → overflow menu → **Allow restricted settings** first if Android blocks the toggle).
   - Tap your keyboard’s microphone in a text field and choose **Voice Keyboard** (always).
3. *(Optional — local Whisper backend)*: enter your Whisper server URL in **Voice Keyboard** Setup and tap **Save server URL**.

## Diagnostics & Debugging

Voice Keyboard keeps an always-on, privacy-safe causal flight recorder (`DictationDiagnostics`, schema v1) that never logs raw transcripts or API keys. Events are stored in a 512-entry memory ring, mirrored to logcat (`VoiceKeyboardDiag`), and persisted asynchronously to rotating JSONL files (`filesDir/diagnostics/events.jsonl` + `events.1.jsonl`, 512 KiB max) so failure evidence survives Android logcat rotation and process restarts.

Three independent ways to inspect diagnostics:
1. **On-device (no computer required):** Open **Voice Keyboard** Setup → scroll to **Diagnostics** to see session/S3/commit counters and the last error, tap **View log**, or tap **Copy report**.
2. **Live snapshot via `dumpsys` (works even if UI is stuck):**
   ```sh
   adb -s DEVICE shell dumpsys activity service io.github.terrydaktal.whisperkeyboard/.WhisperRecordingService
   adb -s DEVICE shell dumpsys input_method | grep -A 80 "VoiceKeyboard Diagnostics"
   ```
3. **Persisted post-mortem JSONL log & live logcat stream:**
   ```sh
   adb -s DEVICE shell run-as io.github.terrydaktal.whisperkeyboard cat files/diagnostics/events.jsonl
   adb -s DEVICE logcat -s VoiceKeyboardDiag
   ```

## Migrating from the Old Two-App Setup

Because this repo keeps `applicationId = "io.github.terrydaktal.whisperkeyboard"`, installing in place (`adb install -r`) preserves your IME enablement, microphone permission, accessibility grant, and saved settings. If migrating from the older separate `io.github.terrydaktal.ramblerbridge` (**Dictation Bridge**) app, grant `WRITE_SECURE_SETTINGS` to `io.github.terrydaktal.whisperkeyboard`, enable **Voice Keyboard** in Accessibility, and uninstall the old bridge app.
