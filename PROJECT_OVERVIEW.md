# Project Overview: Hush! - Silence Detection Component

## Introduction

`Hush!` has evolved from a simple file-remover tool into a specialized PCM preprocessing engine for speech intelligence pipelines. Its primary role is to serve as an upstream filter for Automatic Speech Recognition (ASR) systems, ensuring that only audible segments are passed to the inference engine. This reduces computational waste and improves transcription efficiency.

## System Architecture

The project is structured into three distinct layers to maximize interoperability and performance.

### 1. Core Engine (PCM Layer)
The heart of `Hush!` is the `SilenceDetector`. This layer is strictly **PCM-only** and has zero dependencies on multimedia frameworks like FFmpeg.
*   **Responsibility:** RMS calculation, state-machine transitions (Silent <-> Active), and telemetry.
*   **Format:** Operates on raw S16_LE Mono PCM.
*   **Design:** Sample-rate agnostic and streaming-capable.

### 2. Real-time I/O Layer (Voice SDK)
High-level wrappers around `miniaudio` for direct hardware interaction.
*   **AudioRecorder:** Captures microphone input and applies `SilenceDetector` in real-time within the audio callback. This ensures that only audible audio is ever written to disk or passed to the application.
*   **AudioPlayer:** Provides thread-safe, asynchronous playback for monitoring or reviewing processed audio.

### 3. FFI Bridge (Middleware Layer)
The `hush_api` provides an `extern "C"` boundary for external integration, facilitating usage in higher-level languages.
*   **Use Case:** Real-time memory-buffer processing for Android (Kotlin/Native), JVM, Python, or Go applications.
*   **Features:** Handle-based state management, explicit flush for tail-audio, and real-time telemetry (reduction %, samples removed).
*   **Safety:** Thread-safe state transitions via atomic flags and opaque pointer handles.

### 4. Text-to-Speech Engine (Synthesis Layer)
A high-performance synthesis engine based on ONNX Runtime.
*   **Architecture:** Implements a diffusion-based TTS pipeline with duration prediction and vocoding.
*   **Capability:** Multi-lingual support with customizable voice styles and speed control.
*   **Integration:** Integrated into the core library as `TtsEngine`, using the project's native logging and error handling.

### 5. Codec Bridge (Legacy/Transport Layer)
The `AudioProcessor` provides a high-level bridge between compressed files (MP3/WAV) and the core engine.
*   **Normalization:** All inputs are resampled and mixed down to **16kHz Mono S16** (the "Whisper standard") before processing.
*   **I/O:** Leverages FFmpeg for robust decoding and encoding.
*   **Performance:** Now includes high-resolution timing to track processing overhead per file.

### 6. Process Management Layer
Provides background detachment and state tracking for long-running batch jobs or synthesis tasks.
*   **Detachment:** Fork-based backgrounding with I/O redirection to `hush.log`.
*   **State Persistence:** A file-based `StateManager` tracks PID, progress, and status across sessions.
*   **Parallel Playback:** Supports "Process-and-Play" mode where the engine runs in the background while an interactive player monitors the output in real-time (supported for both silence removal and TTS).

## Data Flow Diagram

```
[Text Input] -> [TtsEngine (ONNX)] -> [Synthesized PCM]
                                           ↓
[MP3/WAV File] -> [FFmpeg Decoder] -> [Normalization (16kHz Mono)] 
                                               ↓
[Real-time Mic] -> [PCM Stream] ------> [SilenceDetector Core]
                                               ↓
[ASR Engine]   <- [Clean PCM Buffer] <- [Hush! Logic (Keep/Cut)]
```

## Key Technical Specifications

*   **Canonical Format:** S16_LE, Mono, 16kHz (normalized internally).
*   **Analysis Window:** 1152 samples (~72ms at 16kHz) for stable RMS detection.
*   **Latency:** Minimal (~72ms processing window delay).
*   **Language:** C++17 with C-Linkage FFI.

## Deployment & Integration

*   **Static Library (`libhush_core.a`):** For C++ projects requiring direct embedding.
*   **Shared Library (`libhush_ffi.so`):** For dynamic loading and cross-language integration.
*   **CLI Tool (`hush`):** A robust utility for batch silence removal, interactive playback, and background task management.
*   **Terminal Recorder Example (`hush-terminal-recorder`):** A real-time recording and playback tool demonstrating C API integration and `miniaudio`.

## Future Roadmap

*   **Advanced VAD:** Integration of machine-learning based Voice Activity Detection (e.g., Silero VAD).
*   **SIMD Optimization:** Accelerating RMS calculations using NEON/AVX instructions.
*   **Multi-channel support:** Independent silence detection per-channel for multi-mic arrays.

