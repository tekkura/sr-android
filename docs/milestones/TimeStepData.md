Implement several tasks related to TimeStepData collection and processing

## 1. Store decoded QR code data in TimeStepData

### Summary
`TimeStepDataBuffer` implements `QRCodeDataSubscriber`, but decoded QR code values are currently only logged and are not stored in `TimeStepData`.

### Current state
`TimeStepDataBuffer.onQRCodeDetected(qrDataDecoded)` logs the decoded value, but `TimeStepData` has no QR-code field and downstream basicAssembler/RL-style policies cannot read QR detections from the assembled timestep snapshot.

### Expected state
Decoded QR code events received during a timestep should be represented in `TimeStepData`, likely with timestamps if available or another documented correlation mechanism.

### Notes
This should preserve the direct subscriber path for users who want callback-level control while making the assembled timestep path more complete.

## 2. Add object detection results to TimeStepData

### Summary
Object detector callbacks are available through `ObjectDetectorDataSubscriber`, but object detection results are not currently assembled into `TimeStepData`.

### Current state
Users can consume object detection directly through subscriber callbacks, but `TimeStepDataBuffer` does not collect object detection results into the assembled timestep snapshot used by basicAssembler/RL-style loops.

### Expected state
Object detection results received during a timestep should be represented in `TimeStepData`, with enough metadata to support policy decisions and later analysis.

Possible data to preserve:

- detection labels/categories
- confidence scores if available
- bounding boxes if available
- capture/detection timestamps
- image dimensions or coordinate frame metadata

### Notes
This should preserve the direct subscriber path for users who need callback-level control while making the assembled timestep path more complete.

## 3. Preserve per-callback sound metadata in TimeStepData

### Summary
`TimeStepData.SoundData` appends audio sample levels during a timestep, but metadata such as `startTime`, `endTime`, and `sampleRate` is stored as timestep-level fields rather than per audio callback/frame.

### Current state
`TimeStepDataBuffer.onMicrophoneDataUpdate(...)` calls:

- `soundData.setMetaData(sampleRate, startTime, endTime)`
- `soundData.add(audioData, numSamples)`
The audio levels are accumulated, but timing metadata can be overwritten by later callbacks in the same timestep window. This makes it harder to reconstruct exact audio frame boundaries when multiple microphone callbacks occur during one timestep.

### Expected state
Sound data should preserve enough per-callback/frame metadata to correlate audio samples with their timing during the timestep.

Possible approaches:

- Store audio frames as records containing levels, sample count, sample rate, start timestamp, and end timestamp.
- Keep the existing flattened `levels[]` accessor for compatibility, but add frame metadata alongside it.
- Document whether timestep-level sound metadata represents the first callback, last callback, or whole accumulated window.

### Notes
This matters because different sensors update at different frequencies. Users may want either all high-resolution audio updates or a documented efficient summary.