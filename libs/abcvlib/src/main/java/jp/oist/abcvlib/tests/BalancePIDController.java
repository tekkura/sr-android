package jp.oist.abcvlib.tests;

import android.util.Log;

import jp.oist.abcvlib.core.inputs.Publisher;
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber;
import jp.oist.abcvlib.core.inputs.phone.OrientationData;
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber;
import jp.oist.abcvlib.core.outputs.AbcvlibController;
import jp.oist.abcvlib.core.outputs.Outputs;

public class BalancePIDController extends AbcvlibController implements WheelDataSubscriber, OrientationDataSubscriber {

    private final String TAG = this.getClass().getName();

    // Initialize all sensor reading variables
    private double p_tilt = -24;
    private double i_tilt = 0;
    private double d_tilt = 1.0;
    private double setPoint = 2.8;
    private double p_wheel = 0.0;
    private double expWeight = 0.25;
    private double e_t = 0; // e(t) of wikipedia
    private double int_e_t; // integral of e(t) from wikipedia. Discrete, so just a sum here.

    private double maxAbsTilt = 6.5; // in Degrees

    private double speedL;
    private double thetaDeg;
    private double angularVelocityDeg;

    private int bounceLoopCount = 0;

    public BalancePIDController(Outputs outputs){
        super(outputs);
    }

    public void run(){
        if (isRunning()){
            // If current tilt angle is over maxAbsTilt or under -maxAbsTilt --> Bounce Up
            if ((setPoint - maxAbsTilt) > thetaDeg){
                bounce(false); // Bounce backward first
            }else if((setPoint + maxAbsTilt) < thetaDeg){
                bounce(true); // Bounce forward first
            }else{
                bounceLoopCount = 0;
                linearController();
            }
        }
    }

    /**
     * A means of changing the PID values while this controller is running
     * @param p_tilt_ proportional controller relative to the tilt angle of the phone
     * @param i_tilt_ integral controller relative to the tilt angle of the phone
     * @param d_tilt_ derivative controller relative to the tilt angle of the phone
     * @param setPoint_ the assumed angle where the robot would be balanced (ideally zero but realistically nearer to 3 or 4 deg)
     * @param p_wheel_ proportional controller relative to the wheel distance
     * @param expWeight_ exponential filter coefficicent //todo implement this more clearly
     * @param maxAbsTilt_ max tilt abgle (deg) at which the controller will switch between a linear and non-linear bounce controller.
     * @throws InterruptedException thrown if shutdown while trying to read/write to the IOIO board.
     */
    synchronized public void setPID(double p_tilt_, double i_tilt_, double d_tilt_, double setPoint_,
                                    double p_wheel_, double expWeight_, double maxAbsTilt_)
            throws InterruptedException {

        try {
                setPoint = setPoint_;
                p_tilt = p_tilt_;
                i_tilt = i_tilt_;
                d_tilt = d_tilt_;
                p_wheel = p_wheel_;
                expWeight = expWeight_;
                maxAbsTilt = maxAbsTilt_;
        } catch (NullPointerException e){
            Log.e(TAG,"Error", e);
            Thread.sleep(1000);
        }
    }

    // -------------- Actual Controllers ----------------------------

    private void bounce(boolean forward) {
        float speed = 0.5f;
        // loop steps between turning on and off wheels.
        int bouncePulseWidth = 100;
        if (bounceLoopCount < bouncePulseWidth * 0.1){
            setOutput(0,0);
        }else if (bounceLoopCount < bouncePulseWidth * 1.1){
            if (forward){
                setOutput(speed,speed);
            }else{
                setOutput(-speed,-speed);
            }
        }else if (bounceLoopCount < bouncePulseWidth * 1.2){
            setOutput(0,0);
        }else if (bounceLoopCount < bouncePulseWidth * 2.2) {
            if (forward){
                setOutput(-speed,-speed);
            }else{
                setOutput(speed,speed);
            }
        }else {
            bounceLoopCount = 0;
        }
        bounceLoopCount++;
    }

    private void linearController(){

        // TODO this needs to account for length of time on each interval, or overall time length. Here this just assumes a width of 1 for all intervals.
        int_e_t = int_e_t + e_t;
        e_t = setPoint - thetaDeg;
        Log.v(TAG, "tilt angle:" + thetaDeg);// error betweeen actual and desired wheel speed (default 0)
        double e_w = 0.0 - speedL;
        Log.v(TAG, "speedL:" + speedL);

        double p_out = (p_tilt * e_t) + (p_wheel * e_w);
        double i_out = i_tilt * int_e_t;
        double d_out = d_tilt * angularVelocityDeg;

        double outputL = p_out + i_out + d_out;
        double outputR = p_out + i_out + d_out;
        Log.v(TAG, "outputL:" + outputL);
        Log.v(TAG, "outputR:" + outputR);
        setOutput((float)outputL, (float)outputR);
    }

    // -------------- Input Data Listeners ----------------------------

    @Override
    public void onWheelDataUpdate(long timestamp, int wheelCountL, int wheelCountR,
                                  double wheelDistanceL, double wheelDistanceR,
                                  double wheelSpeedInstantL, double wheelSpeedInstantR,
                                  double wheelSpeedBufferedL, double wheelSpeedBufferedR,
                                  double wheelSpeedExpAvgL, double wheelSpeedExpAvgR) {
        speedL = wheelSpeedExpAvgL;
        //        wheelData.setExpWeight(expWeight); // todo enable access to this in GUI somehow
    }

    @Override
    public void onOrientationUpdate(long timestamp, double thetaRad, double angularVelocityRad) {
        thetaDeg = OrientationData.getThetaDeg(thetaRad);
        angularVelocityDeg = OrientationData.getAngularVelocityDeg(angularVelocityRad);
    }
}
