package jp.oist.abcvlib.core.inputs.microcontroller

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import jp.oist.abcvlib.core.inputs.Publisher
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.util.DSP
import jp.oist.abcvlib.util.Logger
import java.util.ArrayList

class WheelData(
    context: Context,
    publisherManager: PublisherManager,
    bufferLength: Int,
    expWeight: Double
) : Publisher<WheelDataSubscriber>(context, publisherManager) {
    //----------------------------------- Wheel speed metrics --------------------------------------
    private val rightWheel: SingleWheelData = SingleWheelData(bufferLength, expWeight)
    private val leftWheel: SingleWheelData = SingleWheelData(bufferLength, expWeight)

    class Builder(
        private val context: Context,
        private val publisherManager: PublisherManager
    ) {
        private var bufferLength = 50
        private var expWeight = 0.01

        fun build(): WheelData {
            return WheelData(context, publisherManager, bufferLength, expWeight)
        }

        fun setBufferLength(bufferLength: Int): Builder {
            this.bufferLength = bufferLength
            return this
        }

        fun setExpWeight(expWeight: Double): Builder {
            this.expWeight = expWeight
            return this
        }
    }

    /**
     * Listens for updates on the ioio pins monitoring the quadrature encoders.
     * Note these updates are not interrupts, so they do not necessarily represent changes in
     * value, simply a loop that regularly checks the status of the pin (high/low). This method is
     * called from the publisher located in [AbcvlibLooper.loop].
     *
     * After receiving data this then calculates various metrics like encoderCounts, distance,
     * and speed of each wheel. As the quadrature encoder pin states is updated FAR more frequently
     * than the frequency in which they change value, the speed calculation will result in
     * values of mostly zero if calculated between single updates. Therefore the calculation of speed
     * provides three different calculations for speed.
     *
     * 1. [SingleWheelData.speedInstantaneous] is the speed between single timesteps
     *    (and therefore usually zero in value)
     *
     * 2. [SingleWheelData.speedBuffered] is the speed as measured from the beginning to the end
     *    of a fixed length buffer. The default length is 50, but this can be set via the
     *    [WheelData] constructor or via the [WheelData.Builder.setBufferLength] builder method
     *    when creating an instance of WheelData.
     *
     * 3. [SingleWheelData.speedExponentialAvg] is a running exponential average of
     *    [SingleWheelData.speedBuffered]. The default weight of the average is 0.01, but this
     *    can be set via the [WheelData] constructor or via the [WheelData.Builder.setExpWeight]
     *    builder method when creating an instance of WheelData.
     *
     * Finally, this method then acts as a publisher to any subscribers/listeners that implement
     * the [WheelDataSubscriber] interface and passes this quadrature encoder pin state to them.
     * See the `jp.oist.abcvlib.basicsubscriber.MainActivity` for an example of this subscription
     * framework.
     */
    fun onWheelDataUpdate(timestamp: Long, countL: Int, countR: Int) {
        handler.post {
            rightWheel.update(timestamp, countR)
            leftWheel.update(timestamp, countL)
            if (!paused) {
                for (subscriber in subscribers) {
                    subscriber.onWheelDataUpdate(
                        timestamp, leftWheel.latestEncoderCount,
                        -rightWheel.latestEncoderCount, leftWheel.latestDistance,
                        -rightWheel.latestDistance, leftWheel.speedInstantaneous,
                        -rightWheel.speedInstantaneous, leftWheel.speedBuffered,
                        -rightWheel.speedBuffered, leftWheel.speedExponentialAvg,
                        -rightWheel.speedExponentialAvg
                    )
                }
            }
            rightWheel.updateIndex()
            leftWheel.updateIndex()
        }
    }

    override fun start() {
        mHandlerThread = HandlerThread("wheelDataThread")
        mHandlerThread.start()
        handler = Handler(mHandlerThread.looper)
        publisherManager.onPublisherInitialized()
        super.start()
    }

    override fun stop() {
        mHandlerThread.quitSafely()
        super.stop()
    }

    override fun getRequiredPermissions(): ArrayList<String> {
        return arrayListOf()
    }

    fun resetWheelCounts() { //todo I thought this was used at the end of each episode somewhere?
        this.rightWheel.resetCount()
        this.leftWheel.resetCount()
    }

    /**
     * Holds all wheel metrics such as quadrature encoder state, quadrature encoder counts,
     * distance traveled, and current speed. All metrics other than quadrature encoder state are
     * stored in circular buffers in order to avoid rapid shifts in speed measurements due to
     * several identical readings even when moving at full speed. This is due to a very fast sampling
     * rate compared to the rate of change on the quadrature encoders.
     */
    private class SingleWheelData(private val bufferLength: Int, private var expWeight: Double) {
        private var idxHead: Int = bufferLength - 1
        private var idxHeadPrev: Int = idxHead - 1
        private var idxTail = 0
        private val timestamps: LongArray = LongArray(bufferLength)
        private var mmPerCount: Double = (2 * Math.PI * 30) / 128

        // Total number of counts since start of activity
        private val encoderCount: IntArray = IntArray(bufferLength)

        // distance in mm that the wheel has traveled from start point. his assumes no slippage/lifting/etc.
        private val distance: DoubleArray = DoubleArray(bufferLength)

        // speed in mm/s that the wheel is currently traveling at. Calculated by taking the difference between the first and last index in the distance buffer over the difference in timestamps
        @get:Synchronized
        var speedBuffered: Double = 0.0
            private set

        // speed as measured between two consecutive quadrature code samples (VERY NOISY due to reading zero for any repeated quadrature encoder readings which happen VERY often)
        @get:Synchronized
        var speedInstantaneous: Double = 0.0
            private set

        // running exponential average of speedBuffered.
        @get:Synchronized
        var speedExponentialAvg: Double = 0.0
            private set

        /**
         * Input all IO values from Hubee Wheel and output either +1, or -1 to add or subtract one wheel
         * count.
         *
         * The combined values of input1WheelStateIo and input2WheelStateIo control the direction of the
         * Hubee wheels.
         *
         * encoderAState and encoderBState are the direct current IO reading (high or low) of
         * the quadrature encoders on the Hubee wheels. See Hubee wheel documentation regarding which IO
         * corresponds to the A and B IO.
         *
         * ![Hubee Wheel](../../../../../../../../../../media/images/hubeeWheel.gif)
         *
         * encoderAWheelStatePrevious and encoderBWheelStatePrevious are previous state of their
         * corresponding variables.
         *
         * ```
         * IN1  IN2 PWM Standby Result
         * H    H   H/L H       Stop-Brake
         * L    H   H   H       Turn Forwards
         * L    H   L   H       Stop-Brake
         * H    L   H   H       Turn Backwards
         * H    L   L   H       Stop-Brake
         * L    L   H/L H       Stop-NoBrake
         * H/L  H/L H/L L       Standby
         * ```
         *
         * See: [Quadrature Intro](http://www.creative-robotics.com/quadrature-intro)
         */
        @Synchronized
        fun update(timestamp: Long, count: Int) {
            encoderCount[idxHead] = count
            updateDistance()
            updateWheelSpeed(timestamp)
        }

        @Synchronized
        fun updateDistance() {
            distance[idxHead] = encoderCount[idxHead] * mmPerCount
        }

        @Synchronized
        fun updateWheelSpeed(timestamp: Long) {
            timestamps[idxHead] = timestamp
            val dtBuffer = ((timestamps[idxHead] - timestamps[idxTail]) / 1000000000f).toDouble()

            if (dtBuffer != 0.0) {
                // Calculate the speed of each wheel in mm/s.
                speedInstantaneous = (distance[idxHead] - distance[idxHeadPrev]) / 1000000000f
                speedBuffered = (distance[idxHead] - distance[idxTail]) / dtBuffer
                speedExponentialAvg =
                    DSP.exponentialAvg(speedBuffered, speedExponentialAvg, expWeight)
            } else {
                Logger.i("sensorDebugging", "dt_buffer == 0")
            }
        }

        @Synchronized
        fun updateIndex() {
            idxHeadPrev = idxHead
            idxHead++
            idxTail++
            idxHead %= bufferLength
            idxTail %= bufferLength
        }

        @get:Synchronized
        val latestEncoderCount: Int
            get() = encoderCount[idxHead]

        @get:Synchronized
        val latestDistance: Double
            get() = distance[idxHead]

        @Synchronized
        fun setExpWeight(expWeight: Double) {
            this.expWeight = expWeight
        }

        fun resetCount() {
            encoderCount.fill(0)
        }
    }
}
