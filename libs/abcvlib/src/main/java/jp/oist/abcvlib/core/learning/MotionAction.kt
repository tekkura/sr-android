package jp.oist.abcvlib.core.learning

data class MotionAction(
    var actionName: String,
    var actionByte: Byte,
    var leftWheelPWM: Float,
    var rightWheelPWM: Float,
    val leftWheelBrake: Boolean,
    val rightWheelBrake: Boolean
) {

    fun setLeftWheelPWM(leftWheelPWM: Int) {
        this.leftWheelPWM = leftWheelPWM.toFloat()
    }

    fun setRightWheelPWM(rightWheelPWM: Int) {
        this.rightWheelPWM = rightWheelPWM.toFloat()
    }

    fun setAction(leftWheelPWM: Int, rightWheelPWM: Int, actionName: String, actionByte: Byte) {
        this.actionName = actionName
        this.actionByte = actionByte
        this.leftWheelPWM = leftWheelPWM.toFloat()
        this.rightWheelPWM = rightWheelPWM.toFloat()
    }

}
