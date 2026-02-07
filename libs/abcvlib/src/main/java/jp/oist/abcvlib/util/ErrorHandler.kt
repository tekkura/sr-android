package jp.oist.abcvlib.util

object ErrorHandler {
    @JvmStatic
    fun eLog(TAG: String, comment: String, e: Exception, crash: Boolean) {
        Logger.e(TAG, comment, e)
        if (crash) {
            throw RuntimeException("This is an intentional debugging crash")
        }
    }
}
