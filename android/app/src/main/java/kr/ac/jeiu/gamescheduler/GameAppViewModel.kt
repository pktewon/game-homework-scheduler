package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ac.jeiu.gamescheduler.data.*

data class GameAppState(val game: GameEntity? = null, val apps: List<LaunchableApp> = emptyList(),
    val loading: Boolean = true, val saving: Boolean = false, val message: String = "", val query: String = "")
private data class AppLoad(val apps: List<LaunchableApp> = emptyList(), val loading: Boolean = true, val saving: Boolean = false, val message: String = "")

class GameAppViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    val profile = handle.get<String>("profileKey") ?: PROFILE
    private val db = (application as SchedulerApp).database
    private val repository = GameAppRepository(db)
    private val launcher = GameAppLauncher(application)
    private val data = MutableStateFlow(AppLoad())
    private val query = handle.getStateFlow("appQuery", "")
    val state = combine(db.dao().observeGame(profile), data, query) { game, content, text ->
        GameAppState(game, content.apps.filter { it.label.contains(text, true) || it.packageName.contains(text, true) },
            content.loading, content.saving, content.message, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameAppState())
    fun search(value: String) { handle["appQuery"] = value }
    fun refresh() {
        if (data.value.saving) return
        data.value = data.value.copy(loading = true, message = "설치된 실행 앱을 확인하고 있습니다.")
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { launcher.installedApps() }
                data.value = AppLoad(apps, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { data.value = data.value.copy(loading = false, message = "앱 목록을 읽지 못했습니다. 다시 확인해 주세요.") }
        }
    }
    fun connect(target: LaunchableApp?) {
        if (data.value.loading || data.value.saving) return
        data.value = data.value.copy(saving = true, message = "")
        viewModelScope.launch {
            try {
                if (target != null) demand(launcher.available(target.packageName), "선택한 앱이 삭제되었거나 실행할 수 없습니다. 목록을 새로 확인해 주세요.")
                repository.connect(profile, target)
                data.value = data.value.copy(saving = false, message = if (target == null) "실행 앱 연결을 해제했습니다. 완료 기록은 유지합니다." else "실행 앱을 연결했습니다. 숙제 완료는 직접 체크해 주세요.")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { data.value = data.value.copy(saving = false, message = if (e is CatalogException) e.message.orEmpty() else "실행 앱 연결을 저장하지 못했습니다.") }
        }
    }
}
