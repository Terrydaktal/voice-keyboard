# Voice Keyboard (`:whisper-keyboard`)

Android input method for local Whisper dictation against `whisper_service.py` (`/transcribe_raw`).

- Starts recording when the keyboard opens in a non-password text field, displays a live radial audio waveform, and continues recording in a foreground service even if you switch keyboards or minimize the IME.
- Tap the microphone to stop, upload the 16 kHz mono WAV to your configured Whisper server URL, and insert the transcript at the cursor. Failed uploads keep the WAV ready for one-tap retry.

## Build

Use JDK 17 and Android SDK Platform 36.

```sh
cd ~/Dev/assistant/android
./gradlew :whisper-keyboard:testDebugUnitTest :whisper-keyboard:assembleDebug :whisper-keyboard:lintDebug
```

APK output:
- `whisper-keyboard/build/outputs/apk/debug/whisper-keyboard-debug.apk`

## Install and set up

```sh
adb -s DEVICE install -r whisper-keyboard/build/outputs/apk/debug/whisper-keyboard-debug.apk
```

1. Open **Voice Keyboard**, allow microphone access, and enable **Voice Keyboard** in Android’s keyboard settings.
2. Enter your Whisper server URL (e.g. `http://<host>:5001/transcribe_raw`) and tap **Save server URL**.
3. Switch to **Voice Keyboard** in any text field to start dictating.
