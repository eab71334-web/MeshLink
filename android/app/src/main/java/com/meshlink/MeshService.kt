package com.meshlink

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** يبقي الـ Mesh شغالاً في الخلفية (Foreground Service). */
class MeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "service")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MeshLink is running")
            .setContentText("Relaying messages for nearby devices")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        (application as MeshApp).mesh.start()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as MeshApp).mesh.stop()
        super.onDestroy()
    }
}
