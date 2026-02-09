package jp.oist.abcvlib.core.inputs.microcontroller

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import jp.oist.abcvlib.core.inputs.Publisher
import jp.oist.abcvlib.core.inputs.PublisherManager

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
        mHandlerThread = HandlerThread("batteryThread")
        mHandlerThread.start()
        handler = Handler(mHandlerThread.looper)
        publisherManager.onPublisherInitialized()
        super.start()
    }

    override fun stop() {
        mHandlerThread.quitSafely()
        handler = null
        super.stop()
    }

    override fun getRequiredPermissions(): ArrayList<String> {
        return ArrayList()
    }
}