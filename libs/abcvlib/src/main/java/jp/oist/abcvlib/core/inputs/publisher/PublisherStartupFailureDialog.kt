package jp.oist.abcvlib.core.inputs.publisher

import android.app.Activity
import android.app.AlertDialog
import androidx.annotation.StringRes
import jp.oist.abcvlib.core.R

internal data class PublisherStartupFailureDialogAction(
    @StringRes val label: Int,
    val onClick: () -> Unit
)

internal fun showPublisherStartupFailureDialog(
    activity: Activity,
    @StringRes message: Int,
    additionalAction: PublisherStartupFailureDialogAction? = null,
    retry: () -> Unit,
    close: () -> Unit
): AlertDialog {
    val builder = AlertDialog.Builder(activity)
        .setTitle(R.string.publisher_startup_failure_title)
        .setMessage(message)
        .setPositiveButton(R.string.retry) { dialog, _ ->
            dialog.dismiss()
            retry()
        }
        .setNegativeButton(R.string.close_app) { _, _ -> close() }
        .setCancelable(false)
    additionalAction?.let { builder.setNeutralButton(it.label, null) }

    return builder.create().also { dialog ->
        additionalAction?.let { action ->
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener { action.onClick() }
            }
        }
        dialog.show()
    }
}
