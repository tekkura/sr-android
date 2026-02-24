package jp.oist.abcvlib.core.learning

class CommActionSpace {
    val commActions: Array<CommAction?>

    /**
     * Initialize an object with actionCount number of discrete actions. Add actions via the
     * [.addCommAction] method. Alternatively, you can use the default actions
     * by calling this method without any arguments. See [.CommActionSpace].
     * @param actionCount Number of possible discrete actions
     */
    constructor(actionCount: Int) {
        commActions = arrayOfNulls(actionCount)
    }

    /**
     * Creates 3 default actions called action1, action2, and action3 with bytes 0 to 2 assigned
     * respectively.
     */
    constructor() {
        commActions = arrayOfNulls(3)
        addDefaultActions()
    }

    private fun addDefaultActions() {
        commActions[0] = CommAction("action1", 0.toByte())
        commActions[1] = CommAction("action2", 1.toByte())
        commActions[2] = CommAction("action3", 2.toByte())
    }

    fun addCommAction(actionName: String, actionByte: Byte) {
        if (actionByte.toInt() > commActions.size) {
            throw ArrayIndexOutOfBoundsException()
        }
        commActions[actionByte.toInt()] = CommAction(actionName, actionByte)
    }
}
