package kr.ac.jeiu.gamescheduler.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.MainActivity
import kr.ac.jeiu.gamescheduler.R
import kr.ac.jeiu.gamescheduler.SchedulerApp
import kr.ac.jeiu.gamescheduler.displayTime

const val PLAY_CHANNEL = "deadline_participation"
const val SHOP_CHANNEL = "deadline_exchange"

class AndroidDeadlineNotifier(private val context: Context) : DeadlineNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(PLAY_CHANNEL, "이벤트 참여 마감", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SHOP_CHANNEL, "보상 교환 마감", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }
    fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    override fun allowed(shop: Boolean) = permissionGranted() && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(if (shop) SHOP_CHANNEL else PLAY_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    override fun retain(tags: Set<String>) {
        manager.activeNotifications.filter { it.tag?.startsWith("deadline:") == true && it.tag !in tags }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    override fun post(notice: DeadlineNotice): Boolean {
        if (!allowed(notice.isShop)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("gamescheduler").authority("deadline").appendPath(notice.event.sourceKey).build()
            putExtra("deadlineProfile", notice.event.profileKey); putExtra("deadlineEvent", notice.event.sourceKey)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "${notice.event.groupTitle}\n${notice.kindLabel} 마감: ${displayTime(notice.event.endAt)} (한국 시각)"
        val notification = NotificationCompat.Builder(context, if (notice.isShop) SHOP_CHANNEL else PLAY_CHANNEL)
            .setSmallIcon(R.drawable.ic_deadline)
            .setContentTitle("${notice.gameName} · ${notice.kindLabel} 마감 임박")
            .setContentText(text.replace('\n', ' ')).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter((notice.event.endAt!! - System.currentTimeMillis()).coerceAtLeast(1L)).build()
        return try { manager.notify(notice.key.tag, 1, notification); true } catch (_: SecurityException) { false }
    }
}

class AndroidDeadlineAlarm(private val context: Context) : DeadlineAlarm {
    override fun schedule(at: Long?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, DeadlineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) alarm.cancel(pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = reconcile(context, goAsync())
}
class DeadlineRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) reconcile(context, goAsync())
    }
}
private fun reconcile(context: Context, pending: BroadcastReceiver.PendingResult) {
    val app = context.applicationContext as SchedulerApp
    app.backgroundScope.launch { try { app.deadlines.reconcile() } finally { pending.finish() } }
}
