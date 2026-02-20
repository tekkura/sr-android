package jp.oist.abcvlib.tests

import jp.oist.abcvlib.core.outputs.AbcvlibController

class BackAndForthController(private val speed: Float) : AbcvlibController() {
    private var currentSpeed: Float

    init {
        this.currentSpeed = speed
    }

    override fun run() {
        currentSpeed = if (currentSpeed == speed) {
            -speed
        } else {
            speed
        }
        setOutput(currentSpeed, currentSpeed)
    }
}
