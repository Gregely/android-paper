package com.paperscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the notification's "Stop" action. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaperScreenAccessibilityService.stopCapture()
    }
}
