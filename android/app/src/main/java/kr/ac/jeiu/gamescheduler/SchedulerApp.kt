package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.room.Room
import kr.ac.jeiu.gamescheduler.data.CatalogRepository
import kr.ac.jeiu.gamescheduler.data.SchedulerDatabase
import kr.ac.jeiu.gamescheduler.data.DailyRepository
import kr.ac.jeiu.gamescheduler.notifications.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class SchedulerApp : Application() {
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy {
        Room.databaseBuilder(this, SchedulerDatabase::class.java, "game_scheduler_database")
            .addMigrations(SchedulerDatabase.MIGRATION_1_2, SchedulerDatabase.MIGRATION_2_3, SchedulerDatabase.MIGRATION_3_4, SchedulerDatabase.MIGRATION_4_5, SchedulerDatabase.MIGRATION_5_6).build()
    }
    val repository by lazy { CatalogRepository(database) }
    val dailyRepository by lazy { DailyRepository(database) }
    val deadlineNotifier by lazy { AndroidDeadlineNotifier(this).also { it.createChannels() } }
    val deadlines by lazy { DeadlineCoordinator(database, deadlineNotifier, AndroidDeadlineAlarm(this)) }
    override fun onCreate() {
        super.onCreate()
        deadlineNotifier.createChannels()
        backgroundScope.launch {
            combine(database.dao().observeAllEvents(), database.dao().observeDeadlineSettings(), database.dao().observeGames()) { events, settings, games ->
                Triple(events, settings, games)
            }.distinctUntilChanged().collect { deadlines.reconcile() }
        }
    }
}
