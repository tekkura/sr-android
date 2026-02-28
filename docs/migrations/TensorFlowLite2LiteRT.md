# Migration Guide: TensorFlow Lite to LiteRT

This guide outlines the steps required to migrate the project's machine learning implementation from TensorFlow Lite to LiteRT. This migration ensures the project uses the latest supported on-device ML runtime and hardware acceleration APIs.

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
    - Replace `org.tensorflow:tensorflow-lite-*` artifacts with `com.google.ai.edge.litert:*` (or Play Services equivalent).
- **Update module-level `build.gradle.kts`**:
    - Update dependency references to use the new LiteRT entries.

### Phase 3: Code Migration
- **Refactor Imports**: Update all `org.tensorflow.lite.*` imports to their LiteRT equivalents.
- **Update Model Loading**: Migrate from `ObjectDetector` / `Interpreter` to the new LiteRT Task or Runtime APIs.
- **Update Image Processing**: Migrate `TFLite Support` usage (e.g., `ImageProcessor`, `TensorImage`) to LiteRT Support.
- **Update Acceleration Logic**: Migrate GPU Delegate and NNAPI usage to LiteRT Delegates (preferably via Google Play services).

## Resources
- [LiteRT on Android Documentation](https://ai.google.dev/edge/lite/android)
- [LiteRT Task Vision Migration](https://ai.google.dev/edge/lite/android/play_services)