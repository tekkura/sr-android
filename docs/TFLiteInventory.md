# Inventory of TensorFlow Lite Model Load and Inference Call Sites

This document provides a map of all TensorFlow Lite entry points, models, and preprocessing/postprocessing paths as of the start of the LiteRT migration.

## Summary Table

| File                                                                                           | Model Asset                 | Feature                             | Status |
|:-----------------------------------------------------------------------------------------------|:----------------------------|:------------------------------------|:-------|
| `libs/abcvlib/src/main/java/jp/oist/abcvlib/core/inputs/phone/ObjectDetectorData.kt`           | `model.tflite` (default)    | Core Object Detection (Task Vision) | Mapped |
| `libs/abcvlib/src/main/java/jp/oist/abcvlib/core/inputs/phone/ObjectDetectorDataSubscriber.kt` | N/A                         | Subscriber Interface                | Mapped |
| `apps/handsOnApp/src/main/java/jp/oist/abcvlib/handsOnApp/MainActivity.kt`                     | `efficientdet-lite1.tflite` | UI: Object detection display        | Mapped |
| `apps/basicCharger/src/main/java/jp/oist/abcvlib/basiccharger/MainActivity.kt`                 | N/A                         | Behavior: Object detection usage    | Mapped |
| `apps/basicSubscriber/src/main/java/jp/oist/abcvlib/basicsubscriber/MainActivity.kt`           | N/A                         | UI: Object detection display        | Mapped |

---

## Detailed Call Sites

### 1. `libs/abcvlib/src/main/java/jp/oist/abcvlib/core/inputs/phone/ObjectDetectorData.kt`

- **Model Load**:
    - `objectDetector = ObjectDetector.createFromFileAndOptions(context, modelPath, optionsBuilder.build())`
    - `modelPath` defaults to `"model.tflite"` (from `assets`).
- **Input Preprocessing**:
    - `ImageProcessor` is used to rotate the image: `Rot90Op(-rotation / 90)`.
    - `TensorImage` is created from `Bitmap` via `TensorImage.fromBitmap(bitmap)`.
- **Inference Invocation**:
    - `val results = objectDetector.detect(tensorImage)`
- **Output Postprocessing**:
    - `Detection` results are passed to `subscribers` via `onObjectsDetected`.
- **Threading/Performance**:
    - Runs in `customAnalysis` within `ImageData`'s analyzer.
    - Uses `ExecutorService imageExecutor` (passed from `ImageData`).
    - Defaults to 2 threads (`numThreads = 2`).
    - Supports `DELEGATE_CPU`, `DELEGATE_GPU` (via `CompatibilityList`), and `DELEGATE_NNAPI`.

### 2. `libs/abcvlib/src/main/java/jp/oist/abcvlib/core/inputs/phone/ObjectDetectorDataSubscriber.kt`

- **Usage**:
    - Defines the interface for receiving `Detection` results.
    - Imports `org.tensorflow.lite.task.vision.detector.Detection` and `org.tensorflow.lite.support.image.TensorImage`.
- **Inference Invocation**: N/A (Interface only).

### 3. `apps/handsOnApp/src/main/java/jp/oist/abcvlib/handsOnApp/MainActivity.kt`

- **Usage**:
    - Implements `ObjectDetectorDataSubscriber`.
    - `onObjectsDetected` receives `List<Detection> results`.
    - Displays detected object categories and confidence.
- **Inference Invocation**: N/A (Consumer).

### 4. `apps/basicCharger/src/main/java/jp/oist/abcvlib/basiccharger/MainActivity.kt`

- **Usage**:
    - Implements `ObjectDetectorDataSubscriber`.
    - `onObjectsDetected` receives `List<Detection> results`.
    - Uses detection for robot behavior (e.g., following/avoiding detected objects).
- **Inference Invocation**: N/A (Consumer).

### 5. `apps/basicSubscriber/src/main/java/jp/oist/abcvlib/basicsubscriber/MainActivity.kt`

- **Usage**:
    - Implements `ObjectDetectorDataSubscriber`.
    - `onObjectsDetected` receives `List<Detection> results`.
    - Logs detections.
- **Inference Invocation**: N/A (Consumer).

---

## ProGuard Rules

- **File**: `proguard-rules.pro`
- **Rules**:
    - `-keep class org.tensorflow.lite.gpu.** { *; }`
    - `-dontwarn org.tensorflow.lite.gpu.**`

## Assets & Model Files

- **File**: `libs/abcvlib/src/main/assets/model.tflite`
- **File**: `libs/abcvlib/src/main/assets/efficientdet-lite0.tflite`
- **File**: `libs/abcvlib/src/main/assets/efficientdet-lite1.tflite`
    - *Note*: These files might be downloaded dynamically via `ModelDownload.configure(project)` in the build script.

---

## Status and Acceptance
- [x] Every identified TFLite usage path is mapped with file references.
- [x] Inventory is sufficient to derive follow-up migration tasks.
