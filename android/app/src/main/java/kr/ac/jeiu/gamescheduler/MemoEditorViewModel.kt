package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*

data class MemoEditorState(val draft: MemoDraft = MemoDraft(), val game: GameEntity? = null,
    val events: List<EventDetails> = emptyList(), val loading: Boolean = true, val saving: Boolean = false,
    val error: String = "", val saved: Boolean = false)
private data class MemoEditorStatus(val loading: Boolean = true, val saving: Boolean = false, val error: String = "", val saved: Boolean = false)

class MemoEditorViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    val profile = handle.get<String>("profileKey") ?: PROFILE
    private val id = handle.get<String>("memoId")
    private val dao = (application as SchedulerApp).database.dao()
    private val repository = MemoRepository((application as SchedulerApp).database)
    private val draft = MutableStateFlow(MemoDraft(id, handle["title"] ?: "", handle["body"] ?: "",
        if (handle.get<Boolean>("loaded") == true) handle["selectedEvent"] else handle["eventKey"]))
    private val status = MutableStateFlow(MemoEditorStatus())
    val state = combine(draft, dao.observeGame(profile), dao.observeEvents(profile), status) { d, game, events, info ->
        MemoEditorState(d, game, events, info.loading, info.saving, info.error, info.saved)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoEditorState())
    init {
        viewModelScope.launch {
            try {
                if (id != null && handle.get<Boolean>("loaded") != true) draft.value = repository.load(profile, id)
                persist(); status.value = MemoEditorStatus(loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { status.value = MemoEditorStatus(error = e.message ?: "메모를 열 수 없습니다.") }
        }
    }
    private fun persist() {
        handle["title"] = draft.value.title; handle["body"] = draft.value.body
        handle["selectedEvent"] = draft.value.eventKey; handle["loaded"] = true
    }
    private fun edit(transform: (MemoDraft) -> MemoDraft) {
        if (status.value.loading || status.value.saving) return
        draft.value = transform(draft.value); persist()
    }
    fun title(value: String) = edit { it.copy(title = value) }
    fun body(value: String) = edit { it.copy(body = value) }
    fun event(value: String?) = edit { it.copy(eventKey = value) }
    fun save() {
        if (status.value.loading || status.value.saving) return
        status.value = MemoEditorStatus(loading = false, saving = true)
        viewModelScope.launch {
            try {
                repository.save(profile, draft.value); status.value = MemoEditorStatus(loading = false, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                status.value = MemoEditorStatus(loading = false, error = if (e is CatalogException) e.message.orEmpty() else "메모를 저장하지 못했습니다.")
            }
        }
    }
}
