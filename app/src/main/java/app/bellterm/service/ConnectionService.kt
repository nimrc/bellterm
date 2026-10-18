package app.bellterm.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.core.Connection
import app.bellterm.data.AppLanguages
import app.bellterm.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps attached connections alive while the app is in the background, and
 * turns the daemon's own agent notifications (an agent needs input or
 * finished) into Android notifications. The daemon keeps running regardless.
 */
class ConnectionService : Service() {
    private var watcher: Job? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BelltermApp
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            app.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = app.connections.value.size
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            ongoing(count, app.connections.value.values.firstOrNull()?.node?.title),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (watcher == null) {
            watcher = app.scope.launch {
                app.connections.collectLatest { connections ->
                    connections.values.forEach { connection -> launch { forward(app, connection) } }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun forward(app: BelltermApp, connection: Connection) {
        connection.events.collect { event ->
            if (event is Connection.Event.Notification && !app.foreground) {
                notifyAgent(connection, event)
            }
        }
    }

    private fun notifyAgent(connection: Connection, event: Connection.Event.Notification) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            this,
            connection.node.id.hashCode(),
            MainActivity.intent(this, connection.node.id, event.pane),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, BelltermApp.CHANNEL_AGENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body ?: connection.node.title)
            .setSubText(connection.node.title)
            .setCategory(if (event.attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (event.attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(this).notify((event.pane ?: event.title).hashCode(), notification)
    }

    private fun ongoing(count: Int, title: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BelltermApp.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (count == 1 && title != null) getString(R.string.attached_to, title)
                else resources.getQuantityString(R.plurals.attached_hosts, count, count),
            )
            .setContentText(getString(R.string.attached_detail))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), disconnect)
            .build()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "app.bellterm.DISCONNECT_ALL"

        fun update(context: Context) {
            val app = context.applicationContext as BelltermApp
            val intent = Intent(context, ConnectionService::class.java)
            if (app.connections.value.isEmpty()) {
                context.stopService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
