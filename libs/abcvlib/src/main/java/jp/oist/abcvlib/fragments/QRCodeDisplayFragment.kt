package jp.oist.abcvlib.fragments

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import jp.oist.abcvlib.core.R
import jp.oist.abcvlib.util.ErrorHandler
import jp.oist.abcvlib.util.Logger
import java.util.EnumMap
import androidx.core.graphics.createBitmap

class QRCodeDisplayFragment(
    private val data2Encode: String
) : Fragment(R.layout.q_r_code_display) {

    companion object {
        private val TAG = QRCodeDisplayFragment::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.q_r_code_display, container, false)
        val qrCode = rootView.findViewById<ImageView>(R.id.qrImage)
        val width = container!!.width
        val height = container.height
        try {
            val bitmap = encodeAsBitmap(data2Encode, width, height)
            qrCode.setImageBitmap(bitmap)
        } catch (e: WriterException) {
            ErrorHandler.eLog(TAG, "encodeAsBitmap threw WriterException", e, true)
        }
        return rootView
    }

    @Throws(WriterException::class)
    private fun encodeAsBitmap(contents: String?, imgWidth: Int, imgHeight: Int): Bitmap? {
        if (contents == null) {
            return null
        }

        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 0
        val writer = MultiFormatWriter()
        val result: BitMatrix
        try {
            result = writer.encode(contents, BarcodeFormat.QR_CODE, imgWidth, imgHeight, hints)
        } catch (iae: IllegalArgumentException) {
            Logger.d(TAG, "Unsupported format", iae)
            return null
        }
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        for (y in 0..<height) {
            val offset = y * width
            for (x in 0..<width) {
                pixels[offset + x] = if (result.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}