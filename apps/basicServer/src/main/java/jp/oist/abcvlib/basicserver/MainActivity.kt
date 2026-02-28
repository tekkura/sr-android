package jp.oist.abcvlib.basicserver

import android.app.Activity
import android.os.Bundle
import com.google.flatbuffers.FlatBufferBuilder
import jp.oist.abcvlib.core.learning.fbclasses.Episode
import jp.oist.abcvlib.util.HttpConnection
import jp.oist.abcvlib.util.HttpConnection.HttpCallback
import jp.oist.abcvlib.util.HttpDataType
import jp.oist.abcvlib.util.HttpExtraInfo
import jp.oist.abcvlib.util.HttpExtraInfo.FlatbufferInfo
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), HttpCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val scheduledExecutorServiceWithException = ScheduledExecutorServiceWithException(
            1,
            ProcessPriorityThreadFactory(Thread.NORM_PRIORITY, "HttpConnection")
        )
        scheduledExecutorServiceWithException.scheduleWithFixedDelay(
            { this.loopingHttpCalls() },
            0,
            5,
            TimeUnit.SECONDS
        )
    }

    private fun loopingHttpCalls() {
        val httpConnection = HttpConnection(this, this)

        // Send a string
        val stringData: ByteArray = "Hello, this is a string!".toByteArray()
        httpConnection.sendData(stringData, HttpDataType.STRING, null)

        // Send a file
        val fileData = byteArrayOf()
        val fileInfo = HttpExtraInfo.FileInfo("filename.txt", 1234, "text/plain")
        httpConnection.sendData(fileData, HttpDataType.FILE, fileInfo)

        // Send a flatbuffer
        val robotID = 1
        val flatBufferSize = 1024
        val builder = FlatBufferBuilder(flatBufferSize)
        Episode.startEpisode(builder)
        Episode.addRobotid(builder, robotID)
        val ep = Episode.endEpisode(builder)
        builder.finish(ep)
        val episode = builder.dataBuffer()
        val flatBufferData = episode.array()
        val size = flatBufferData.size
        val flatbufferInfo = FlatbufferInfo("FlatbufferClassName", size)
        httpConnection.sendData(flatBufferData, HttpDataType.FLATBUFFER, flatbufferInfo)

        // Request a file
        httpConnection.getData("test.txt")
    }

    override fun onFileReceived(filename: String, fileData: ByteArray) {
        // Handle the received file data
        Logger.d("HttpConnection", "Received file: " + filename + " of length: " + fileData.size)
    }

    override fun onSuccess(response: String) {
        // Handle the received string response
        Logger.d("HttpConnection", "Received response: $response")
    }

    override fun onError(error: String) {
        // Handle the error
        Logger.e("HttpConnection", "Error: $error")
    }
}