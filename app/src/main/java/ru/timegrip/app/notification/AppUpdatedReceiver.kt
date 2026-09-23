package ru.timegrip.app.notification

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Installing a new APK over the app kills its process, so the running-timer
 * notification and the periodic sync would stay gone until the app is opened
 * again. This broadcast only brings the process back up: TimeGripApplication
 * .onCreate restores both.
 */
// Not exported and does nothing with the intent, so a spoofed one changes nothing.
@SuppressLint("UnsafeProtectedBroadcastReceiver")
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
