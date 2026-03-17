package jp.oist.abcvlib.util

class RecordingWithoutTimeStepBufferException : Exception(
    "Trying to set recording to true prior to initializing a TimeStepDataBuffer"
)