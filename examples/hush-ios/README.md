# Hush iOS Example

A pure Swift iOS application demonstrating the Hush! silence detection engine. This mirrors the Android example (`hush-android`) but adapted for iOS — no JNI, no KMP, Swift calls C directly via bridging header.

---

## Architecture Comparison

| Android | iOS |
|---------|-----|
| `:hush` library module (AAR) | Swift wrappers in `Sources/hush/` |
| `HushJni.cpp` (269 lines) | **DELETED** — not needed |
| `external fun` + `System.loadLibrary()` | Direct C calls via bridging header |
| NDK cross-compilation | CMake + Xcode iOS SDK |
| Compose UI | SwiftUI |
| `MainActivity.kt` | `ContentView.swift` |

### Why iOS is Simpler

Android requires a JNI bridge (`HushJni.cpp`) because:
- Kotlin runs on the JVM (or ART)
- C++ code runs natively
- JNI is the glue between JVM and native

iOS doesn't need this because:
- Swift can call C functions directly
- The bridging header tells Swift about the C API
- No intermediate layer needed

---

## Project Structure

```
hush-ios/
├── build-hush.sh                    # Cross-compile hush.cpp for iOS arm64
├── hush-ios.xcodeproj/             # Xcode project
├── Sources/
│   ├── HushBridgingHeader.h         # Imports hush_api.h into Swift
│   ├── hush/                        # Swift wrappers (mirrors :hush Android module)
│   │   ├── Hush.swift               # Mirrors Hush.kt
│   │   ├── AudioPlayer.swift        # Mirrors AudioPlayer.kt
│   │   └── AudioRecorder.swift      # Mirrors AudioRecorder.kt
│   └── app/                         # SwiftUI app (mirrors :app Android module)
│       ├── hush_iosApp.swift        # @main entry point
│       └── ContentView.swift        # Core UI
├── Libs/ios-arm64/                  # Pre-compiled static libraries
│   ├── libhush_core.a
│   └── libhush_ffi.a
└── Resources/
    └── Info.plist
```

---

## Build Requirements

- Xcode 16.4+ (for iPhone 8 support)
- CMake 3.10+ (comes with Xcode)
- iPhone 8 with iOS 16.7.16

---

## How to Build

### 1. Cross-compile hush.cpp for iOS

```bash
cd examples/hush-ios
./build-hush.sh
```

This produces `Libs/ios-arm64/libhush_core.a` and `Libs/ios-arm64/libhush_ffi.a`.

### 2. Build the iOS app

**Via Xcode:**
```bash
open hush-ios.xcodeproj
# Select your iPhone 8 target
# Build & Run
```

**Via CLI (with Xcode 16.4 for iPhone 8):**
```bash
DEVELOPER_DIR=/Applications/Xcode-16.4.0.app/Contents/Developer \
  xcodebuild -project hush-ios.xcodeproj -scheme hush-ios \
  -sdk iphoneos -destination 'id=eb8e445b47d75faf332e2fcbaa0cd4efa68e62f0' \
  -derivedDataPath build/ios_device_data -allowProvisioningUpdates \
  -configuration Debug build
```

### 3. Install on iPhone 8

```bash
/opt/homebrew/venvs/pymobiledevice3/bin/pymobiledevice3 \
  apps install build/ios_device_data/Build/Products/Debug-iphoneos/hush-ios.app
```

---

## File Mapping: Android ↔ iOS

```
Android                              iOS
─────────────                        ─────────────
hush/src/main/cpp/HushJni.cpp   →   DELETED (not needed)
hush/src/main/java/.../Hush.kt  →   Sources/hush/Hush.swift
hush/src/main/java/.../         →   Sources/hush/AudioPlayer.swift
    AudioPlayer.kt
hush/src/main/java/.../         →   Sources/hush/AudioRecorder.swift
    AudioRecorder.kt
app/src/main/java/.../          →   Sources/app/ContentView.swift
    MainActivity.kt
app/build.gradle.kts            →   hush-ios.xcodeproj/project.pbxproj
hush/build.gradle.kts           →   (static libs linked via OTHER_LDFLAGS)
```

---

## Key Differences from Android

### 1. No JNI Bridge

Android needs `HushJni.cpp` to map JNI entrypoints to C++ functions:
```cpp
// Android: JNI bridge (269 lines)
JNIEXPORT jlong JNICALL Java_klama_hush_Hush_hushCreate(
    JNIEnv *env, jclass clazz, jdouble thresholdDb, ...) {
    hush_config_t config;
    config.threshold_db = thresholdDb;
    return reinterpret_cast<jlong>(hush_engine_create(config));
}
```

iOS doesn't need this. Swift calls C directly:
```swift
// iOS: Direct C call (1 line)
let handle = hush_engine_create(cConfig)
```

### 2. Bridging Header vs JNI Loading

Android loads native libraries at runtime:
```kotlin
// Android: Runtime loading
companion object {
    init {
        System.loadLibrary("hush_core")
        System.loadLibrary("hush_ffi")
        System.loadLibrary("klama_hush_android")
    }
}
```

iOS links at compile time via bridging header:
```c
// iOS: Compile-time linking
#include "ffi/hush_api.h"
```

### 3. Handle Types

Android uses `Long` (raw pointer as integer):
```kotlin
// Android: Unsafe pointer as Long
private var handle: Long = 0
handle = hushCreate(config.thresholdDb, ...)
val engine = reinterpret_cast<hush_engine_t*>(handle)
```

iOS uses `OpaquePointer?` (type-safe optional pointer):
```swift
// iOS: Type-safe pointer
private var handle: OpaquePointer?
handle = hush_engine_create(cConfig)
```

---

## Current Scope

Implemented (feature parity with `hush-android`):
- File picker (single file mode) — WAV/MP3 decode via `AVAudioFile`
- Batch directory processing with results card + failures list
- Original/Processed playlist switch (input dir vs output dir scans)
- Real-time recording with VU meter, pressure & degradation indicators
- Audio playback with playlist, pause/resume, auto-advance
- Statistics display (single-file, batch, recorder — isolated per tab)

---

## Dependencies

**Static libraries (pre-compiled):**
- `libhush_core.a` — PCM silence detection engine
- `libhush_ffi.a` — C API bridge

**System frameworks:**
- `CoreAudio` — Audio hardware access
- `CoreFoundation` — Core Apple types
- `AudioUnit` — Audio processing

---

## Testing on iPhone 8

Since Xcode 27 cannot see iPhone 8, use Xcode 16.4:

```bash
# Build
DEVELOPER_DIR=/Applications/Xcode-16.4.0.app/Contents/Developer \
  xcodebuild -project hush-ios.xcodeproj -scheme hush-ios \
  -sdk iphoneos -derivedDataPath build/ios_device_data \
  -allowProvisioningUpdates -configuration Debug build

# Install
/opt/homebrew/venvs/pymobiledevice3/bin/pymobiledevice3 \
  apps install build/ios_device_data/Build/Products/Debug-iphoneos/hush-ios.app

# Verify
/opt/homebrew/venvs/pymobiledevice3/bin/pymobiledevice3 usbmux list
```
