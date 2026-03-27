package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import java.util.Queue

interface SerialTransport {
    fun send(command: RP2040OutgoingCommand, timeout: Int)
    fun send(packet: ByteArray, timeout: Int)
    fun awaitPacketReceived(timeout: Int): Int
    val fifoQueue: Queue<RP2040IncomingCommand>
}
