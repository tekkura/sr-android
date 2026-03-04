# Migration Guide: TensorFlow Lite to LiteRT

This guide outlines the steps required to migrate the project's machine learning implementation from TensorFlow Lite to LiteRT. This migration ensures the project uses the latest supported on-device ML runtime and hardware acceleration APIs.
Since LiteRT does not have tasks-vision implementation, we would have to migrate to MediaPipe, as suggested by Google's official documentation [TensorFlow Lite is now LiteRT](https://developers.googleblog.com/tensorflow-lite-is-now-litert/)
MediaPipe contains LiteRT so no additional explicit LiteRT dependencies are required - everything will be covered by MediaPipe.

## Checklist

### Phase 1: Inventory & Discovery
- **Inventory all TFLite call sites**: Map all locations where models are loaded or inference is performed.
    - Record module and file path.
    - Record model asset/file used.
    - Document input preprocessing path (e.g., CameraX to TensorImage).
    - Document inference invocation path.
    - Document output postprocessing path (e.g., Detection results to UI).
    - Note threading and performance assumptions (e.g., executor usage, GPU delegate).
    - **Complete Inventory Document**: Create [TFLiteInventory.md](./TFLiteInventory.md) containing full details.

### Phase 2: Dependency Migration
- **Update `libs.versions.toml`**:
    - Add LiteRT versions.
    - Replace `org.tensorflow:tensorflow-lite-*` with `com.google.mediapipe:*` as recommended by Google (see below).
- **Update module-level `build.gradle.kts`**:
    - Update dependency references to use the new MediaPipe entries.

### Phase 3: Code Migration
- **Refactor Imports**: Update all `org.tensorflow.lite.*` imports to their MediaPipe equivalents.
- **Update Model Loading**: Migrate from `ObjectDetector` / `Interpreter` to the new MediaPipe Task or Runtime APIs.
- **Update Image Processing**: Migrate `TFLite Support` usage (e.g., `ImageProcessor`, `TensorImage`) to MediaPipe Support.
- **Update Acceleration Logic**: Migrate GPU Delegate and NNAPI usage to MediaPipe Delegates.

## Resources
- [TensorFlow Lite is now LiteRT](https://developers.googleblog.com/tensorflow-lite-is-now-litert/)
