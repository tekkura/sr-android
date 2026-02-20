package jp.oist.abcvlib.core.outputs

import jp.oist.abcvlib.util.ErrorHandler
import java.util.concurrent.TimeUnit

abstract class AbcvlibController : Runnable {
    private var name: String = ""
    private var threadCount = 1
    private var threadPriority = Thread.MAX_PRIORITY
    private var initDelay = 0
    private var timeStep = 0
    private lateinit var timeUnit: TimeUnit
    private val TAG: String = javaClass.name

    private var _isRunning = false
    private lateinit var outputs: Outputs

    @Deprecated(
        message = "You must provide an Outputs object",
        replaceWith = ReplaceWith("AbcvlibController(outputs)")
    )
    constructor()

    constructor(outputs: Outputs) {
        this.outputs = outputs
    }

    fun setName(name: String): AbcvlibController {
        this.name = name
        return this
    }

    fun setThreadCount(threadCount: Int): AbcvlibController {
        this.threadCount = threadCount
        return this
    }

    fun setThreadPriority(threadPriority: Int): AbcvlibController {
        this.threadPriority = threadPriority
        return this
    }

    fun setInitDelay(initDelay: Int): AbcvlibController {
        this.initDelay = initDelay
        return this
    }

    fun setTimestep(timeStep: Int): AbcvlibController {
        this.timeStep = timeStep
        return this
    }

    fun setTimeUnit(timeUnit: TimeUnit): AbcvlibController {
        this.timeUnit = timeUnit
        return this
    }

    fun startController() {
        _isRunning = true
    }

    fun stopController() {
        setOutput(0f, 0f)
        _isRunning = false
    }

    private var _output: Output = Output()

    @get:Synchronized
    val output get() = _output

    @Synchronized
    fun getLeftOutput(): Float {
        return _output.left
    }

    @Synchronized
    fun getRightOutput(): Float {
        return _output.right
    }


    @Synchronized
    fun setOutput(left: Float, right: Float) {
        _output.left = left
        _output.right = right
        if (_isRunning) {
            outputs.setWheelOutput(left, right, false, false)
        }
    }

    override fun run() {
        ErrorHandler.eLog(
            TAG,
            "You must override the run method within your custom AbcvlibController.",
            Exception(),
            false
        )
    }

    data class Output(
        var left: Float = 0f,
        var right: Float = 0f
    )

    @Synchronized
    fun isRunning(): Boolean = _isRunning
}
