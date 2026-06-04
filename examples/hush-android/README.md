# Hush Android Example

This example demonstrates how to integrate the **Hush** silence removal engine and its asynchronous **AudioPlayer** into a native Android application. It features a modular structure with a dedicated library module for the engine, JNI bindings, and a premium Compose-based UI supporting single files, batch directories, and format-independent audio decoding.

---

## 🚀 Key Features

* **Modular Architecture**: Uses a `:hush` Android library module to compile the C++ core and package JNI bindings separately from the `:app` module.
* **Dual Processing Modes**:
  * **Single File Mode**: Select, process, and play individual audio files.
  * **Batch Directory Mode**: Select input and output directory trees via Android's Storage Access Framework (SAF). Automatically scans, processes, and saves all files under their original names.
* **Sophisticated Native Audio Recorder**:
  * **Permission-Safe Pipeline**: The library (`AudioRecorder.kt`) handles microphone permission validations internally to prevent native capture initialization crashes if called without access.
  * **Decoupled Activation Flow**: Tightly checks permission on the first click, displaying a "Grant Permission" action. Once permission is acquired, the button upgrades to "Start Recording" (requiring a second click to begin, avoiding race conditions).
  * **Real-time VU Meter**: Computes and displays audio signal dB levels dynamically (polling native FFI every 80ms inside a Compose lifecycle-bound coroutine).
  * **Live Engine Metrics**: Shows real-time audio statistics card containing processed duration, reduction percentage, silent segments detected, RingBuffer pressure, and native flow degradation states.
  * **Auto-Register**: Automatically decodes and registers the recorded file path directly into the Audio Player for instant playback or Hush silence-removal processing.
* **MediaCodec Audio Decoding**: Natively decodes both **MP3** and **WAV** files to raw 16-bit mono PCM buffers, downmixing stereo channels, and dynamically configuring the engine's sample rate (e.g. 44100Hz, 48000Hz) to prevent pitch distortion.
* **Redesigned Playlist Audio Player**:
  * Visual progress slider and real-time elapsed/duration timers.
  * Interactive playlist scrolling list showing folder tracks with active playback highlight.
  * **Previous Track (`SkipPrevious`)** and **Next Track (`SkipNext`)** navigation controls.
  * **JNI-based Auto-Advance**: Automatically advances and plays the next track when the JNI player signals playback is complete.
  * Dynamic cache copying preserving original file extensions (`.mp3` vs `.wav`) to ensure miniaudio decodes formats correctly.

---

## 🛠️ Build Requirements

* Android SDK 26+
* NDK 28.2.13676358 (or specify your version in `build.gradle.kts`)
* Gradle 9.5+
* Kotlin 2.0+

---

## 💻 How to Run

1. Open the `examples/hush-android` directory in Android Studio.
2. Or build and install the debug APK via CLI:
   ```bash
   gradle :app:installDebug
   ```
3. Launch the app on your device, choose **Single File** or **Batch Directory** mode, select your audio resources, and click **Start**.

---

## 🏗️ Implementation Details

* **Native Player Wrapper**: Located in [AudioPlayer.kt](hush/src/main/java/klama/hush/AudioPlayer.kt), loading the native `:klama_hush_android` shared library.
* **Native JNI Bindings**: Located in [HushJni.cpp](hush/src/main/cpp/HushJni.cpp), mapping JNI entrypoints to the C++ core [AudioPlayer](../../src/core/AudioPlayer.cpp).
* **MediaCodec Decoder**: Located in [MainActivity.kt](app/src/main/java/klama/hush/MainActivity.kt), utilizing Android's media APIs to decode MP3/WAV assets to PCM buffers.
