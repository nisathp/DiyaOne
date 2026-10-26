package com.naminfo.core

import org.linphone.core.*

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.annotation.MainThread
import com.naminfo.DiyaOneApplication.Companion.coreContext
import org.linphone.core.tools.Log

@MainThread
class CoreKeepAliveThirdPartyAccountsService : Service() {
    companion object {
        private const val TAG = "[Core Keep Alive Third Party Accounts Service]"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("$TAG Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("$TAG onStartCommand")
        coreContext.notificationsManager.onKeepAliveServiceStarted(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("$TAG Task removed by user swipe, scheduling instant restart of keep-alive service...")
        try {
            val restartServiceIntent = Intent(applicationContext, this.javaClass).also {
                it.setPackage(packageName)
            }
            val restartServicePendingIntent = android.app.PendingIntent.getService(
                this, 1, restartServiceIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmService?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } catch (e: Exception) {
            Log.e("$TAG Failed to schedule restart on task removed: $e")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i("$TAG onDestroy")
        coreContext.notificationsManager.onKeepAliveServiceDestroyed()
        coreContext.postOnCoreThread {
            coreContext.onKeepAliveServiceDestroyed()
        }
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
