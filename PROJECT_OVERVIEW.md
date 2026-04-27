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

### 2. FFI Bridge (Middleware Layer)
The `hush_api` provides an `extern "C"` boundary for external integration.
*   **Use Case:** Direct memory-buffer processing for Android (Kotlin/Native), Python, or Go applications.
*   **Features:** Handle-based state management, explicit flush for tail-audio, and real-time stats.

### 3. Codec Bridge (Legacy/Transport Layer)
The `AudioProcessor` provides a high-level bridge between compressed files (MP3/WAV) and the core engine.
*   **Normalization:** All inputs are resampled and mixed down to **16kHz Mono S16** (the "Whisper standard") before processing.
*   **I/O:** Leverages FFmpeg for robust decoding and encoding.

## Data Flow Diagram

```
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
*   **CLI Tool (`hush_remover`):** For batch processing and testing.

## Future Roadmap

*   **Advanced VAD:** Integration of machine-learning based Voice Activity Detection (e.g., Silero VAD).
*   **SIMD Optimization:** Accelerating RMS calculations using NEON/AVX instructions.
*   **Multi-channel support:** Independent silence detection per-channel for multi-mic arrays.
