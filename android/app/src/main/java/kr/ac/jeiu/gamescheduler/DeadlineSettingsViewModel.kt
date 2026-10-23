package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*

data class DeadlineSettingsDraft(val enabled: Boolean = false, val playEnabled: Boolean = true, val playMinutes: String = "1440",
    val shopEnabled: Boolean = true, val shopMinutes: String = "180")
data class DeadlineSettingsState(val draft: DeadlineSettingsDraft = DeadlineSettingsDraft(), val loading: Boolean = true,
    val saving: Boolean = false, val message: String = "")

class DeadlineSettingsViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as SchedulerApp
    private val value = MutableStateFlow(DeadlineSettingsState())
    val state = value.asStateFlow()
    val schedule = app.deadlines.state
    init {
        viewModelScope.launch {
            try {
                val saved = app.database.dao().deadlineSettings() ?: DeadlineSettingsEntity()
                val draft = if (handle.get<Boolean>("loaded") == true) DeadlineSettingsDraft(handle["enabled"] ?: false,
                    handle["playEnabled"] ?: true, handle["playMinutes"] ?: "1440", handle["shopEnabled"] ?: true, handle["shopMinutes"] ?: "180")
                else DeadlineSettingsDraft(saved.enabled, saved.playEnabled, saved.playLeadMinutes.toString(), saved.shopEnabled, saved.shopLeadMinutes.toString())
                value.value = DeadlineSettingsState(draft, loading = false); persist()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { value.value = DeadlineSettingsState(message = "알림 설정을 읽지 못했습니다. 화면을 다시 열어 주세요.") }
        }
    }
    private fun persist() {
        val d = value.value.draft
        handle["loaded"] = true; handle["enabled"] = d.enabled; handle["playEnabled"] = d.playEnabled
        handle["playMinutes"] = d.playMinutes; handle["shopEnabled"] = d.shopEnabled; handle["shopMinutes"] = d.shopMinutes
    }
    fun edit(transform: (DeadlineSettingsDraft) -> DeadlineSettingsDraft) {
        if (value.value.loading || value.value.saving) return
        value.value = value.value.copy(draft = transform(value.value.draft), message = ""); persist()
    }
    fun save() {
        val current = value.value
        if (current.loading || current.saving) return
        val d = current.draft
        val play = d.playMinutes.toIntOrNull(); val shop = d.shopMinutes.toIntOrNull()
        if (play == null || shop == null || play !in 1..10080 || shop !in 1..10080) {
            value.value = current.copy(message = "참여·교환 알림 시점은 각각 1~10,080분(7일) 사이로 입력해 주세요."); return
        }
        value.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                app.deadlines.save(DeadlineSettingsEntity(enabled = d.enabled, playEnabled = d.playEnabled, playLeadMinutes = play,
                    shopEnabled = d.shopEnabled, shopLeadMinutes = shop))
                value.value = value.value.copy(saving = false, message = "알림 설정을 저장했습니다.")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { value.value = value.value.copy(saving = false, message = "설정을 저장하지 못했습니다. 다시 시도해 주세요.") }
        }
    }
}
