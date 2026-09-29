package com.paperscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the notification's Stop, Pause 5 min and Resume buttons. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaperScreenAccessibilityService.onNotificationAction(context, intent.action)
    }
}
