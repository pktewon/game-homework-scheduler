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

data class DailyState(
    val game: GameEntity? = null, val tasks: List<DailyTaskDetails> = emptyList(),
    val history: List<DailyHistoryRow> = emptyList(), val now: Long = System.currentTimeMillis(),
    val showHistory: Boolean = false, val message: String = "", val saving: Boolean = false,
    val personalTasks: List<PersonalTaskEntity> = emptyList(), val personalHistory: List<PersonalHistoryRow> = emptyList(),
) {
    val cycle: DailyCycle? get() = game?.dailyResetMinute?.let { DailyCycles.current(now, it, game.serverUtcOffset) }
}

class DailyViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    val profileKey = handle.get<String>("profileKey") ?: PROFILE
    private val db = (application as SchedulerApp).database
    private val repository = DailyRepository(db, profileKey = profileKey)
    private val personalization = PersonalizationRepository(db)
    private val showHistory = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val saving = MutableStateFlow(false)
    private val resumedAt = MutableStateFlow(System.currentTimeMillis())
    private var lastCycle: DailyCycle? = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ticks = resumedAt.flatMapLatest {
        flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    }
    private val official = combine(repository.game, repository.tasks, repository.history, ticks) { game, tasks, history, now ->
        DailyState(game, tasks, history, now)
    }
    private val data = combine(official, repository.personalTasks, repository.personalHistory) { state, tasks, history ->
        state.copy(personalTasks = tasks, personalHistory = history)
    }.onEach { current ->
        val cycle = current.cycle
        if (lastCycle != null && cycle != null && lastCycle != cycle) {
            message.value = "현재 회차가 바뀌었습니다. 이전 완료 기록은 그대로 남아 있습니다."
        }
        lastCycle = cycle
    }
    val state = combine(data, showHistory, message, saving) { data, history, info, busy ->
        data.copy(showHistory = history, message = info, saving = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyState())

    fun resumed() { resumedAt.value = System.currentTimeMillis() }
    fun toggleHistory() { showHistory.value = !showHistory.value }
    fun setResetMinute(minute: Int?) = save {
        repository.setResetMinute(minute)
        message.value = if (minute == null) "초기화 설정을 해제했습니다. 이전 기록은 남아 있습니다."
            else "초기화 시각을 저장했습니다. 이전 회차의 완료 기록은 유지합니다."
    }
    fun setChecked(row: DailyRow, checked: Boolean, confirmed: Boolean = false) = save {
        val cycle = row.cycle ?: throw CatalogException("초기화 시각을 설정해 주세요.")
        if (row.personalTaskId != null) repository.setPersonalChecked(row.personalTaskId, cycle, checked)
        else repository.setChecked(row.eventKey, row.taskKey, cycle, checked, confirmed)
        message.value = if (checked) "이번 회차 완료를 저장했습니다." else "이번 회차 체크를 해제했습니다. 이전 회차 기록은 유지합니다."
    }
    fun saveTask(id: String?, title: String, notes: String, enabled: Boolean) = save {
        personalization.saveTask(profileKey, id, title, notes, enabled)
        message.value = "직접 등록한 숙제를 저장했습니다. 기존 완료 기록은 유지합니다."
    }
    private fun save(action: suspend () -> Unit) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try { action()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                message.value = if (error is CatalogException) error.message.orEmpty() else "저장하지 못했습니다. 다시 시도해 주세요."
            } finally { saving.value = false }
        }
    }
}
