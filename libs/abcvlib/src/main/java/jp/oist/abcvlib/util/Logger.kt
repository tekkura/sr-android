package jp.oist.abcvlib.util

import android.util.Log
import jp.oist.abcvlib.core.BuildConfig

/**
 * Custom logger wrapper.
 * Logs (v/d/i/w) only for DEBUG builds.
 * Errors (e) are always logged.
 */
object Logger {

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.v(tag, msg, tr)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.d(tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.i(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }
}