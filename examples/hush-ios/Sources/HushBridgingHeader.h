#ifndef HushBridgingHeader_h
#define HushBridgingHeader_h

// Import the Hush! C API directly into Swift
// This is how Swift calls C functions — no JNI, no CInterop, no magic.
// The bridging header tells the Swift compiler:
// "These C functions exist. Generate Swift interfaces for them automatically."

#include "ffi/hush_api.h"

#endif
