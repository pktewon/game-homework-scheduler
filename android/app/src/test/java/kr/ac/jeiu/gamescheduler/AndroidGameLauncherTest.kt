package kr.ac.jeiu.gamescheduler

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.ac.jeiu.gamescheduler.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AndroidGameLauncherTest {
    private lateinit var app: Application
    private val pkg = "com.example.launchgame"
    @Before fun setup() { app = ApplicationProvider.getApplicationContext() }
    private fun register(packageName: String = pkg, name: String = "테스트 게임", activityName: String = "$packageName.MainActivity", enabled: Boolean = true) {
        val info = ApplicationInfo().apply { this.packageName = packageName; this.enabled = enabled }
        val activity = ActivityInfo().apply { this.packageName = packageName; this.name = activityName; exported = true; this.enabled = enabled; applicationInfo = info }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { this.packageName = packageName; applicationInfo = info; activities = arrayOf(activity) })
        val row = ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = name }
        val base = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(app.packageManager).addResolveInfoForIntent(base, row)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(base).setPackage(packageName), row)
    }
    @Test fun launcherListDeduplicatesAndExcludesSelfAndDisabledApps() {
        register(); register(activityName = "$pkg.OtherActivity")
        register(app.packageName, "스케줄러")
        register("com.example.disabled", "중지 앱", enabled = false)
        val rows = GameAppLauncher(app).installedApps()
        assertEquals(1, rows.count { it.packageName == pkg })
        assertFalse(rows.any { it.packageName == app.packageName || it.packageName == "com.example.disabled" })
    }
    @Test fun launchUsesSelectedPackageFrontDoorWithNewTaskFlag() {
        register()
        val launcher = GameAppLauncher(app)
        assertTrue(launcher.available(pkg)); assertEquals(LaunchResult.STARTED, launcher.launch(pkg))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(pkg, intent.component!!.packageName)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }
    @Test fun unlinkedMissingInvalidAndOwnPackageNeverStartAnActivity() {
        val launcher = GameAppLauncher(app)
        assertEquals(LaunchResult.NOT_LINKED, launcher.launch(null))
        for (value in listOf("com.example.missing", "D:/game.apk", app.packageName)) {
            assertFalse(launcher.available(value)); assertEquals(LaunchResult.UNAVAILABLE, launcher.launch(value))
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun removalBetweenLookupAndStartIsHandledAsUnavailable() {
        register()
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException("removed") } }
        assertEquals(LaunchResult.UNAVAILABLE, GameAppLauncher(context).launch(pkg))
    }
    @Test fun deviceRestrictionIsReportedWithoutCrashing() {
        register()
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { throw SecurityException("blocked") } }
        assertEquals(LaunchResult.BLOCKED, GameAppLauncher(context).launch(pkg))
    }
    @Test fun launchingNeverWritesEventChecksOrCreatesCompletionRecords() = runBlocking {
        register()
        val db = Room.inMemoryDatabaseBuilder(app, SchedulerDatabase::class.java).build()
        try {
            db.dao().ensureGame(GameEntity(PROFILE))
            val group = ManualEventsRepository(db).save(PROFILE, EventDraft(title = "체크하지 않은 이벤트"))
            GameAppRepository(db).connect(PROFILE, LaunchableApp(pkg, "게임"))
            val before = db.dao().allEvents()
            assertEquals(LaunchResult.STARTED, GameAppLauncher(app).launch(pkg))
            assertEquals(before, db.dao().allEvents())
            assertNull(db.dao().checkRecord("$group:play", "goal")); assertNull(db.dao().checkRecord("$group:play", "rewards"))
            assertTrue(db.dao().dailyRecords().isEmpty())
        } finally { db.close() }
    }
}
