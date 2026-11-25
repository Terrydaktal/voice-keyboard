# Voice Keyboard (`:whisper-keyboard`)

Single Android app combining the Voice Keyboard IME and keyboard microphone handoff (SwiftKey or any keyboard firing `ACTION_RECOGNIZE_SPEECH`).

- Starts recording when opened in a text field, matches the calling keyboard’s height, displays a live radial waveform, and keeps recording while you switch back via the top-left `← <Keyboard>` button.
- Choose which keyboard the back button returns to (or **Auto (last keyboard)**) in Setup.

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
3. *(Keyboard mic handoff — SwiftKey or another keyboard)*:
   - In **SwiftKey Settings → Rich input**, leave **Voice typing** on and turn **Multi-modal voice typing** off. In Android’s on-screen keyboard settings, turn off **Google Voice Typing**.
   - Grant keyboard-switching permission over ADB:
     ```sh
     adb -s DEVICE shell pm grant io.github.terrydaktal.whisperkeyboard android.permission.WRITE_SECURE_SETTINGS
     ```
   - Under **Settings → Accessibility**, enable **Voice Keyboard** (on sideloaded APKs, open App Info → overflow menu → **Allow restricted settings** first if Android blocks the toggle).
   - Tap your keyboard’s microphone in a text field and choose **Voice Keyboard** (always).

## Migrating from the old two-app setup (Dictation Bridge + Whisper Keyboard)

Older setups installed `io.github.terrydaktal.ramblerbridge` (**Dictation Bridge**) alongside `io.github.terrydaktal.whisperkeyboard`. Because the unified APK keeps the `io.github.terrydaktal.whisperkeyboard` package ID:

1. Install the unified APK in place with `adb -s DEVICE install -r whisper-keyboard-debug.apk` (your existing IME enablement, microphone permission, and saved Whisper URL are preserved).
2. Grant `WRITE_SECURE_SETTINGS` to `io.github.terrydaktal.whisperkeyboard` using the ADB command above.
3. Enable **Voice Keyboard** in **Settings → Accessibility**.
4. Uninstall the old **Dictation Bridge** app (`adb -s DEVICE uninstall io.github.terrydaktal.ramblerbridge`), and optionally **PixelBoard** (`adb -s DEVICE uninstall com.akshaykadam.pixelboard`).
5. Tap your keyboard’s mic once and select **Voice Keyboard** as the default handler.
