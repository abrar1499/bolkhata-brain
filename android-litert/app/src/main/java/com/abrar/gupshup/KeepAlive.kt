package com.abrar.gupshup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service: keeps the app process (model + downloads + replies) alive while the user is in other apps. */
class KeepAlive : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Brain.shutdown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Gup Shup chal raha hai", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, KeepAlive::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Gup Shup chal raha hai")
            .setContentText(intent?.getStringExtra("text") ?: "Model tayyar hai, baat-cheet mehfooz hai")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Band karein", stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gupshup:work").apply { setReferenceCounted(false) }
        if (Brain.busy) wake?.acquire(3 * 60 * 60 * 1000L) else if (wake?.isHeld == true) wake?.release()
        return START_NOT_STICKY
    }

    override fun onDestroy() { if (wake?.isHeld == true) wake?.release(); super.onDestroy() }

    companion object { const val CH = "gupshup"; const val ACTION_STOP = "stop" }
}
