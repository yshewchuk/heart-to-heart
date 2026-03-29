package com.yurishewchuk.hearttoheart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yurishewchuk.hearttoheart.services.AlarmService

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
        const val ACTION_DISMISS = "com.yurishewchuk.hearttoheart.ACTION_DISMISS"
    }
}
