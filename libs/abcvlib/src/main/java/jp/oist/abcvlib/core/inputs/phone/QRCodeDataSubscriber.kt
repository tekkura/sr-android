package jp.oist.abcvlib.core.inputs.phone

import jp.oist.abcvlib.core.inputs.Subscriber

interface QRCodeDataSubscriber : Subscriber {
    fun onQRCodeDetected(qrDataDecoded: String)
}
