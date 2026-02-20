package jp.oist.abcvlib.util

import androidx.fragment.app.FragmentManager
import jp.oist.abcvlib.fragments.QRCodeDisplayFragment

class QRCode(
    private val fragmentManager: FragmentManager,
    private val fragmentViewID: Int
) {
    private var codeVisible = false

    fun generate(data2Encode: String) {
        val qrCodeDisplayFragment = QRCodeDisplayFragment(data2Encode)
        fragmentManager.beginTransaction()
            .replace(fragmentViewID, qrCodeDisplayFragment)
            .setReorderingAllowed(true)
            .addToBackStack("qrCode")
            .commit()
        codeVisible = true
    }

    fun close() {
        if (codeVisible) {
            fragmentManager.popBackStack()
        } else {
            Logger.e("QRCode", "Attempted to close nonexistent QR Code")
        }
    }
}
