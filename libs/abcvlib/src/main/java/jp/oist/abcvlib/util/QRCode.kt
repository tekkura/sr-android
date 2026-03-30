package jp.oist.abcvlib.util

import android.graphics.Color
import androidx.fragment.app.FragmentManager
import jp.oist.abcvlib.fragments.QRCodeDisplayFragment

class QRCode(
    private val fragmentManager: FragmentManager,
    private val fragmentViewID: Int
) {
    private var codeVisible = false

    fun generate(data2Encode: String, foregroundColor: Int = Color.BLACK) {
        val qrCodeDisplayFragment = QRCodeDisplayFragment(data2Encode, foregroundColor)
        fragmentManager.beginTransaction()
            .replace(fragmentViewID, qrCodeDisplayFragment, FRAGMENT_TAG)
            .setReorderingAllowed(true)
            .commit()
        codeVisible = true
    }

    fun close() {
        val qrCodeDisplayFragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG)
        if (qrCodeDisplayFragment == null) {
            Logger.e("QRCode", "Attempted to close nonexistent QR Code")
            return
        }
        fragmentManager.beginTransaction()
            .remove(qrCodeDisplayFragment)
            .setReorderingAllowed(true)
            .commit()
        codeVisible = false
    }

    private companion object {
        private const val FRAGMENT_TAG = "qrCode"
    }
}
