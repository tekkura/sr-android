package jp.oist.abcvlib.core.inputs.microcontroller

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import jp.oist.abcvlib.core.inputs.publisher.Publisher
import jp.oist.abcvlib.core.inputs.publisher.PublisherManager

class BatteryData(
    context: Context,
    publisherManager: PublisherManager
) : Publisher<BatteryDataSubscriber>(context, publisherManager) {

    class Builder(
        private val context: Context,
        private val publisherManager: PublisherManager
    ) {
        fun build(): BatteryData {
            return BatteryData(context, publisherManager)
        }
    }

    fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        for (subscriber in subscribers) {
            handler.post {
                if (!paused) {
                    subscriber.onBatteryVoltageUpdate(timestamp, voltage)
                }
            }
        }
    }

    fun onChargerVoltageUpdate(timestamp: Long, chargerVoltage: Double, coilVoltage: Double) {
        for (subscriber in subscribers) {
            handler.post {
                if (!paused) {
                    subscriber.onChargerVoltageUpdate(timestamp, chargerVoltage, coilVoltage)
                }
            }
        }
    }

    override fun start() {
        try {
            mHandlerThread = HandlerThread("batteryThread")
            mHandlerThread.start()
            handler = Handler(mHandlerThread.looper)
            super.start()
            reportInitializationSucceeded()
        } catch (failure: Exception) {
            stopHandlerThread()
            throw failure
        }
    }

    override fun stop() {
        stopHandlerThread()
        super.stop()
    }

    override fun getRequiredPermissions(): ArrayList<String> {
        return ArrayList()
    }
}
