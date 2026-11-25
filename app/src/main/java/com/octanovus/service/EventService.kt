package com.octanovus.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.octanovus.MainActivity
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppNotification
import com.octanovus.data.AppPrefs
import com.octanovus.data.LiveUpdates
import com.octanovus.data.NotificationHub
import com.octanovus.data.OrderStep
import com.octanovus.data.Session
import com.octanovus.data.toSteps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the live connection to the desktop (`GET /api/events`) open while the app is in the
 * background, so overdue and status alerts still arrive with the screen off. Android requires a
 * foreground service to do this, which is why a "Connected to desktop" notification stays in the
 * notification shade while it runs.
 *
 * Each connection first catches up on anything missed (`GET /api/notifications?after=<last id>`).
 * Each notification is shown once (matched on its id). It stops itself when the phone is unpaired.
 * Alerts do not arrive while the app is closed or force-stopped: there is no push service.
 */
class EventService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private lateinit var prefs: AppPrefs
    private lateinit var api: ApiClient

    private var steps: Map<String, OrderStep> = emptyMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHub.init(this)
        prefs = AppPrefs(this)
        api = ApiClient(prefs)
        createChannels()
        // Must be called soon after startForegroundService().
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification("Connecting to desktop..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) loop = scope.launch { run() }
        // Not sticky: if Android ends the process, the PIN in memory is gone, so the waiter has to
        // sign in again, and the app starts this service again then.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        NotificationHub.setConnection(NotificationHub.Connection.OFFLINE)
        super.onDestroy()
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            // Nobody is signed in: nothing to connect with.
            if (prefs.deviceToken == null || (Session.pin == null && Session.unlockToken == null)) {
                stopSelf()
                return
            }
            NotificationHub.setConnection(NotificationHub.Connection.CONNECTING)
            updateOngoing("Connecting to desktop...")
            if (steps.isEmpty()) loadSteps()

            // Catch up first. The very first sync fills the list without raising alerts for old orders.
            val firstSync = NotificationHub.lastId == 0L
            when (val missed = api.catchUp(NotificationHub.lastId)) {
                is ApiResult.Ok -> {
                    handle(NotificationHub.receive(missed.value), alert = !firstSync)
                    attempt = 0
                }

                is ApiResult.Err -> {
                    if (missed.code == 401) return authFailed()
                    goOffline()
                    delay(backoffMs(++attempt))
                    continue
                }
            }

            val result = api.streamEvents(
                lastId = NotificationHub.lastId,
                onOpen = {
                    NotificationHub.setConnection(NotificationHub.Connection.CONNECTED)
                    updateOngoing("Connected to desktop")
                    // Reload the visible screen once, in case something changed while disconnected.
                    LiveUpdates.onConnected()
                },
                onNotification = { handle(NotificationHub.receive(listOf(it)), alert = true) },
                onChanged = LiveUpdates::onChanged,
            )
            // The desktop ends the stream when the device is revoked.
            if (result is ApiResult.Err && result.code == 401) return authFailed()
            goOffline()
            delay(backoffMs(++attempt))
        }
    }

    private fun goOffline() {
        NotificationHub.setConnection(NotificationHub.Connection.OFFLINE)
        updateOngoing("Desktop not reachable. Retrying...")
    }

    /** 401 revokes the device; a PIN timeout only ends this service until the waiter signs in again. */
    private fun authFailed() {
        if (Session.expired.value || (Session.pin == null && Session.unlockToken == null)) stopSelf() else unpair()
    }

    private fun unpair() {
        prefs.clearPairing()
        stopSelf()
    }

    /** 2 s, 4 s, 8 s, 16 s, then every 30 s. */
    private fun backoffMs(attempt: Int): Long = minOf(2_000L shl (attempt - 1).coerceIn(0, 4), 30_000L)

    private suspend fun loadSteps() {
        val result = api.protectedGet("/api/overdue-thresholds")
        if (result is ApiResult.Ok) steps = result.value.toSteps().associateBy { it.code }
    }

    /** Raises phone alerts for new notifications, and clears an order's delay alerts once it is served. */
    private fun handle(fresh: List<AppNotification>, alert: Boolean) {
        if (!alert) return
        fresh.forEach { n ->
            when (n.type) {
                "delay" -> postAlert(n)
                "status" -> {
                    val step = n.status?.let { steps[it] }
                    // A final step ends the order: its pending delay alerts go away.
                    if (step?.isFinal == true) clearAlerts(n.orderId)
                    // The desktop's notifyWaiter flag decides whether a status change raises an alert.
                    if (step == null || step.notifyWaiter) postAlert(n)
                }
            }
        }
    }

    private fun postAlert(n: AppNotification) {
        if (!canPostNotifications()) return
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TABLE, n.tableNumber)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            this,
            n.id.toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(n.title)
            .setContentText(n.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .apply { n.at?.let { setWhen(it.toEpochMilli()) } }
            .build()
        // The order id is the tag, so all alerts of one order can be cleared together.
        manager().notify(n.orderId, n.id.toInt(), notification)
    }

    private fun clearAlerts(orderId: String) {
        val manager = manager()
        manager.activeNotifications
            .filter { it.tag == orderId && it.id != ONGOING_ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun updateOngoing(text: String) {
        if (canPostNotifications()) manager().notify(ONGOING_ID, ongoingNotification(text))
    }

    private fun ongoingNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Virunthagam Captain")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
    }

    private fun createChannels() {
        val manager = manager()
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "Connection to desktop", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows while the phone listens for order alerts." }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Order alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Overdue orders and order status changes." }
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun manager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val ONGOING_ID = 1
        private const val ONGOING_CHANNEL = "connection"
        private const val ALERT_CHANNEL = "alerts"
    }
}
