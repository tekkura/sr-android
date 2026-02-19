package jp.oist.abcvlib.handsOnApp

import android.graphics.Bitmap
import jp.oist.abcvlib.handsOnApp.databinding.ActivityMainBinding
import kotlin.concurrent.Volatile

class DebugInfoViewer(private val binding: ActivityMainBinding) {
    @Volatile
    var text1: String = ""

    @Volatile
    var text2: String = ""

    @Volatile
    var text3: String = ""

    @Volatile
    var image: Bitmap? = null


    fun update() {
        binding.description.text = text1
        binding.description2.text = text2
        binding.description3.text = text3
        if (image != null) {
            binding.demoImage.setImageBitmap(image)
        }
    }
}
