# Voice Keyboard (`:whisper-keyboard`)

Single Android app combining the Voice Keyboard IME, keyboard microphone handoff, and pluggable transcription backends (**Rambler** and **Whisper**).

- **Rambler (default):** live-encodes 16 kHz mono Ogg/Opus via Android `MediaCodec` (`c2.android.opus.encoder`) and streams over HTTP/2 gRPC (`S3Bidi`) to Google’s `beyond-a2a-recognizer` (Gemini v3, profanity filter off, `en-GB` default), with automatic 3–4 minute rolling segments and WAV-backed retry on network errors. Works out of the box with no server setup; the top-right corner always displays **Rambler ▾** (showing only **Rambler** in the dropdown until a Whisper URL is configured).
- **Whisper (optional):** enter a Whisper server URL in Setup (e.g. `http://<host>:5001/transcribe_raw`) to enable switching between **Rambler** and **Whisper** from the top-right dropdown or Setup. Clear the URL to return to Rambler-only mode.
- **Keyboard mic handoff & configurable return keyboard:** built directly into the same APK via `SpeechBridgeActivity` and `SwiftKeyBridgeService` — works with SwiftKey or any active keyboard firing `ACTION_RECOGNIZE_SPEECH`, matches the calling keyboard’s height, and lets you choose which keyboard the top-left `← <Keyboard>` back button returns to (or **Auto (last keyboard)**) in Setup.

## Build

Use JDK 17 and Android SDK Platform 36.

```sh
cd ~/Dev/assistant/android
./gradlew :whisper-keyboard:testDebugUnitTest :whisper-keyboard:assembleDebug :whisper-keyboard:lintDebug
```

APK output:
- `whisper-keyboard/build/outputs/apk/debug/whisper-keyboard-debug.apk`

## Install and set up

Install or update over ADB (for wireless ADB, connect with `adb connect <ip>:5555`):

```sh
adb -s DEVICE install -r whisper-keyboard/build/outputs/apk/debug/whisper-keyboard-debug.apk
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
3. *(Optional — enable local Whisper backend)*: enter your Whisper server URL in **Voice Keyboard** Setup and tap **Save server URL**.

## Migrating from the old two-app setup (Dictation Bridge + Whisper Keyboard)

Older setups installed `io.github.terrydaktal.ramblerbridge` (**Dictation Bridge**) alongside `io.github.terrydaktal.whisperkeyboard`. Because the unified APK keeps the `io.github.terrydaktal.whisperkeyboard` package ID:

1. Install the unified APK in place with `adb -s DEVICE install -r whisper-keyboard-debug.apk` (your existing IME enablement, microphone permission, and saved Whisper URL are preserved).
2. Grant `WRITE_SECURE_SETTINGS` to `io.github.terrydaktal.whisperkeyboard` using the ADB command above.
3. Enable **Voice Keyboard** in **Settings → Accessibility**.
4. Uninstall the old **Dictation Bridge** app (`adb -s DEVICE uninstall io.github.terrydaktal.ramblerbridge`), and optionally **PixelBoard** (`adb -s DEVICE uninstall com.akshaykadam.pixelboard`).
5. Tap your keyboard’s mic once and select **Voice Keyboard** as the default handler.

## Project structure

```text
whisper-keyboard/src/main/
├── AndroidManifest.xml
├── java/io/github/terrydaktal/whisperkeyboard/
│   ├── WhisperKeyboardService.kt    # IME service
│   ├── WhisperRecordingService.kt   # Foreground audio capture + backend orchestration
│   ├── TranscriptionBackend.kt      # Backend interface, WhisperBackend & registry
│   ├── MicrophonePanel.kt           # Keyboard UI (waveform, ← <Keyboard>, backend dropdown)
│   ├── SetupActivity.kt             # Unified setup, status checks, return keyboard, locale & Whisper URL
│   ├── SpeechBridgeActivity.kt      # Keyboard ACTION_RECOGNIZE_SPEECH receiver
│   ├── SwiftKeyBridgeService.kt     # Accessibility service measuring keyboard height & switching IME
│   ├── KeyboardSwitch.kt            # IME lookup & WRITE_SECURE_SETTINGS switcher
│   ├── KeyboardLayoutStore.kt       # SharedPreferences for height, last caller & return keyboard
│   ├── DictationSettings.kt         # Backend selector (Rambler/Whisper) & locale settings
│   ├── WavRecording.kt              # 16 kHz mono PCM WAV recorder with live chunk callback
│   ├── WhisperEndpoint.kt           # Optional HTTP POST client for whisper_service.py
│   ├── RamblerBackend.kt            # TranscriptionBackend adapter for RamblerLiveSession
│   ├── RamblerS3Proto.kt            # S3Bidi protobuf wire codec & gRPC frame splitter
│   ├── OggOpusMuxer.kt              # RFC 7845 / 3533 Ogg/Opus streaming page muxer
│   ├── OpusOggEncoder.kt            # Android MediaCodec audio/opus encoder wrapper
│   ├── RamblerS3Client.kt           # OkHttp HTTP/2 duplex S3Bidi client
│   └── RamblerLiveSession.kt        # Rolling 3–4 min live-streaming Rambler session & WAV retry
└── res/
    ├── drawable/
    ├── values/
    └── xml/
```
