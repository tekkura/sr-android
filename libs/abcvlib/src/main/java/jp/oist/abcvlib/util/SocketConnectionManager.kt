package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.Logger.d
import jp.oist.abcvlib.util.Logger.v
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.IllegalBlockingModeException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.CyclicBarrier

class SocketConnectionManager(
    private val socketListener: SocketListener,
    private val inetSocketAddress: InetSocketAddress,
    private val episode: ByteBuffer,
    private val doneSignal: CyclicBarrier
) : Runnable {
    private lateinit var sc: SocketChannel
    private lateinit var selector: Selector
    private val TAG = javaClass.simpleName
    private lateinit var socketMessage: SocketMessage

    override fun run() {
        try {
            selector = Selector.open()
            startConnection()
            do {
                val eventCount = selector.select(0)
                // events are int representing how many keys have changed state
                val events = selector.selectedKeys()
                if (eventCount != 0) {
                    val selectedKeys = selector.selectedKeys()
                    for (selectedKey in selectedKeys) {
                        try {
                            val socketMessage = selectedKey.attachment() as SocketMessage
                            socketMessage.processEvents(selectedKey)
                            selectedKeys.remove(selectedKey)
                        } catch (e: ClassCastException) {
                            ErrorHandler.eLog(
                                TAG,
                                "selectedKey attachment not a SocketMessage type",
                                e,
                                true
                            )
                        }
                    }
                }
            } while (selector.isOpen) //todo remember to close the selector somewhere

            close()
        } catch (e: IOException) {
            ErrorHandler.eLog(TAG, "Error", e, true)
        }
    }

    internal fun startConnection() {
        try {
            sc = SocketChannel.open()
            sc.configureBlocking(false)

            // sc.setOption(SO_SNDBUF, 2^27);
            d(TAG, "Initializing connection with $inetSocketAddress")
            val connected = sc.connect(inetSocketAddress)

            socketMessage = SocketMessage(socketListener, sc, selector)
            v(TAG, "socketChannel.isConnected ? : " + sc.isConnected)

            socketMessage.addEpisodeToWriteBuffer(episode, doneSignal)

            v(TAG, "registering with selector to connect")
            val ops = SelectionKey.OP_CONNECT
            val selectionKey = sc.register(selector, ops, socketMessage)
            v(TAG, "Registered with selector")
        } catch (e: Exception) {
            when (e) {
                is IOException, is ClosedSelectorException, is IllegalBlockingModeException,
                is CancelledKeyException, is IllegalArgumentException -> {
                    ErrorHandler.eLog(TAG, "Initial socket connect and registration:", e, true)
                }

                else -> throw e
            }
        }
    }

    /**
     * Should be called prior to exiting app to ensure zombie threads don't remain in memory.
     */
    fun close() {
        try {
            v(TAG, "Closing connection: ${sc.remoteAddress}")
            selector.close()
            sc.close()
        } catch (e: IOException) {
            ErrorHandler.eLog(TAG, "Error closing connection", e, true)
        }
    }
}
