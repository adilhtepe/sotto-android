# VBAN Transmitter (Android)

Streams audio from an Android phone to any VBAN receiver (VB-Audio VoiceMeeter,
VBAN Receptor, `vban_receptor`, etc.) over UDP.

## Features
- Sources: **media audio (default)**, all device audio (media + games + unclassified, the most Android lets apps capture), or microphone. Device sources need Android 10+ and the screen-capture permission; the phone keeps playing as normal
- 16-bit PCM, 8 k – 48 kHz, mono or stereo, software gain, level meter
- Runs as a foreground service, so streaming continues with the screen off
- Packets: 256 frames each (512 B mono / 1024 B stereo + 28 B header)

## Build
Open the repository in Android Studio, or from the command line (the app builds the SDK module `:vban` from source):

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Use
1. On the receiver, enable VBAN and note its IP and port (default 6980) and the stream name to accept.
2. In the app enter the IP, port and the same stream name, then tap **Start streaming**.
3. Phone and receiver must be on the same network; the receiver's firewall must allow UDP on the port.

## Notes
- Android never exposes notifications, calls or assistant audio to capture apps. Device audio only captures apps that allow capture (some apps, e.g. DRM/call audio, are excluded by Android).
- VBAN is unencrypted UDP; use it on trusted networks.
- Code: `Vban.kt` (protocol), `AudioStreamService.kt` (capture + send), `MainActivity.kt` (UI).
