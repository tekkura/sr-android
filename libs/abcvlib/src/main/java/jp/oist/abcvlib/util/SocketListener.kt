package jp.oist.abcvlib.util

import org.json.JSONObject
import java.nio.ByteBuffer

interface SocketListener {
    /**
     * Call this in MainActivity such that you can parse the ByteBuffer msgFromServer to either
     * local files or to RAM.
     * @param jsonHeader You are responsible for writing this on the python side to specify the
     * contents of the msgFromServer. See libserver.py:_create_message()
     * @param msgFromServer A byte buffer that contains everything from the server. Use the
     * jsonHeader that you create on the python side to parse this here on
     * the Java end.
     */
    fun onServerReadSuccess(jsonHeader: JSONObject, msgFromServer: ByteBuffer)
}
