# Hush Android Example

This example demonstrates how to integrate the Hush silence removal engine into a native Android application. It features a modular structure with a dedicated library module for the Hush engine and its JNI bindings.

## Features

- **Modular Architecture**: Uses a `:hush` library module to separate the engine logic from the UI.
- **File Picker**: Select `.wav` files directly from your device using the system file selector.
- **Processing Presets**: Choose between different levels of silence detection aggression (Slightly to More Aggressive).
- **Two-Step Workflow**: Select a file, configure the engine, and then click "Start Hush" to process.
- **Export**: Save the "hushed" audio back to your device as a valid `.wav` file.

## Build Requirements

- Android SDK 26+
- NDK 28.2.13676358 (or specify your version in `build.gradle.kts`)
- Gradle 8.13+
- AGP 8.13.2

## How to Run

1. Open the project in Android Studio or use the command line.
2. Build and install the app:
   ```bash
   ./gradlew installDebug
   ```
3. Launch "Hush Android Example" on your device.
4. Select a mono 16kHz `.wav` file, choose an aggression level, and press **Start Hush**.

## Implementation Details

- **Hush Wrapper**: Located in `hush/src/main/java/klama/hush/Hush.kt`.
- **JNI Layer**: Located in `hush/src/main/cpp/HushJni.cpp`.
- **C++ Core**: Linked as a static library during the CMake build process.

