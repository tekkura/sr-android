package jp.oist.abcvlib.core.outputs

import jp.oist.abcvlib.core.Switches
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.SerialCommManager
import java.util.concurrent.CopyOnWriteArrayList

/*
Currently deprecated. This class was intended to be used as a way to control multiple controllers
This needs to be updated to include a way to control the motors via the SerialCommManager class
instead of the deprecated SerialCommManager class
 */
class MasterController internal constructor(
    private val switches: Switches,
    private val serialCommManager: SerialCommManager
) : AbcvlibController() {
    private val TAG: String = this.javaClass.name

    private val controllers = CopyOnWriteArrayList<AbcvlibController>()

    override fun run() {
        setOutput(0f, 0f)
        for (controller in controllers) {
            if (switches.loggerOn) {
                Logger.v(TAG, "$controller output: ${controller.getLeftOutput()}")
            }

            setOutput(
                left = getLeftOutput() + controller.getLeftOutput(),
                right = getRightOutput() + controller.getRightOutput()
            )
        }

        if (switches.loggerOn) {
            Logger.v("abcvlib", "grandController output: ${getLeftOutput()}")
        }

        serialCommManager.setMotorLevels(getLeftOutput(), getRightOutput(), false, false)
    }

    fun addController(controller: AbcvlibController) {
        this.controllers.add(controller)
    }

    fun removeController(controller: AbcvlibController) {
        if (controllers.contains(controller)) {
            this.controllers.remove(controller)
        }
    }

    fun stopAllControllers() {
        for (controller in controllers) {
            controller.setOutput(0f, 0f)
        }
    }
}
