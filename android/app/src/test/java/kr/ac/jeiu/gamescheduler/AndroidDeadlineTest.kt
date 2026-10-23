package kr.ac.jeiu.gamescheduler

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kr.ac.jeiu.gamescheduler.data.*
import kr.ac.jeiu.gamescheduler.notifications.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AndroidDeadlineTest {
    private lateinit var context: Context
    private lateinit var notifier: AndroidDeadlineNotifier
    private lateinit var manager: NotificationManager
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        notifier = AndroidDeadlineNotifier(context); notifier.createChannels()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun notice(shop: Boolean): DeadlineNotice {
        val now = System.currentTimeMillis()
        val e = EventEntity(if (shop) "test:shop" else "test:play", "my-game", "test", "테스트 축제", "테스트", 1,
            if (shop) "SHOP" else "PLAY", "WINDOW", null, now - 1000, now + 3_600_000,
            "CONFIRMED", "", "{}", "", isUserCreated = true)
        return DeadlineNotice(EventDetails(e, emptyList(), emptyList()), "명조", now)
    }
    @Test fun separateChannelsTitlesAndDistinctTapIntentsOpenTheCorrectGameAndPhase() {
        val play = notice(false); val shop = notice(true)
        assertTrue(notifier.post(play)); assertTrue(notifier.post(shop))
        val rows = manager.activeNotifications
        assertEquals(2, rows.size)
        val a = rows.single { it.tag == play.key.tag }.notification
        val b = rows.single { it.tag == shop.key.tag }.notification
        assertEquals(PLAY_CHANNEL, a.channelId); assertEquals(SHOP_CHANNEL, b.channelId)
        assertTrue(a.extras.getString(Notification.EXTRA_TITLE)!!.contains("참여 마감"))
        assertTrue(b.extras.getString(Notification.EXTRA_TITLE)!!.contains("보상 교환 마감"))
        val tapA = shadowOf(a.contentIntent).savedIntent; val tapB = shadowOf(b.contentIntent).savedIntent
        assertNotEquals(tapA.data, tapB.data)
        assertEquals("my-game", tapA.getStringExtra("deadlineProfile"))
        assertEquals("test:shop", tapB.getStringExtra("deadlineEvent"))
        notifier.retain(setOf(shop.key.tag)); assertEquals(shop.key.tag, manager.activeNotifications.single().tag)
    }
    @Test fun revokedPermissionDoesNotPostNotification() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.allowed(false)); assertFalse(notifier.post(notice(false)))
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun disabledChannelBlocksOnlyItsNotificationKind() {
        manager.createNotificationChannel(android.app.NotificationChannel(SHOP_CHANNEL, "교환", NotificationManager.IMPORTANCE_NONE))
        assertFalse(notifier.allowed(true)); assertTrue(notifier.allowed(false))
    }
    @Test fun singleInexactAlarmIsReplacedThenCancelledWithoutExactAlarmPermission() {
        val alarm = AndroidDeadlineAlarm(context)
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))
        val start = System.currentTimeMillis() + 3_600_000
        alarm.schedule(start); assertEquals(1, shadow.scheduledAlarms.size)
        assertEquals(start, shadow.scheduledAlarms.single().triggerAtTime)
        alarm.schedule(start + 60_000); assertEquals(1, shadow.scheduledAlarms.size)
        assertEquals(start + 60_000, shadow.scheduledAlarms.single().triggerAtTime)
        alarm.schedule(null); assertTrue(shadow.scheduledAlarms.isEmpty())
    }
}
