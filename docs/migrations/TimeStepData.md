# Migration Guide: TimeStepData

## Overview
This guide outlines the transition to the updated `TimeStepData` structure, which encompasses finishing three tasks defined in `milestones/TimeStepData.md`. These changes aim to make the `TimeStepData` snapshot a more complete representation of the robot's state and sensor inputs during a single timestep.

## 1. Store decoded QR code data in TimeStepData

### Objective
Ensure that decoded QR code data is preserved in the `TimeStepData` snapshot rather than just being logged.

### Checklist
- Add a `QRCodeData` class inside `TimeStepDataBuffer.TimeStepData`.
- `QRCodeData` should store a list of detection events, each containing:
    - `data`: The decoded string.
    - `timestampNs`: The time of detection (use `System.nanoTime()` as the subscriber currently doesn't provide one).
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
    - Metadata: `frameCapturedAtNs`, `inferenceTime`, `imageWidth`, `imageHeight`.
- Update `TimeStepDataBuffer.onObjectsDetected` to populate `writeData.detectionData`.
- Ensure `TimeStepData.clear()` resets the `detectionData`.

## 3. Preserve per-callback sound metadata in TimeStepData

### Objective
Prevent loss of audio timing metadata when multiple microphone callbacks occur within a single timestep.

### Checklist
- Define an `AudioFrame` data class within `TimeStepDataBuffer.TimeStepData.SoundData`.
- `AudioFrame` should store:
    - `levels`: `FloatArray` of audio samples.
    - `sampleCount`: `Int`.
    - `sampleRate`: `Int`.
    - `startTime`: `AudioTimestamp`.
    - `endTime`: `AudioTimestamp`.
- Update `SoundData` to maintain a list of `AudioFrame` objects.
- Update `TimeStepDataBuffer.onMicrophoneDataUpdate` to create and add a new `AudioFrame` to the list.
- **Backward Compatibility**: Maintain the existing `levels: ArrayList<Float>` in `SoundData` and continue to append samples to it in `onMicrophoneDataUpdate`.
- **Metadata Consistency**: Update `SoundData.setMetaData` (or equivalent) so that the top-level `startTime` reflects the `startTime` of the *first* frame in the timestep, and `endTime` reflects the `endTime` of the *last* frame.
- Document the behavior of top-level `SoundData` metadata in the code.
