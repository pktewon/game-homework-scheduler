package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*

data class HomeState(
    val events: List<EventDetails> = emptyList(), val sync: SyncStateEntity? = null,
    val busy: Boolean = false, val message: String = "", val now: Long = System.currentTimeMillis(),
    val gameNames: Map<String, String> = emptyMap(),
    val daily: List<GameDailySummary> = emptyList(), val filter: EventFilter = EventFilter.ALL,
)

class HomeViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = (application as SchedulerApp).repository
    private val dao = (application as SchedulerApp).database.dao()
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val resumedAt = MutableStateFlow(0L)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ticks = resumedAt.flatMapLatest { flow {
        while (true) { emit(System.currentTimeMillis()); delay(30_000) }
    } }
    private val daily = combine(dao.observeAllDailyTasks(), dao.observeAllDailyRecords(),
        dao.observeAllPersonalTasks(), dao.observeAllPersonalRecords(), ::HomeDailyData)
    private val filter = saved.getStateFlow("eventFilter", EventFilter.ALL.name)
    fun filter(value: EventFilter) { saved["eventFilter"] = value.name }
    fun resumed() { resumedAt.value = System.nanoTime() }
    private val content = combine(dao.observeAllEvents(), repository.syncState, busy, message, ticks) { events, sync, loading, info, now ->
        HomeState(events, sync, loading, info, now)
    }
    val state = combine(content, dao.observeGames(), daily, filter) { state, games, tasks, selected ->
        state.copy(gameNames = games.associate { it.profileKey to it.name }, daily = summarizeDaily(games, tasks, state.now),
            filter = EventFilter.entries.firstOrNull { it.name == selected } ?: EventFilter.ALL)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init { refresh() }

    fun refresh() {
        if (busy.value) return
        busy.value = true
        message.value = "공식 일정을 확인하고 있습니다."
        viewModelScope.launch {
            try {
                val result = repository.refresh()
                message.value = if (result.unchanged) "최신 일정입니다. 완료 기록을 유지했습니다."
                    else "공식 일정 ${result.events}개 저장 · 기간 확인 필요 ${result.needsReview}개"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                message.value = "갱신 실패 · 저장된 일정과 완료 기록을 유지합니다.\n" +
                    (if (error is CatalogException) error.message else "인터넷 연결을 확인한 뒤 다시 시도해 주세요.")
            } finally { busy.value = false }
        }
    }

    fun setChecked(event: String, item: String, value: Boolean) {
        viewModelScope.launch {
            try { repository.setChecked(event, item, value)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message.value = "완료 기록을 저장하지 못했습니다. 다시 시도해 주세요." }
        }
    }
}
