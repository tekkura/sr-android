package jp.oist.abcvlib.core.inputs.publisher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import jp.oist.abcvlib.core.R
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.phone.MicrophoneData
import jp.oist.abcvlib.util.Logger

/** Presents the standard Android response to a required publisher startup failure. */
internal class PublisherStartupFailureHandler(
    private val activity: Activity,
    private val publisherManager: PublisherManager
) {
    private var dialog: AlertDialog? = null
    private val lifecycleOwner = activity as? LifecycleOwner

    init {
        lifecycleOwner?.lifecycle?.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    dismiss()
                    owner.lifecycle.removeObserver(this)
                }
            }
        )
    }

    fun show(failure: PublisherManagerStartupResult.Failure, retry: () -> Unit) {
        val microphoneFailed = failure.requiredFailures.any {
            it.publisher is MicrophoneData
        }

        dismiss()
        dialog = showPublisherStartupFailureDialog(
            activity = activity,
            message = if (microphoneFailed) {
                R.string.microphone_startup_failure_message
            } else {
                R.string.publisher_startup_failure_message
            },
            additionalAction = if (microphoneFailed) {
                PublisherStartupFailureDialogAction(
                    label = R.string.privacy_settings,
                    onClick = ::openPrivacySettings
                )
            } else {
                null
            },
            retry = {
                dialog = null
                retry()
            },
            close = {
                publisherManager.stopPublishers()
                activity.finishAffinity()
            }
        )
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun openPrivacySettings() {
        val privacySettingsOpened = runCatching {
            activity.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        }.isSuccess
        if (!privacySettingsOpened) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }.onFailure {
                Logger.e(TAG, "Unable to open Android settings")
            }
        }
    }

    private companion object {
        const val TAG = "PublisherStartupFailureHandler"
    }
}
