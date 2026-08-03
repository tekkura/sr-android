package jp.oist.abcvlib.core.inputs.publisher

import android.app.Activity
import android.app.AlertDialog
import androidx.annotation.StringRes
import jp.oist.abcvlib.core.R
import jp.oist.abcvlib.core.inputs.PublisherManager

internal data class PublisherStartupFailureDialogConfig(
    @StringRes val message: Int,
    val additionalAction: PublisherStartupFailureDialogAction? = null
)

internal data class PublisherStartupFailureDialogAction(
    @StringRes val label: Int,
    val onClick: () -> Unit
)

internal fun showPublisherStartupFailureDialog(
    activity: Activity,
    publisherManager: PublisherManager,
    config: PublisherStartupFailureDialogConfig,
    retry: () -> Unit
): AlertDialog {
    val builder = AlertDialog.Builder(activity)
        .setTitle(R.string.publisher_startup_failure_title)
        .setMessage(config.message)
        .setPositiveButton(R.string.retry) { dialog, _ ->
            dialog.dismiss()
            retry()
        }
        .setNegativeButton(R.string.close_app) { _, _ ->
            publisherManager.stopPublishers()
            activity.finishAffinity()
        }
        .setCancelable(false)
    config.additionalAction?.let { builder.setNeutralButton(it.label, null) }

    return builder.create().also { dialog ->
        config.additionalAction?.let { action ->
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener { action.onClick() }
            }
        }
        dialog.show()
    }
}
