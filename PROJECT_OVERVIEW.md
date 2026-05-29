# Project Overview: Hush! — PCM Silence Detection Engine

## Introduction

"Hush!" is designed as an upstream audio filtering component for speech systems.

Its responsibility is narrow and explicit: detect silence in PCM streams and preserve only audible segments for downstream consumers such as speech recognition pipelines, storage systems, or real-time applications.

The project is intentionally structured around strict component boundaries so the core engine remains dependency-light, reusable, and provider-neutral.

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
*   **Features:** Handle-based state management, explicit flush for tail-audio, real-time telemetry (reduction %, samples removed), and lock-free backpressure metrics (buffer pressure, latency estimate, degradation state).
*   **Safety:** Thread-safe state transitions via atomic flags, opaque pointer handles, and lock-free atomic telemetry queries.

### 4. Codec Bridge (Legacy/Transport Layer)
The `AudioProcessor` provides a high-level bridge between compressed files (MP3/WAV) and the core engine.
*   **Normalization:** All inputs are resampled and mixed down to **16kHz Mono S16** (a common format for speech recognition pipelines) before processing.
*   **I/O:** Leverages FFmpeg for robust decoding and encoding.
*   **Performance:** Now includes high-resolution timing to track processing overhead per file.

### 5. Process Management Layer
Provides background detachment and state tracking for long-running batch jobs.
*   **Detachment:** Fork-based backgrounding with I/O redirection to logs.
*   **State Persistence:** A file-based `StateManager` tracks PID, progress, and status across sessions.
*   **Parallel Playback:** Supports "Process-and-Play" mode where the silencer runs in the background while an interactive player monitors the output in real-time.

## Data Flow Diagram

```
[MP3/WAV File] -> [FFmpeg Decoder] -> [Normalization (16kHz Mono)] 
                                               ↓
[Real-time Mic] -> [PCM Stream] ------> [SilenceDetector Core]
                                               ↓
[ASR Engine]   <- [Clean PCM Buffer] <- [Hush! Logic (Keep/Cut)]
```

## Real-Time Threading Design & Safety

To ensure absolute audio stream stability, `Hush!` defines a strict boundaries architecture for real-time thread safety. 

### Audio Callback Thread Boundary
The capture/playback loop runs on miniaudio's high-priority audio callback thread. This thread is subject to hard real-time requirements where any scheduling delay or execution pause causes audible dropouts (glitches) or data loss.

To guarantee safety, the callback path enforces these rules:
1. **Zero Dynamic Memory Allocation**: No heap allocations, deallocations, or container resizing (`vector::resize`) are allowed. All required buffers must be preallocated during the initialization/startup phase (`AudioRecorder::start()`).
2. **Lock-Free Execution**: No standard mutexes or blocking synchronization primitives are used. State tracking uses lock-free atomic variables (`std::atomic`).
3. **No I/O or Logging**: Disk writes, network calls, and string logging (such as `std::ostream` or formatting) are strictly prohibited on the hot path as they involve implicit locks and OS system calls.

### Real-Time Pipeline Isolation (Hush v2 RT)
Hush v2 RT decouples hardware timing fluctuations from heavy computational processing and disk I/O using a Single Producer Single Consumer (SPSC) lock-free ring buffer:
```
[RT Audio Thread Callback]
            ↓ (Push raw samples - lock-free)
   [Lock-Free SPSC Ring Buffer]
            ↓ (Pop raw samples - lock-free)
[Asynchronous Worker Thread] 
            ↓ (SilenceDetector DSP & File I/O)
```
By restricting the RT Audio Thread exclusively to copying raw samples to a cache-aligned `RingBuffer` (using bitwise masking and unbounded indices), hardware timing fluctuations are fully isolated from processing and disk write latencies.

### Flow Control & Graceful Degradation State Machine
The `FlowController` monitors queue saturation dynamically and manages three backpressure states:
* **NORMAL** (Saturation < 50%): Performs full RMS silence detection.
* **DEGRADED** (Saturation 50% - 80%): Silence detection degrades to **subsampled RMS** (skipping every second sample), reducing CPU arithmetic by 50% and recovering ~36% CPU cycles.
* **EMERGENCY** (Saturation > 80%): Silence detection degrades to a cheap **subsampled peak envelope check** (skipping to check every fourth sample against a cached linear amplitude threshold), bypassing all multiplications, divisions, square roots, and logarithms in the loop, recovering over ~73% CPU cycles.
* **Hysteresis release thresholds** (70% for Emergency-to-Degraded and 40% for Degraded-to-Normal) prevent rapid back-and-forth state oscillations.


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

