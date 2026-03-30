package jp.oist.abcvlib.rosbridge

import jp.oist.abcvlib.util.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RosBridgeClient(private val listener: RosBridgeClientListener) {
    private var url: String = ""
    private lateinit var webSocket: WebSocket
    private var isConnected = false

    companion object {
        private const val TAG = "RosBridge"
        const val MESSAGE_TYPE_STRING = "std_msgs/msg/String"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(ip: String) {
        if (isConnected) return
        url = "ws://$ip:9090"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    fun isConnected(): Boolean = isConnected

    fun disconnect() {
        if (::webSocket.isInitialized) webSocket.close(1000, "Client disconnected")
        isConnected = false
        Logger.i(TAG, "Disconnected")
    }

    fun advertise(topic: String, type: String = MESSAGE_TYPE_STRING): Boolean {
        val msg = JSONObject().apply {
            put("op", "advertise")
            put("topic", topic)
            put("type", type)
        }
        Logger.i(TAG, "Advertised $topic")
        Logger.d(TAG, msg.toString())
        return send(msg.toString())
    }

    fun subscribe(topic: String, type: String = MESSAGE_TYPE_STRING): Boolean {
        val msg = JSONObject().apply {
            put("op", "subscribe")
            put("topic", topic)
            put("type", type)
        }
        Logger.i(TAG, "Subscribed to $topic")
        Logger.d(TAG, msg.toString())
        return send(msg.toString())
    }

    fun publish(topic: String, message: String): Boolean {
        val msg = JSONObject().apply {
            put("op", "publish")
            put("topic", topic)
            put("msg", JSONObject().put("data", message))
        }
        Logger.i(TAG, "Published to $topic")
        Logger.d(TAG, msg.toString())
        return send(msg.toString())
    }

    private fun send(json: String): Boolean {
        if (!::webSocket.isInitialized) return false
        return webSocket.send(json)
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Logger.i(TAG, "Connected to $url")
            isConnected = true
            listener.onConnected(url)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val json = JSONObject(text)
                val op = json.optString("op")
                val topic = json.optString("topic")
                val msg = json.optJSONObject("msg") ?: JSONObject()
                val data = if (msg.has("data")) msg.optString("data") else null
                if (topic.isNotEmpty() && data != null) {
                    Logger.d(TAG, "Received: $data")
                    listener.onMessage(topic, data)
                } else {
                    Logger.d(TAG, "Ignoring rosbridge frame op=$op topic=$topic payload=$text")
                }
            }.onFailure {
                val error = "ERROR: Failed to parse message ${it.message}"
                Logger.e(TAG, error)
                listener.onError(error)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            listener.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            listener.onDisconnected()
            val message = t.message?.takeIf { it.isNotBlank() }
            if (message != null) {
                listener.onError(message)
                Logger.e(TAG, "ERROR: $message")
            } else {
                Logger.d(TAG, "WebSocket failure without message")
            }
        }
    }
}

interface RosBridgeClientListener {
    fun onConnected(url: String)
    fun onDisconnected()
    fun onMessage(topic: String, message: String)
    fun onError(error: String)
}
