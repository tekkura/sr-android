package jp.oist.abcvlib.core.learning

class MotionActionSpace {
    val motionActions: Array<MotionAction?>

    /**
     * Initialize an object with actionCount number of discrete actions. Add actions via the
     * [.addMotionAction] method. Alternatively, you can use the default actions
     * by calling this method without any arguments. See [.MotionActionSpace].
     * @param actionCount Number of possible discrete actions
     */
    constructor(actionCount: Int) {
        motionActions = arrayOfNulls<MotionAction>(actionCount)
    }

    /**
     * Creates default motion action set with stop, forward, backward, left, and right as actions.
     */
    constructor() {
        motionActions = arrayOfNulls<MotionAction>(5)
        addDefaultActions()
    }

    private fun addDefaultActions() {
        motionActions[0] = MotionAction("stop", 0, 0f, 0f, true, true)
        motionActions[1] = MotionAction("forward", 1, 1f, 1f, false, false)
        motionActions[2] = MotionAction("backward", 2, -1f, -1f, false, false)
        motionActions[3] = MotionAction("left", 3, -1f, 1f, false, false)
        motionActions[4] = MotionAction("right", 4, 1f, -1f, false, false)
    }

    fun addMotionAction(
        actionName: String,
        actionByte: Byte,
        leftWheelSpeed: Float,
        rightWheelSpeed: Float,
        leftWheelBrake: Boolean,
        rightWheelBrake: Boolean
    ) {
        if (actionByte.toInt() > motionActions.size) {
            throw ArrayIndexOutOfBoundsException(
                "Tried to addMotionAction to an index that " +
                        "doesn't exist in the MotionActionSpace. Make sure you initialize the " +
                        "MotionActionSpace with long enough length to accommodate all the actions you " +
                        "plan to create."
            )
        }
        motionActions[actionByte.toInt()] = MotionAction(
            actionName, actionByte, leftWheelSpeed,
            rightWheelSpeed, leftWheelBrake, rightWheelBrake
        )
    }
}


