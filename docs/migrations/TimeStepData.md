# Migration Guide: TimeStepData

## Overview
This guide outlines the transition to the updated `TimeStepData` structure, which encompasses finishing three tasks defined in [`docs/milestones/TimeStepData.md`](../milestones/TimeStepData.md). These changes aim to make the `TimeStepData` snapshot a more complete representation of the robot's state and sensor inputs during a single timestep.

## 1. Store decoded QR code data in TimeStepData

### Objective
Ensure that decoded QR code data is preserved in the `TimeStepData` snapshot rather than just being logged.

### Checklist
- Add a `QRCodeData` class inside `TimeStepDataBuffer.TimeStepData`.
- `QRCodeData` should store a list of detection events, each containing:
    - `data`: The decoded string.
    - `timestampNs`: The time of detection (provided by `ImageData.customAnalysis`, but noot collected by `QRCodeDataSubscriber`. Change its signature to accomodate).
- Update `TimeStepDataBuffer.onQRCodeDetected(qrDataDecoded: String)` to append the detection to `writeData.qrCodeData`.
- Ensure `TimeStepData.clear()` resets the `qrCodeData`.
- Expose an accessor for the list of QR detections in `QRCodeData`.

## 2. Add object detection results to TimeStepData

### Objective
Integrate object detection results into the `TimeStepData` snapshot to support policy decisions based on detected objects.

### Checklist
- Modify `TimeStepDataBuffer` to implement `ObjectDetectorDataSubscriber`.
- Add a `DetectionData` class inside `TimeStepDataBuffer.TimeStepData`.
- `DetectionData` should store a list of `DetectionResult` objects.
- `DetectionResult` should encapsulate:
    - List of categories (label and score).
    - Bounding box coordinates.
    - Metadata: `frameCapturedAtNs`, `detectStartedAtNs`, `detectCompletedAtNs`, `inferenceTime`, `imageWidth`, `imageHeight`.
- Expose an accessor for the list of detection results in `DetectionData` so downstream assembled-timestep consumers can read them.
- Update `TimeStepDataBuffer.onObjectsDetected` to populate `writeData.detectionData`.
- Ensure `TimeStepData.clear()` resets the `detectionData`.

## 3. Preserve per-callback sound metadata in TimeStepData

### Objective
Prevent loss of audio timing metadata when multiple microphone callbacks occur within a single timestep.

### Checklist
- Define an `AudioFrame` data class within `TimeStepDataBuffer.TimeStepData.SoundData`.
- `AudioFrame` should store:
    - `levels`: `FloatArray` containing exactly the `sampleCount` valid audio samples (for example, `audioData.copyOf(sampleCount)`), excluding unused values in a larger callback buffer.
    - `sampleCount`: `Int`.
    - `sampleRate`: `Int`.
    - `startTime`: An independent snapshot of the callback's `AudioTimestamp` value.
    - `endTime`: An independent snapshot of the callback's `AudioTimestamp` value.
- Copy the timestamp values into each `AudioFrame`; do not retain references to the mutable `MicrophoneData` timestamps, which are reused across callbacks.
- Update `SoundData` to maintain a list of `AudioFrame` objects.
- Expose an accessor for the `AudioFrame` list in `SoundData` so downstream assembled-timestep consumers can read it.
- Update `TimeStepDataBuffer.onMicrophoneDataUpdate` to create and add a new `AudioFrame` to the list.
- **Backward Compatibility**: Maintain the existing `levels: ArrayList<Float>` in `SoundData`, but append only the first `sampleCount` valid samples in `onMicrophoneDataUpdate` (not unused entries from the callback buffer), keeping the flattened view consistent with `AudioFrame.levels` and the aggregate sample counts.
- **Metadata Consistency**: Update `SoundData.setMetaData` (or equivalent) so that the top-level `startTime` reflects the `startTime` of the *first* frame in the timestep, and `endTime` reflects the `endTime` of the *last* frame. `totalTime` and `totalSamplesCalculatedViaTime` must likewise represent the complete first-frame-to-last-frame window, rather than only the most recent callback's arguments.
- Document the behavior of top-level `SoundData` metadata in the code.
