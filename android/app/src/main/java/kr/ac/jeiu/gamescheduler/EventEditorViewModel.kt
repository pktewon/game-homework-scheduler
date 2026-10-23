package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*

enum class EventEndpoint { PLAY_START, PLAY_END, SHOP_START, SHOP_END }
data class EventEditorState(val draft: EventDraft = EventDraft(), val game: GameEntity? = null,
    val loading: Boolean = true, val saving: Boolean = false, val error: String = "", val saved: Boolean = false)
private data class EditorStatus(val loading: Boolean = true, val saving: Boolean = false, val error: String = "", val saved: Boolean = false)

class EventEditorViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    val profile = handle.get<String>("profileKey") ?: PROFILE
    private val group = handle.get<String>("groupKey")
    private val db = (application as SchedulerApp).database
    private val repository = ManualEventsRepository(db)
    private val draft = MutableStateFlow(EventDraft(group, handle["title"] ?: "", handle["notes"] ?: "",
        handle["playStart"], handle["playEnd"], handle["includeShop"] ?: false, handle["shopStart"], handle["shopEnd"]))
    private val status = MutableStateFlow(EditorStatus())
    val state = combine(draft, db.dao().observeGame(profile), status) { value, game, info ->
        EventEditorState(value, game, info.loading, info.saving, info.error, info.saved)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventEditorState())
    init {
        viewModelScope.launch {
            try {
                if (group != null && handle.get<Boolean>("loaded") != true) draft.value = repository.load(profile, group)
                persist()
                status.value = EditorStatus(loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { status.value = EditorStatus(error = error.message ?: "이벤트를 열 수 없습니다.") }
        }
    }
    private fun persist() {
        val value = draft.value
        handle["title"] = value.title; handle["notes"] = value.notes
        handle["playStart"] = value.playStart; handle["playEnd"] = value.playEnd
        handle["includeShop"] = value.includeShop; handle["shopStart"] = value.shopStart; handle["shopEnd"] = value.shopEnd
        handle["loaded"] = true
    }
    private fun edit(value: EventDraft) {
        if (status.value.loading || status.value.saving) return
        draft.value = value; persist()
    }
    fun title(value: String) = edit(draft.value.copy(title = value))
    fun notes(value: String) = edit(draft.value.copy(notes = value))
    fun shop(value: Boolean) = edit(draft.value.copy(includeShop = value))
    fun time(key: EventEndpoint, millis: Long?) = edit(when (key) {
        EventEndpoint.PLAY_START -> draft.value.copy(playStart = millis)
        EventEndpoint.PLAY_END -> draft.value.copy(playEnd = millis)
        EventEndpoint.SHOP_START -> draft.value.copy(shopStart = millis)
        EventEndpoint.SHOP_END -> draft.value.copy(shopEnd = millis)
    })
    fun save() {
        if (status.value.loading || status.value.saving) return
        status.value = EditorStatus(loading = false, saving = true)
        viewModelScope.launch {
            try {
                repository.save(profile, draft.value)
                status.value = EditorStatus(loading = false, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                status.value = EditorStatus(loading = false, error = if (error is CatalogException) error.message.orEmpty() else "이벤트를 저장하지 못했습니다.")
            }
        }
    }
}
