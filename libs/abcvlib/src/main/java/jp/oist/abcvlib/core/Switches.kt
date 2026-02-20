package jp.oist.abcvlib.core

/**
 * Various booleans controlling optional functionality. All switches are optional as default
 * values have been set elsewhere. All changes here will override default values
 */
data class Switches(
    /**
     * Enable/disable sensor and IO logging. Only set to true when debugging as it uses a lot of
     * memory/disk space on the phone and may result in memory failure if run for a long time
     * such as any learning tasks.
     */
    var loggerOn: Boolean = false,

    /**
     * Enables measurements of time intervals between various functions and outputs to Logcat
     */
    var timersOn: Boolean = false,

    /**
     * Enable/disable this to swap the polarity of the wheels such that the default forward
     * direction will be swapped (i.e. wheels will move cw vs ccw as forward).
     */
    var wheelPolaritySwap: Boolean = true,

    /**
     * Enable readings from phone gyroscope, accelerometer, and sensor fusion software sensors
     * determining the angle of tile, angular velocity, etc
     */
    var motionSensorApp: Boolean = true,

    /**
     * Enable readings from the wheel quadrature encoders to determine things like wheel speed,
     * distance traveled, etc.
     */
    var quadEncoderApp: Boolean = true,

    /**
     * Control various things from a remote python server interface
     */
    var pythonControlledPIDBalancer: Boolean = false,

    /**
     * Enable default PID controlled balancer. Custom controllers can be added to the output of
     * this controller to enable balanced movements.
     */
    var balanceApp: Boolean = false,

    /**
     * Enables the use of camera inputs via OpenCV.
     */
    var cameraApp: Boolean = false,

    /**
     * Enables the use of camera inputs via cameraX.
     */
    var cameraXApp: Boolean = false,

    /**
     * Determines center of color blob and moves wheels in order to keep blob centered on screen
     */
    var centerBlobApp: Boolean = false,

    /**
     * Enables raw audio feed as well as simple calculated metrics like rms,
     * dB SPL (uncalibrated), etc.
     */
    var micApp: Boolean = false,

    /**
     * Generates an action selector with basic Q-learning. Generalized version still in development
     */
    var actionSelectorApp: Boolean = false
)