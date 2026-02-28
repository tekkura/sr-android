package jp.oist.abcvlib.util

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.text.DecimalFormat
import java.util.Vector
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

class SocketMessage(
    private val socketListener: SocketListener,
    private val sc: SocketChannel,
    private val selector: Selector
) {
    // this._recv_buffer = ByteBuffer.allocate((int) Math.pow(2,24));
    private val recvBuffer: ByteBuffer = ByteBuffer.allocate(1024)
    private var sendBuffer: ByteBuffer = ByteBuffer.allocate(1024)
    private var jsonHeaderLen = 0

    // Will tell Java at which points in msgContent each model lies (e.g. model1 is from 0 to 1018, model2 is from 1019 to 2034, etc.)
    private var jsonHeaderRead: JSONObject? = null
    private lateinit var jsonHeaderBytes: ByteArray

    // Should contain ALL model files. Parse to individual files after reading
    private var msgContent: ByteBuffer? = null
    private var jsonHeaderWrite: JSONObject? = null
    private val writeBufferVector = Vector<ByteBuffer>() // List of episodes
    private var msgReadComplete = false
    private var socketWriteTimeStart: Long = 0
    private var socketReadTimeStart: Long = 0
    private var totalNumBytesToWrite = 0

    // used to notify main thread that write/read to server has finished
    private lateinit var doneSignal: CyclicBarrier

    private val TAG = "SocketConnectionManager"

    fun processEvents(selectionKey: SelectionKey) {
        val sc = selectionKey.channel() as SocketChannel
        // Logger.i(TAG, "process_events");
        try {
            if (selectionKey.isConnectable) {
                val connected = sc.finishConnect()
                if (connected) {
                    val address = (selectionKey.channel() as SocketChannel).remoteAddress
                    Logger.d(TAG, "Finished connecting to $address")
                    Logger.v(TAG, "socketChannel.isConnected ? : " + sc.isConnected)
                    val ops = SelectionKey.OP_WRITE
                    sc.register(selectionKey.selector(), ops, selectionKey.attachment())
                }
            }
            if (selectionKey.isWritable) {
                // Logger.i(TAG, "write event")
                write(selectionKey)
            }
            if (selectionKey.isReadable) {
                // Logger.i(TAG, "read event")
                read(selectionKey)
                // int ops = SelectionKey.OP_WRITE
                // sc.register(selectionKey.selector(), ops, selectionKey.attachment());
            }
        } catch (e: Exception) {
            when (e) {
                is ClassCastException, is IOException, is JSONException,
                is BrokenBarrierException, is InterruptedException -> {
                    ErrorHandler.eLog(TAG, "Error processing selector events", e, true)
                }

                else -> throw e
            }
        }
    }

    @Throws(
        IOException::class,
        JSONException::class,
        BrokenBarrierException::class,
        InterruptedException::class
    )
    private fun read(selectionKey: SelectionKey) {
        val socketChannel = selectionKey.channel() as SocketChannel

        while (!msgReadComplete) {
            // At this point the _recv_buffer should have been cleared (pointer 0 limit=cap, no mark)
            val bitsRead = socketChannel.read(recvBuffer)

            if (bitsRead > 0 || recvBuffer.position() > 0) {
                // If you have not determined the length of the header via the 2 byte short protoHeader,
                // try to determine it, though there is no guarantee it will have enough bytes. So it may
                // pass through this if statement multiple times. Only after it has been read will
                // jsonHeaderLen have a non-zero length;
                if (this.jsonHeaderLen == 0) {
                    socketReadTimeStart = System.nanoTime()
                    processProtoHeader()
                } else if (this.jsonHeaderRead == null) {
                    processJsonHeader()
                } else if (!msgReadComplete) {
                    processMsgContent(selectionKey)
                } else {
                    Logger.e(TAG, "bitsRead but don't know what to do with them")
                }
            } else if (msgContent != null && !msgReadComplete) {
                processMsgContent(selectionKey)
            }
        }
    }

    @Throws(
        IOException::class,
        JSONException::class,
        BrokenBarrierException::class,
        InterruptedException::class
    )
    private fun write(selectionKey: SelectionKey) {
        if (!writeBufferVector.isEmpty()) {
            val socketChannel = selectionKey.channel() as SocketChannel

            // Logger.v(TAG, "writeBufferVector contains data");
            if (jsonHeaderWrite == null) {
                // This is because the data in this ByteBuffer does NOT start at 0, but at
                // buf.position(). Even if it were some other type of buffer here (e.g. compacted one)
                // the position should be zero and thus this shouldn't change anything
                val numBytesToWrite =
                    writeBufferVector[0]!!.limit() - writeBufferVector[0]!!.position()

                // Create JSONHeader containing length of episode in Bytes
                Logger.v(TAG, "generating jsonHeader")
                jsonHeaderWrite = generateJsonHeader(numBytesToWrite)
                val jsonBytes: ByteArray = jsonHeaderWrite.toString().toByteArray(UTF_8)

                // ByteBuffer jsonByteBuffer = ByteBuffer.wrap(jsonBytes); //todo optimize buffer length

                // Encode length of JSONHeader to first four bytes (int) and write to socketChannel
                val jsonLength = jsonBytes.size

                // Add up length of protoHeader, JSONHeader and episode bytes
                totalNumBytesToWrite = Integer.BYTES + jsonLength + numBytesToWrite

                // int optimalBufferSize = findOptimalBufferSize(totalNumBytesToWrite);

                // Create new buffer that compiles protoHeader, JsonHeader, and Episode
                sendBuffer = ByteBuffer.allocate(Integer.BYTES + jsonLength)

                Logger.v(TAG, "Assembling _send_buffer")
                // Assemble all bytes and flip to prepare to read
                // todo try to write the episode directly rather than copy it.
                sendBuffer.putInt(jsonLength)
                sendBuffer.put(jsonBytes)

                // Remove episode to clear memory note builder will reference the flatBuffer builder in memory
                // ByteBuffer builder = writeBufferVector.remove(0)
                // builder = null;
                sendBuffer.flip()

                val total = sendBuffer.limit()

                Logger.d(TAG, "Writing JSONHeader of length $total bytes to server ...")

                // Write Bytes to socketChannel
                if (sendBuffer.remaining() > 0) {
                    val numBytes = socketChannel.write(sendBuffer) // todo memory dump error here!
                }

                val msgSize = writeBufferVector[0]!!.limit() / 1000000
                Logger.d(TAG, "Writing message of length " + msgSize + "MB to server ...")
            } else {
                // Write Bytes to socketChannel
                if (sendBuffer.remaining() > 0) {
                    socketChannel.write(sendBuffer)
                } else if (writeBufferVector[0]!!.remaining() > 0) {
                    val bytes = socketChannel.write(writeBufferVector[0])
                    printTotalBytes(socketChannel, bytes)
                }
            }
            if (writeBufferVector[0]!!.remaining() == 0) {
                val total = writeBufferVector[0]!!.limit() / 1000
                val timeTaken = (System.nanoTime() - socketWriteTimeStart) * 10e-10
                val df = DecimalFormat()
                df.maximumFractionDigits = 2
                Logger.i(TAG, "Sent " + total + "kb in " + df.format(timeTaken) + "s")
                Logger.i(TAG, "Mean transfer rate of " + df.format(total / timeTaken) + " MB/s")

                // Clear sending buffer
                sendBuffer.clear()
                writeBufferVector[0]!!.clear()
                writeBufferVector.removeAt(0)
                // make null to catch the initial if statement to write a new one.
                jsonHeaderWrite = null

                // Set socket to read now that writing has finished.
                Logger.d(TAG, "Reading from server ...")
                val ops = SelectionKey.OP_READ //todo might need to reconnect if send buffer empties
                sc.register(selectionKey.selector(), ops, selectionKey.attachment())
            }
        }
    }

    @Throws(IOException::class)
    private fun printTotalBytes(socketChannel: SocketChannel, bytesWritten: Int) {
        val percentDone = ceil(
            (totalNumBytesToWrite.toDouble() - writeBufferVector[0]!!.remaining().toDouble())
                    / totalNumBytesToWrite.toDouble() * 100
        ).toInt()
        val total = totalNumBytesToWrite / 1000000
        Logger.d(
            TAG,
            "Sent " + percentDone + "% of " + total + "Mb to " + socketChannel.remoteAddress
        )
    }

    private fun findOptimalBufferSize(dataSize: Int): Int {
        val closestLog2 = ceil(ln(dataSize.toDouble()) / ln(2.0)).toInt()
        val optimalBufferSize = closestLog2.toDouble().pow(2.0).toInt()
        return optimalBufferSize
    }

    @Throws(JSONException::class)
    private fun generateJsonHeader(numBytesToWrite: Int): JSONObject {
        val jsonHeader = JSONObject().apply {
            put("byteorder", ByteOrder.nativeOrder().toString())
            put("content-length", numBytesToWrite)
            put("content-type", "episode")
            put("content-encoding", "flatbuffer")
        }
        return jsonHeader
    }

    /**
     * recv_buffer may contain 0, 1, or several bytes. If it has more than hdrlen, then process
     * the first two bytes to obtain the length of the jsonHeader. Else exit this function and
     * read from the buffer again until it fills past length hdrlen.
     */
    private fun processProtoHeader() {
        Logger.v(TAG, "processing protoHeader")
        val hdrlen = 2
        if (recvBuffer.position() >= hdrlen) {
            recvBuffer.flip() //pos at 0 and limit set to bitsRead
            jsonHeaderLen =
                recvBuffer.getShort().toInt() // Read 2 bytes converts to short and move pos to 2
            // allocate new ByteBuffer to store full jsonHeader
            jsonHeaderBytes = ByteArray(jsonHeaderLen)

            recvBuffer.compact()

            Logger.v(TAG, "finished processing protoHeader")
        }
    }

    /**
     * As with the processProtoHeader we will check if _recv_buffer contains enough bytes to
     * generate the jsonHeader objects, and if not, leave it alone and read more from socket.
     */
    @Throws(JSONException::class)
    private fun processJsonHeader() {
        Logger.v(TAG, "processing jsonHeader")

        // If you have enough bytes in the _recv_buffer to write out the jsonHeader
        if (jsonHeaderLen - recvBuffer.position() <= 0) {
            recvBuffer.flip()
            recvBuffer.get(jsonHeaderBytes)
            // jsonHeaderBuffer should now be full and ready to convert to a JSONObject
            jsonHeaderRead = JSONObject(String(jsonHeaderBytes))
            Logger.d(TAG, "JSONHeader from server: $jsonHeaderRead")

            try {
                val msgLength = jsonHeaderRead!!.get("content-length") as Int
                msgContent = ByteBuffer.allocate(msgLength)
            } catch (e: JSONException) {
                ErrorHandler.eLog(
                    TAG,
                    "Couldn't get content-length from jsonHeader sent from server",
                    e,
                    true
                )
            }
        }

        // Else return to selector and read more bytes into the _recv_buffer

        // If there are any bytes left over (part of the msg) then move them to the front of the buffer
        // to prepare for another read from the socket
        recvBuffer.compact()
    }

    /**
     * Here a bit different as it may take multiple full _recv_buffers to fill the msgContent.
     * So check if msgContent. Remaining is larger than 0 and if so, dump everything from _recv_buffer to it
     * @param selectionKey : Used to reference the instance and selector
     * @throws ClosedChannelException :
     */
    @Throws(IOException::class, BrokenBarrierException::class, InterruptedException::class)
    private fun processMsgContent(selectionKey: SelectionKey) {
        val msgContent: ByteBuffer = this.msgContent!!
        if (msgContent.remaining() > 0) {
            recvBuffer.flip() //pos at 0 and limit set to bitsRead set ready to read
            msgContent.put(recvBuffer)
            recvBuffer.clear()
        }

        if (msgContent.remaining() == 0) {
            // msgContent should now be full and ready to convert to a various model files.
            socketListener.onServerReadSuccess(jsonHeaderRead!!, msgContent)

            // Clear for next round of communication
            recvBuffer.clear()
            jsonHeaderLen = 0
            jsonHeaderRead = null
            msgContent.clear()

            val totalBytes = msgContent.capacity() / 1000000
            this.msgContent = null
            val timeTaken = (System.nanoTime() - socketReadTimeStart) * 10e-10
            val df = DecimalFormat()
            df.maximumFractionDigits = 2
            Logger.i(
                TAG,
                "Entire message containing " + totalBytes + "Mb recv'd in " + df.format(timeTaken) + "s"
            )

            msgReadComplete = true

            // Set socket to write now that reading has finished.
            // int ops = 0;
            // sc.register(selectionKey.selector(), ops, selectionKey.attachment());
            selectionKey.cancel()
            selector.close()

            doneSignal.await()
        }
    }

    //todo should send this to the MainActivity listener so it can be customized/overridden
    private fun onNewMessageFromServer() {
        // Take info from JSONHeader to parse msgContent into individual model files
        // After parsing all models notify MainActivity that models have been updated
    }

    // todo should be able deal with ByteBuffer from FlatBuffer rather than byte[]
    fun addEpisodeToWriteBuffer(episode: ByteBuffer, doneSignal: CyclicBarrier) {
        try {
            // does pos or limit change in either episode or writeBufferVector at this point?
            val success = writeBufferVector.add(episode)
            this.doneSignal = doneSignal
            Logger.v(TAG, "Added data to writeBuffer")
            val ops = SelectionKey.OP_WRITE
            socketWriteTimeStart = System.nanoTime()
            sc.register(selector, ops, this)
            // socketConnectionManager.start_connection();
            // I want this to trigger the selector that this channel is writeReady.
        } catch (e: NullPointerException) {
            ErrorHandler.eLog(TAG, "SocketConnectionManager.data not initialized yet", e, true)
        } catch (e: ClosedChannelException) {
            ErrorHandler.eLog(TAG, "SocketConnectionManager.data not initialized yet", e, true)
        }
    }
}
