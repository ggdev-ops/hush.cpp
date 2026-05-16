# Hush! - PCM Silence Detection & Preprocessing Engine

## Project Description

`Hush!` is a high-performance C++ engine designed for real-time speech intelligence pipelines (such as Whisper or client-whisper). It identifies and eliminates silent segments from audio streams, significantly reducing processing time and storage requirements for downstream STT (Speech-to-Text) systems.

Unlike traditional file-based tools, `Hush!` is built as a **PCM-first streaming component**, making it suitable for both batch file processing and live recording environments.

## Core Features

*   **Integrated TTS Engine:** High-quality, multi-lingual Text-to-Speech synthesis using ONNX Runtime. Supports 30+ languages and custom voice styles.
*   **Real-Time Voice SDK:** High-level `AudioRecorder` and `AudioPlayer` wrappers that enable silence-free voice capture and asynchronous playback with minimal latency.
*   **PCM-Only Core Engine:** Standalone, sample-rate agnostic `SilenceDetector` that operates directly on raw S16_LE Mono PCM buffers.
*   **Real-Time Streaming:** Support for chunked audio processing with state preservation and explicit flush mechanisms.
*   **Professional FFI Bridge:** A clean `extern "C"` API for seamless integration with Kotlin Native, Python, or other high-level languages.
*   **Whisper Optimization:** Internal normalization bridge forces 16kHz Mono S16 output, the industry standard for speech-to-text.
*   **Legacy MP3 Support:** Maintains a CLI bridge for processing existing MP3 files.
*   **Efficient RMS Analysis:** Optimized silence detection logic with timing-aware state machines and adjustable aggression levels.

## Requirements

*   A C++17 compliant compiler
*   [CMake](https://cmake.org/) (version 3.15 or higher)
*   [FFmpeg development libraries](https://ffmpeg.org/) (required for the CLI legacy bridge).
*   [ONNX Runtime](https://onnxruntime.ai/) (required for TTS features).
*   [nlohmann/json](https://github.com/nlohmann/json) (required for TTS configuration).

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

The `hush` tool supports four modes of operation: Silence Removal, Interactive Playback, Text-to-Speech, and Background Management.

### 1. Silence Removal
...
### 2. Audio Playback
...
### 3. Text-to-Speech (TTS)
Generate high-quality speech from text input or files.

```bash
./hush tts [options]
```

**Options:**
*   `-t <text>`, `--text <text>`: Direct text input for synthesis.
*   `-i <file>`, `--input <file>`: Input text file path.
*   `-o <file>`, `--output <file>`: Output WAV file (default: `output.wav`).
*   `-v <voice>`, `--voice <voice>`: Voice style name (e.g., `M1`, `F1`) or full path to style JSON.
*   `-s <speed>`, `--speed <speed>`: Speech speed factor (default: `1.05`).
*   `-n <steps>`, `--steps <steps>`: Diffusion steps (default: `8`).
*   `--play`: Play back the synthesized audio immediately after generation.
*   `--detach`: Run the synthesis process in the background.

**Example:**
```bash
./hush tts --text "Hello world" --voice F1 --output hello.wav
```

### 4. Background Management
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
# Hush.cpp

