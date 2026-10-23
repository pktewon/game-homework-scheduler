package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kr.ac.jeiu.gamescheduler.data.*

data class MemoBoardState(val game: GameEntity? = null, val rows: List<MemoRow> = emptyList(),
    val filterKey: String? = null, val eventTitle: String? = null)

class MemoBoardViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    val profile = handle.get<String>("profileKey") ?: PROFILE
    private val dao = (application as SchedulerApp).database.dao()
    private val filter = handle.getStateFlow<String?>("eventKey", null)
    val state = combine(dao.observeGame(profile), dao.observeMemos(profile), dao.observeEvents(profile), filter) { game, memos, events, key ->
        MemoBoardState(game, if (key == null) memos else memos.filter { it.memo.eventKey == key }, key,
            events.find { it.event.sourceKey == key }?.event?.title)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoBoardState())
    fun allMemos() { handle["eventKey"] = null }
}
