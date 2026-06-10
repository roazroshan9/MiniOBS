# Mini OBS – Android Live Streaming App

A lightweight OBS-style live streaming app for Android, built in Java. Stream directly to YouTube or any RTMP endpoint, receive a game stream from another phone on the same WiFi, overlay your camera as a facecam, mix audio, and switch scenes — all from your phone.

---

## Features

| Feature | Details |
|---|---|
| **Local RTMP Receiver** | `ServerSocket` on port 1935 — receive game video/audio from another phone on the same WiFi |
| **Camera Overlay (Facecam)** | Draggable + resizable camera preview using Camera2 API. Drag anywhere, resize from the bottom-right corner |
| **Audio Mixer** | Independent sliders for Microphone (0–200%) and Game Audio (0–200%) |
| **Scene Manager** | Switch between *Starting Soon*, *Gameplay*, and *Be Right Back* scenes with animated transitions |
| **RTMP Streamer** | Streams to YouTube (or any RTMP endpoint) via [RootEncoder](https://github.com/pedroSG94/RootEncoder) library |

---

## Requirements

- Android 8.0+ (API 26+)
- Camera + Microphone permissions
- Both phones on the same WiFi network

---

## Build Instructions

### Option A — AndroidIDE (on your phone)

1. Open AndroidIDE and clone this repo:
   ```
   git clone https://github.com/YOUR_USERNAME/MiniOBS.git
   ```
2. Open the `MiniOBS` folder as a project.
3. AndroidIDE will sync Gradle automatically.
4. Hit **Run** to build and install the APK.

### Option B — Android Studio (on a PC)

1. Clone the repo:
   ```bash
   git clone https://github.com/YOUR_USERNAME/MiniOBS.git
   cd MiniOBS
   ```
2. Open in Android Studio → **File → Open → MiniOBS**
3. Wait for Gradle sync to finish.
4. Connect your device and click **Run ▶**

### Option C — Command Line

```bash
git clone https://github.com/YOUR_USERNAME/MiniOBS.git
cd MiniOBS
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Setup & Usage

### Step 1 — Configure RTMP (Streaming Phone)
1. Launch Mini OBS.
2. Tap **⚙ Settings**.
3. Enter your YouTube RTMP URL:
   ```
   rtmp://a.rtmp.youtube.com/live2
   ```
4. Enter your **Stream Key** (from YouTube Studio → Go Live → Stream Key).
5. Tap **Save**.

### Step 2 — Connect Game Phone
On the game/source phone:
- Use any RTMP streaming app (e.g. Larix Broadcaster, StreamLabs Mobile).
- Set the RTMP destination to:
  ```
  rtmp://<Mini OBS Phone IP>:1935/live
  ```
  (Your IP is shown in the Settings screen.)
- Start streaming from the game phone.

### Step 3 — Go Live
1. Back in Mini OBS, tap **▶ Go Live**.
2. The status indicator turns **🔴 LIVE**.
3. Switch scenes using the scene buttons.
4. Drag the facecam overlay to reposition it.
5. Adjust Mic and Game audio sliders in real time.

---

## Project Structure

```
MiniOBS/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml         # All permissions declared here
│       ├── java/com/miniobs/app/
│       │   ├── MainActivity.java        # Main UI + service bindings
│       │   ├── StreamSetupActivity.java # RTMP config screen
│       │   ├── StreamService.java       # Foreground streaming service (RootEncoder)
│       │   ├── RtmpReceiverService.java # Local RTMP listener (port 1935)
│       │   ├── CameraOverlayView.java   # Draggable/resizable Camera2 preview
│       │   ├── AudioMixer.java          # PCM audio volume control
│       │   ├── SceneManager.java        # Scene switching with ViewFlipper
│       │   └── StreamConfig.java        # Singleton config (URL, key, volumes)
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_stream_setup.xml
│           │   ├── scene_starting_soon.xml
│           │   ├── scene_gameplay.xml
│           │   └── scene_brb.xml
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               └── themes.xml
├── build.gradle                         # Project-level Gradle
├── app/build.gradle                     # App-level Gradle (dependencies)
├── settings.gradle
└── gradle.properties
```

---

## Dependencies

```groovy
// app/build.gradle
implementation 'com.github.pedroSG94.RootEncoder:library:2.4.5'  // RTMP streaming
implementation 'com.github.bumptech.glide:glide:4.16.0'           // Scene images
implementation 'com.google.android.material:material:1.10.0'      // UI components
```

---

## Permissions Used

| Permission | Purpose |
|---|---|
| `CAMERA` | Camera overlay (facecam) |
| `RECORD_AUDIO` | Microphone input for audio mix |
| `INTERNET` | RTMP output to YouTube |
| `FOREGROUND_SERVICE` | Keep stream alive in background |
| `WAKE_LOCK` | Prevent phone sleep during stream |

---

## Push to GitHub

```bash
cd MiniOBS
git init
git add .
git commit -m "Initial commit: Mini OBS Android app"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/MiniOBS.git
git push -u origin main
```

---

## License

MIT
