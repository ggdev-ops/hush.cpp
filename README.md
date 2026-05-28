# Hush! — PCM Silence Detection Engine

## Project Description

"Hush!" is a high-performance C++ PCM silence detection engine for speech intelligence pipelines.

It removes silence from audio streams before downstream processing, reducing compute cost, storage overhead, and unnecessary inference work.

Built around a PCM-first architecture, "Hush!" supports both batch processing and real-time streaming while remaining lightweight, deterministic, and easy to integrate across platforms.

## The Hush Contract

"Hush!" is a PCM-first silence detection engine for speech pipelines.

### Inputs
Hush accepts:
- Signed 16-bit linear PCM ("S16_LE")
- Mono audio
- Streaming chunks or full audio buffers

Compressed formats such as MP3 or WAV must be decoded before reaching the core engine.

### Guarantees
Hush guarantees:
- Deterministic silence detection on PCM input
- Stateful chunk-by-chunk processing for real-time streams
- Explicit flush support for tail-audio completion
- Processing telemetry, including samples removed and reduction percentage
- No dependency on model runtimes or inference frameworks inside the core engine

### Non-Goals
Hush does not:
- Perform speech-to-text transcription
- Perform text-to-speech synthesis
- Manage AI model lifecycle or inference sessions
- Depend on ONNX Runtime or other ML frameworks in the core domain

These capabilities may exist around Hush, but they are not part of the Hush core contract.

## Core Features

*   **Real-Time Voice SDK:** High-level `AudioRecorder` and `AudioPlayer` wrappers that enable silence-free voice capture and asynchronous playback with minimal latency.
*   **PCM-Only Core Engine:** Standalone, sample-rate agnostic `SilenceDetector` that operates directly on raw S16_LE Mono PCM buffers.
*   **Real-Time Streaming:** Support for chunked audio processing with state preservation and explicit flush mechanisms.
*   **Professional FFI Bridge:** A clean `extern "C"` API for seamless integration with Kotlin Native, Python, or other high-level languages.
*   **PCM Normalization:** Internal normalization bridge forces 16kHz Mono S16 output, a common format for speech recognition pipelines.
*   **Legacy MP3 Support:** Maintains a CLI bridge for processing existing MP3 files.
*   **Efficient RMS Analysis:** Optimized silence detection logic with timing-aware state machines and adjustable aggression levels.

## Requirements

*   A C++17 compliant compiler
*   [CMake](https://cmake.org/) (version 3.10 or higher)
*   [FFmpeg development libraries](https://ffmpeg.org/) (required for the CLI legacy bridge only).

## Building the Project

```bash
mkdir build && cd build
cmake ..
make
```

### Build Targets

Upon successful compilation, the following targets are created:
*   `libhush_core.a`: The standalone PCM engine (strictly PCM, no dependencies).
*   `libhush_ffi.a`: Static library for FFI integration (C-API).
*   `hush`: The CLI tool for MP3/WAV file processing (requires FFmpeg).
*   `hush-terminal-recorder`: A real-time voice recorder showing live C API usage with `miniaudio`.

## Usage (CLI)

The `hush` tool supports three modes of operation: Silence Removal, Interactive Playback, and Background Management.

### 1. Silence Removal
Process audio files or directories to remove silence.

```bash
./hush [options] <input_path> <output_path>
```

**Options:**
*   `-t <dB>`, `--threshold <dB>`: Silence threshold (default: `-40.0`).
*   `-a <level>`, `--aggression <level>`: Trimming sensitivity (default: `1.0`).
*   `--play`: Automatically start parallel playback of processed files.
*   `--detach`: Run the silencer in the background.
*   `--dry-run`: Analyze reductions without writing output.
*   `-q`, `--quiet`: Suppress all output except errors.

### 2. Audio Playback
Play processed files or entire directories with interactive controls.

```bash
./hush play <path>
```

**Controls:**
*   `Space`: Pause / Resume.
*   `Enter`: Stop playback.
*   `>` / `<`: Next / Previous track (when playing a directory).

### 3. Background Management
Monitor or stop processes started with `--detach`.

```bash
./hush status   # Show real-time progress of the background job
./hush stop     # Gracefully terminate the background job
```

## Usage (Terminal Recorder)

Record directly from your microphone with real-time silence removal and performance metrics:

```bash
./hush-terminal-recorder <output.wav> [threshold_db] [aggression] [--play] [--stop-record <seconds>]
```

> **Note for Termux users:** To record audio on Android via Termux, you must install the [Termux:API](https://wiki.termux.com/wiki/Termux:API) app, install the `termux-api` package (`pkg install termux-api`), and ensure Microphone permissions are granted to the Termux app.

**Examples:**
- `... --play`: Plays back the processed audio immediately after recording.
- `... --stop-record 10`: Automatically stops recording after 10 seconds.


## FFI Integration

For speech intelligence pipelines, use the `hush_api.h` interface:

```c
hush_config_t config = {-40.0, 1.0, 16000};
hush_engine_t* engine = hush_engine_create(config);

// Feed chunked PCM data
int out_samples = capacity; // Must initialize with buffer capacity
hush_engine_process(engine, input_ptr, samples, output_ptr, &out_samples);

// Get processing stats
hush_stats_t stats = hush_engine_get_stats(engine);
printf("Reduction: %.2f%%\n", stats.reduction_percentage);

hush_engine_destroy(engine);
```

## Project Structure

*   `core/`: The canonical PCM processing logic (Silence detection, RMS).
*   `ffi/`: The `extern "C"` bridge for interoperability.
*   `codecs/`: Legacy FFmpeg bridge for MP3/WAV file I/O.
*   `cli/`: Command-line interface implementation.
