package jp.oist.abcvlib.util

import android.util.Log
import jp.oist.abcvlib.util.rp2040.MockRP2040
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A mock implementation of SerialTransport that connects the Android side
 * directly to a simulated RP2040.
 */
internal class MockSerialTransport(private val simulator: MockRP2040) : SerialTransport {
    
    override val fifoQueue: Queue<RP2040IncomingCommand> = LinkedList()
    
    private val lock = ReentrantLock()
    private val packetReceived = lock.newCondition()
    private val packetBuffer = PacketBuffer()

    override fun send(command: RP2040OutgoingCommand, timeout: Int) {
        send(command.toBytes(), timeout)
    }

    override fun send(packet: ByteArray, timeout: Int) {
        // 1. Android sends data to the "cable"
        // 2. The simulator processes the raw bytes and generates a response
        val responseBytes = simulator.processPacket(packet)
        
        if (responseBytes != null) {
            // 3. The "cable" receives the response from the robot
            // 4. We feed it into the phone's PacketBuffer to simulate the hardware interrupt
            lock.lock()
            try {
                packetBuffer.consume(responseBytes) { result ->
                    if (result is PacketBuffer.ParseResult.ReceivedPacket) {
                        Log.d("MockSerialTransport", "Received packet: ${result.command}")
                        synchronized(fifoQueue) {
                            fifoQueue.add(result.command)
                        }
                        packetReceived.signalAll()
                    }
                }
            } finally {
                lock.unlock()
            }
        }
    }

    override fun awaitPacketReceived(timeout: Int): Int {
        lock.lock()
        return try {
            // Check if a packet arrived synchronously during the send() call
            // We check this while holding the lock that 'send' uses to signal.
            val alreadyHasPacket = synchronized(fifoQueue) { fifoQueue.isNotEmpty() }
            
            if (alreadyHasPacket || packetReceived.await(timeout.toLong(), TimeUnit.MILLISECONDS)) {
                Log.d("MockSerialTransport", "Packet available (immediate or signaled)")
                1
            } else {
                Log.d("MockSerialTransport", "packetReceived condition timed out")
                -1
            }
        } catch (e: InterruptedException) {
            Log.e("MockSerialTransport", "awaitPacketReceived interrupted", e)
            -1
        } finally {
            lock.unlock()
        }
    }
}
