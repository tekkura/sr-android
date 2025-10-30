package jp.oist.abcvlib.core.outputs;

import java.util.concurrent.TimeUnit;
import java.lang.Math;


import ioio.lib.api.exception.ConnectionLostException;
import jp.oist.abcvlib.core.AbcvlibLooper;
import jp.oist.abcvlib.core.Switches;
import jp.oist.abcvlib.util.Logger;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException;
import jp.oist.abcvlib.util.SerialCommManager;

public class Outputs {

    public Motion motion;
    private final MasterController masterController;
    private final ScheduledExecutorServiceWithException threadPoolExecutor;
    private final SerialCommManager serialCommManager;

    private float lastLeft = 0.0f;
    private float lastRight = 0.0f;
    private long lastCallTimestamp = 0;

    public Outputs(Switches switches, SerialCommManager serialCommManager){
        // Determine number of necessary threads.
        int threadCount = 1; // At least one for the MasterController
        this.serialCommManager = serialCommManager;
        ProcessPriorityThreadFactory processPriorityThreadFactory = new ProcessPriorityThreadFactory(Thread.MAX_PRIORITY, "Outputs");
        threadPoolExecutor = new ScheduledExecutorServiceWithException(threadCount, processPriorityThreadFactory);

        //BalancePIDController Controller
        motion = new Motion(switches);

        masterController = new MasterController(switches, serialCommManager);
    }

    public void startMasterController(){
        threadPoolExecutor.scheduleWithFixedDelay(masterController, 0, 1, TimeUnit.MILLISECONDS);
    }

    /**
     * Ideally users are not using this method but instead the default without maxChange.
     * Please only use this if you know the implications surrounding potential motor damage.
     * @param left speed from -1 to 1 (full speed backward vs full speed forward)
     * @param right speed from -1 to 1 (full speed backward vs full speed forward)
     * @param maxChange The maximum change in wheel output. Any faster than this will be clamped.
     */
    public void setWheelOutput(float left, float right, boolean leftBrake, boolean rightBrake, float maxChange) {
        if (left < -1.0f) {
            Logger.w("Outputs", "Left wheel output " + left + " is less than -1. Clamping.");
            left = -1.0f;
        } else if (left > 1.0f) {
            Logger.w("Outputs", "Left wheel output " + left + " is greater than 1. Clamping.");
            left = 1.0f;
        }
        if (right < -1.0f) {
            Logger.w("Outputs", "Right wheel output " + right + " is less than -1. Clamping.");
            right = -1.0f;
        } else if (right > 1.0f) {
            Logger.w("Outputs", "Right wheel output " + right + " is greater than 1. Clamping.");
            right = 1.0f;
        }

        if (lastCallTimestamp == 0) {
            lastCallTimestamp = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        long dt = now - lastCallTimestamp;
        Logger.v("Outputs", "dt: " + dt);

        if (dt == 0) {
            // To avoid division by zero
            serialCommManager.setMotorLevels(lastLeft, lastRight, leftBrake, rightBrake);
            return;
        }

        if (Math.abs(left - lastLeft) > maxChange) {
            Logger.w("Outputs", "Controller attempting to change left wheel output too quickly. " +
                    "Change: " + (left - lastLeft) + ", MaxChange: " + maxChange);
        }
        if (Math.abs(right - lastRight) > maxChange) {
            Logger.w("Outputs", "Controller attempting to change right wheel output too quickly. " +
                    "Change: " + (right - lastRight) + ", MaxChange: " + maxChange);
        }

        float newLeft = lastLeft + Math.max(-maxChange, Math.min(maxChange, left - lastLeft));
        float newRight = lastRight + Math.max(-maxChange, Math.min(maxChange, right - lastRight));

        if (newLeft < -1.0f) {
            Logger.w("Outputs", "New left wheel output " + newLeft + " is less than -1. Clamping.");
            newLeft = -1.0f;
        } else if (newLeft > 1.0f) {
            Logger.w("Outputs", "New left wheel output " + newLeft + " is greater than 1. Clamping.");
            newLeft = 1.0f;
        }

        if (newRight < -1.0f) {
            Logger.w("Outputs", "New right wheel output " + newRight + " is less than -1. Clamping.");
            newRight = -1.0f;
        } else if (newRight > 1.0f) {
            Logger.w("Outputs", "New right wheel output " + newRight + " is greater than 1. Clamping.");
            newRight = 1.0f;
        }

        serialCommManager.setMotorLevels(newLeft, newRight, leftBrake, rightBrake);
        lastLeft = newLeft;
        lastRight = newRight;
        lastCallTimestamp = now;
    }

    /**
     * @param left speed from -1 to 1 (full speed backward vs full speed forward)
     * @param right speed from -1 to 1 (full speed backward vs full speed forward)
     */
    public void setWheelOutput(float left, float right, boolean leftBrake, boolean rightBrake) {
        setWheelOutput(left, right, leftBrake, rightBrake, 0.4f);
    }

    public synchronized MasterController getMasterController() {
        return masterController;
    }

    public void turnOffWheels() throws ConnectionLostException {
        setWheelOutput(0, 0, false, false);
    }
}
