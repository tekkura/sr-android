package jp.oist.abcvlib.comprehensivedemo

import android.graphics.RectF

internal fun mapDetectionRectToDisplay(
    rect: RectF,
    imageWidth: Int,
    imageHeight: Int
): RectF {
    val displayImageWidth = imageHeight.toFloat()

    val swappedRect = RectF(
        rect.top,
        imageWidth - rect.right,
        rect.bottom,
        imageWidth - rect.left
    )
    return RectF(
        displayImageWidth - swappedRect.right,
        swappedRect.top,
        displayImageWidth - swappedRect.left,
        swappedRect.bottom
    )
}
