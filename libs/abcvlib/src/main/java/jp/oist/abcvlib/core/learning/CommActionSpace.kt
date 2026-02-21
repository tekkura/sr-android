package jp.oist.abcvlib.core.learning

class CommActionSpace {
    private val _commActions: Array<CommAction?>
    val commActions: Array<CommAction> get() = _commActions.requireNoNulls()

    /**
     * Initialize an object with actionCount number of discrete actions. Add actions via the
     * [.addCommAction] method. Alternatively, you can use the default actions
     * by calling this method without any arguments. See [.CommActionSpace].
     * @param actionCount Number of possible discrete actions
     */
    constructor(actionCount: Int) {
        _commActions = arrayOfNulls(actionCount)
    }

    /**
     * Creates 3 default actions called action1, action2, and action3 with bytes 0 to 2 assigned
     * respectively.
     */
    constructor() {
        _commActions = arrayOfNulls(3)
        addDefaultActions()
    }

    private fun addDefaultActions() {
        _commActions[0] = CommAction("action1", 0.toByte())
        _commActions[1] = CommAction("action2", 1.toByte())
        _commActions[2] = CommAction("action3", 2.toByte())
    }

    fun addCommAction(actionName: String, actionByte: Byte) {
        if (actionByte.toInt() > _commActions.size) {
            throw ArrayIndexOutOfBoundsException()
        }
        _commActions[actionByte.toInt()] = CommAction(actionName, actionByte)
    }
}
