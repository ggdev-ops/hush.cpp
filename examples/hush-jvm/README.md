# Hush JVM Example

A desktop implementation of the Hush silence removal engine using Kotlin and Swing. This example provides a graphical interface for processing audio files on desktop platforms (Linux/Windows/macOS).

## Features

- **Swing GUI**: Native windowing interface for easy interaction.
- **File Dialogs**: Standard system dialogs for opening and saving `.wav` files.
- **Silence Presets**: Adjustable aggression levels to control how much silence is removed.
- **Dry Run Flow**: Select your file and configuration before committing to the processing run.
- **WAV Export**: Exports the processed audio as a playable 16kHz mono `.wav` file.

## Build Requirements

- JDK 21+ (aligned with project toolchain)
- CMake (for building the native JNI library)
- Gradle

## How to Run

1. Build the project and the native library:
   ```bash
   gradle build
   ```
2. Run the application:
   ```bash
   gradle run
   ```
3. A window will appear. Select a `.wav` file (preferably 16kHz mono), choose your settings, and click **Start Hush**.

## Implementation Details

- **Native Loading**: The engine automatically attempts to load `libklama_hush_jni` from the system path or extracts it from resources.
- **WAV Handling**: Includes a robust chunk-based parser to find the `data` segment in various WAV formats.
- **Threading**: Audio processing runs on a background thread to keep the Swing UI responsive.

