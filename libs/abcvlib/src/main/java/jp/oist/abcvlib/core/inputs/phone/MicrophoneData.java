package jp.oist.abcvlib.core.inputs.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.intentfilter.androidpermissions.models.DeniedPermissions;

import java.util.ArrayList;

import jp.oist.abcvlib.core.inputs.PublisherManager;
import jp.oist.abcvlib.core.inputs.Publisher;
import jp.oist.abcvlib.util.ErrorHandler;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException;

public class MicrophoneData extends Publisher<MicrophoneDataSubscriber> implements AudioRecord.OnRecordPositionUpdateListener{

    private final AudioTimestamp startTime = new AudioTimestamp();
    private final AudioTimestamp endTime = new AudioTimestamp();
    private ScheduledExecutorServiceWithException audioExecutor;

    private AudioRecord recorder;

    /**
     * Lazy start. This constructor sets everything up, but you must call {@link #start()} for the
     * buffer to begin filling.
     */
    public MicrophoneData(Context context, PublisherManager publisherManager) {
        super(context, publisherManager);
        this.publisherManager = publisherManager;
    }

    public static class Builder{
        private final Context context;
        private final PublisherManager publisherManager;

        public Builder(Context context, PublisherManager publisherManager){
            this.context = context;
            this.publisherManager = publisherManager;
        }

        public MicrophoneData build(){
            return new MicrophoneData(context, publisherManager);
        }
    }

    @Override
    public void start(){
        recorder.startRecording();

        while (recorder.getTimestamp(startTime, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.ERROR_INVALID_OPERATION){
            recorder.getTimestamp(startTime, AudioTimestamp.TIMEBASE_MONOTONIC);
        }
        Log.i("microphone_start", "StartFrame:" + startTime.framePosition + " NanoTime: " + startTime.nanoTime);
        publisherManager.onPublisherInitialized();
        super.start();
    }

    public void stop(){
        recorder.stop();
        recorder.setRecordPositionUpdateListener(null);
        audioExecutor.shutdownNow();
        recorder.release();
        recorder = null;
        super.stop();
    }

    @Override
    public ArrayList<String> getRequiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        return permissions;
    }

    public AudioTimestamp getStartTime(){return startTime;}

    public void setStartTime(){
        recorder.getTimestamp(startTime, AudioTimestamp.TIMEBASE_MONOTONIC);
    }

    public AudioTimestamp getEndTime(){
        recorder.getTimestamp(endTime, AudioTimestamp.TIMEBASE_MONOTONIC);
        return endTime;
    }

    public int getSampleRate(){return recorder.getSampleRate();}

    @Override
    public void onMarkerReached(AudioRecord recorder) {

    }

    /**
     * This method fires 2 times during each loop of the audio record buffer.
     * audioRecord.read(audioData) writes the buffer values (stored in the audioRecord) to a local
     * float array called audioData. It is set to read in non_blocking mode
     * (https://developer.android.com/reference/android/media/AudioRecord?hl=ja#READ_NON_BLOCKING)
     * You can verify it is not blocking by checking the log for "Missed some audio samples"
     * You can verify if the buffer writer is overflowing by checking the log for:
     * "W/AudioFlinger: RecordThread: buffer overflow"
     * @param audioRecord
     */
    @Override
    public void onPeriodicNotification(AudioRecord audioRecord) {
        try{
            audioExecutor.execute(() -> {
                int readBufferSize = audioRecord.getPositionNotificationPeriod();
                float[] audioData = new float[readBufferSize];
                @SuppressLint("WrongConstant") int numSamples = audioRecord.read(audioData, 0,
                        readBufferSize, AudioRecord.READ_NON_BLOCKING);
                if (numSamples < readBufferSize){
                    Log.w("microphone", "Missed some audio samples");
                }
                onNewAudioData(audioData, numSamples);
            });
        }catch (Exception e){
            ErrorHandler.eLog("onPeriodicNotification", "sadfkjsdhf", e, true);
        }
    }

    protected void onNewAudioData(float[] audioData, int numSamples){
        if (subscribers.size() > 0 && !paused){
            android.media.AudioTimestamp startTime = getStartTime();
            android.media.AudioTimestamp endTime = getEndTime();
            int sampleRate = getSampleRate();
            for (MicrophoneDataSubscriber subscriber:subscribers){
                subscriber.onMicrophoneDataUpdate(audioData, numSamples, sampleRate, startTime, endTime);
            }
            setStartTime();
        }
    }

    @Override
    public void onPermissionGranted() {
        audioExecutor = new ScheduledExecutorServiceWithException(1, new ProcessPriorityThreadFactory(10, "dataGatherer"));
        HandlerThread handlerThread = new HandlerThread("audioHandlerThread");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        int mAudioSource = MediaRecorder.AudioSource.UNPROCESSED;
        int mSampleRate = 8000;
        int mChannelConfig = AudioFormat.CHANNEL_IN_MONO;
        int mAudioFormat = AudioFormat.ENCODING_PCM_FLOAT;
        int bufferSize = 3 * AudioRecord.getMinBufferSize(mSampleRate, mChannelConfig, // needed to be 3 or more times or would internally increase it within Native lib.
                mAudioFormat);

        try{
            recorder = new AudioRecord(
                    mAudioSource,
                    mSampleRate,
                    mChannelConfig,
                    mAudioFormat,
                    bufferSize);
        }catch (SecurityException e){
            throw new RuntimeException("You must grant audio record access to use this app");
        }

        int bytesPerSample = 32 / 8; // 32 bits per sample (Float.size), 8 bytes per bit.
        int bytesPerFrame = bytesPerSample * recorder.getChannelCount(); // Need this as setPositionNotificationPeriod takes num of frames as period and you want it to fire after each full cycle through the buffer.
        int framePerBuffer = bufferSize / bytesPerFrame; // # of frames that can be kept in a bufferSize dimension
        int framePeriod = framePerBuffer / 2; // Read from buffer two times per full buffer.
        recorder.setPositionNotificationPeriod(framePeriod);
        recorder.setRecordPositionUpdateListener(this, handler);
        publisherManager.onPublisherPermissionsGranted(this);
    }

    @Override
    public void onPermissionDenied(DeniedPermissions deniedPermissions) {
        ErrorHandler.eLog(TAG, "This app requires Audio Recording", new Exception(), true);
    }

//
//    public void processAudioFrame(short[] audioFrame) {
//        final double bufferLength = 20; //milliseconds
//        final double bufferSampleCount = mSampleRate / bufferLength;
//        // The Google ASR input requirements state that audio input sensitivity
//        // should be set such that 90 dB SPL at 1000 Hz yields RMS of 2500 for
//        // 16-bit samples, i.e. 20 * log_10(2500 / mGain) = 90.
//        final double mGain = 2500.0 / Math.pow(10.0, 90.0 / 20.0);
//        double mRmsSmoothed = 0;  // Temporally filtered version of RMS.
//
//        // Leq Calcs
//        double leqLength = 5; // seconds
//        double leqArrayLength = (mSampleRate / bufferSampleCount) * leqLength;
//        double[] leqBuffer = new double[(int) leqArrayLength];
//        // Compute the RMS value. (Note that this does not remove DC).
//        rms = 0;
//        for (short value : audioFrame) {
//            rms += value * value;
//        }
//        rms = Math.sqrt(rms / audioFrame.length);
//
//        // Compute a smoothed version for less flickering of the display.
//        // Coefficient of IIR smoothing filter for RMS.
//        double mAlpha = 0.9;
//        mRmsSmoothed = (mRmsSmoothed * mAlpha) + (1 - mAlpha) * rms;
//        rmsdB = 20 + (20.0 * Math.log10(mGain * mRmsSmoothed));
//
//    }
//
//    public int getTotalSamples() {
//        return mTotalSamples;
//    }
//
//    public void setTotalSamples(int totalSamples) {
//        mTotalSamples = totalSamples;
//    }
//
//    public double getRms() {
//        return rms;
//    }
//
//    public double getRmsdB() {
//        return rmsdB;
//    }
}