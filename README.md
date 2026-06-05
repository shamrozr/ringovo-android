# Ringovo Android

Native Android wrapper (Capacitor) around **app.ringovo.pk**. It exists for one reason a browser/PWA can't do: route WebRTC **call audio to the phone earpiece** and run the mic through the OS voice-call pipeline.

- Loads the live web app (`server.url = https://app.ringovo.pk`) — no web code is bundled, so the app always shows the latest deploy.
- `MainActivity` forces `MODE_IN_COMMUNICATION` and selects the **built-in earpiece** (Android 12+: `setCommunicationDevice(TYPE_BUILTIN_EARPIECE)`; older: `setSpeakerphoneOn(false)`).
- Requests `RECORD_AUDIO` at runtime so the WebView's `getUserMedia` (mic) works.
- Exposes `window.RingovoAudio.setSpeakerphone(on)` so the in-app Speaker button can flip loudspeaker/earpiece.

## Get the APK (no Android tools needed)
1. Go to the repo's **Actions** tab → the latest **Build Android APK** run.
2. Download the **`ringovo-debug-apk`** artifact → unzip → `app-debug.apk`.
3. On the phone: enable **Install unknown apps** for your browser/files app, then open the APK to install.
4. Launch **Ringovo**, allow the **microphone** permission, log in, and place a call — audio should be on the **earpiece**.

## Rebuild
Push to `main` or run the **Build Android APK** workflow manually (Actions → Run workflow).
