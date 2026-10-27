package wtf.openstrap.openstrap_edge

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

/**
 * Wakes Dart at the scheduled backup time (see lib/data/backup_alarm.dart).
 *
 * A Dart Timer is not enough on its own: it counts monotonic time, which stops
 * while the CPU is suspended, and the tracking service holds no wake lock. An
 * AlarmManager alarm fires through Doze. Exact when the user allows exact
 * alarms; otherwise allow-while-idle, which Doze may defer by some minutes.
 *
 * The alarm only says "check now" — Dart decides whether a backup is due.
 * A partial wake lock is held from the alarm until Dart releases it, so the
 * phone cannot sleep halfway through an export. It times out on its own, so a
 * Dart side that never answers cannot drain the battery.
 */
object BackupAlarmBridge {
    const val CHANNEL = "openstrap/backup_alarm"
    private const val TAG = "BackupAlarm"
    private const val REQUEST_CODE = 4307
    private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val PREFS = "openstrap_runtime"
    const val PENDING_KEY = "pending_backup_alarm"

    private var wakeLock: PowerManager.WakeLock? = null

    fun register(engine: FlutterEngine, context: Context) {
        val app = context.applicationContext
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "schedule" -> {
                        val atMs = (call.argument<Number>("atMs"))?.toLong()
                        if (atMs == null) cancel(app) else schedule(app, atMs)
                        result.success(true)
                    }
                    "cancel" -> {
                        cancel(app)
                        result.success(true)
                    }
                    "canScheduleExact" -> result.success(canScheduleExact(app))
                    "requestExact" -> result.success(requestExact(app))
                    "acquire" -> {
                        acquire(app)
                        result.success(true)
                    }
                    "release" -> {
                        release()
                        result.success(true)
                    }
                    "takePending" -> {
                        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        val pending = prefs.getBoolean(PENDING_KEY, false)
                        if (pending) prefs.edit().remove(PENDING_KEY).apply()
                        result.success(pending)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BackupAlarmReceiver::class.java)
            .setAction(BackupAlarmReceiver.ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** Opens the system "Alarms & reminders" page for this app. */
    private fun requestExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    // Exact alarms are checked with canScheduleExact() first; the lint cannot
    // see that guard across the branch.
    @SuppressLint("MissingPermission")
    private fun schedule(context: Context, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            // Exact permission revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
        Log.i(TAG, "scheduled at $atMs exact=${canScheduleExact(context)}")
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    fun acquire(context: Context) {
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openstrap:backup")
            ?.also {
                // Not reference-counted: the alarm and Dart may both acquire,
                // and one release must let the phone sleep again.
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock?.acquire(WAKE_TIMEOUT_MS)
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    fun markPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_KEY, true).apply()
    }
}

class BackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        BackupAlarmBridge.acquire(context)
        val engine = FlutterEngineCache.getInstance().get(EdgeApplication.ENGINE_ID)
        if (engine != null) {
            MethodChannel(engine.dartExecutor.binaryMessenger, BackupAlarmBridge.CHANNEL)
                .invokeMethod("fire", null)
            return
        }
        // No Dart running: leave a flag Dart reads at startup, then start it.
        // The wake lock (with its timeout) keeps the phone up meanwhile.
        BackupAlarmBridge.markPending(context)
        EdgeApplication.ensureEngine(context)
    }

    companion object {
        const val ACTION = "wtf.openstrap.openstrap_edge.BACKUP_ALARM"
    }
}
