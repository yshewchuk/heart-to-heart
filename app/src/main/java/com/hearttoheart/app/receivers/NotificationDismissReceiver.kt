package com.hearttoheart.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hearttoheart.app.services.AlarmService

/**
 * Receives broadcast to dismiss/acknowledge the alarm notification.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> {
                AlarmService.stop(context)
            }
        }
    }
    
    companion object {
        const val ACTION_DISMISS = "com.hearttoheart.ACTION_DISMISS"
    }
}
