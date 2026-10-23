package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*

data class GamesState(val games: List<GameEntity> = emptyList(), val message: String = "", val busy: Boolean = false)
class GamesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PersonalizationRepository((application as SchedulerApp).database)
    private val message = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    val state = combine(repository.games, message, busy) { games, info, loading -> GamesState(games, info, loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesState())
    init { action { repository.ensureDefaultGame() } }
    fun saveGame(id: String?, name: String, server: String, offset: String) = action {
        repository.saveGame(id, name, server, offset)
        message.value = "게임을 저장했습니다. 기존 숙제와 완료 기록은 유지합니다."
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { message.value = if (error is CatalogException) error.message.orEmpty() else "게임을 저장하지 못했습니다." }
            finally { busy.value = false }
        }
    }
}
