package kr.ac.jeiu.gamescheduler

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentEventEditorBinding

class EventEditorFragment : Fragment(R.layout.fragment_event_editor) {
    private val model: EventEditorViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEventEditorBinding.bind(view)
        val pad = b.editorRoot.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(b.editorRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.title.doAfterTextChanged { model.title(it.toString()) }
        b.notes.doAfterTextChanged { model.notes(it.toString()) }
        b.includeShop.setOnCheckedChangeListener { _, value -> model.shop(value) }
        b.preservationNote.text = "이름·기간을 수정해도 기존 완료 체크는 유지됩니다. 교환 기간 사용을 끄면 교환 일정과 기록을 보관합니다."
        b.save.setOnClickListener { model.save() }
        val buttons = listOf(
            Triple(b.playStart, b.clearPlayStart, EventEndpoint.PLAY_START),
            Triple(b.playEnd, b.clearPlayEnd, EventEndpoint.PLAY_END),
            Triple(b.shopStart, b.clearShopStart, EventEndpoint.SHOP_START),
            Triple(b.shopEnd, b.clearShopEnd, EventEndpoint.SHOP_END),
        )
        for ((pick, clear, key) in buttons) {
            pick.setOnClickListener { pickTime(key) }
            clear.setOnClickListener { model.time(key, null) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.saved) { parentFragmentManager.popBackStack(); return@collect }
                    b.gameName.text = state.game?.name.orEmpty() + " · 한국 시각으로 입력"
                    b.heading.text = if (state.draft.groupKey == null) "이벤트 등록" else "이벤트 수정"
                    if (b.title.text.toString() != state.draft.title) b.title.setText(state.draft.title)
                    if (b.notes.text.toString() != state.draft.notes) b.notes.setText(state.draft.notes)
                    b.includeShop.isChecked = state.draft.includeShop
                    b.shopFields.isVisible = state.draft.includeShop
                    fun label(button: Button, text: String, value: Long?) { button.text = "$text: ${displayTime(value)}" }
                    label(b.playStart, "참여 시작", state.draft.playStart); label(b.playEnd, "참여 종료", state.draft.playEnd)
                    label(b.shopStart, "교환 시작", state.draft.shopStart); label(b.shopEnd, "교환 종료", state.draft.shopEnd)
                    val enabled = !state.loading && !state.saving && state.game != null
                    b.save.isEnabled = enabled; b.title.isEnabled = enabled; b.notes.isEnabled = enabled; b.includeShop.isEnabled = enabled
                    buttons.forEach { (pick, clear, _) -> pick.isEnabled = enabled; clear.isEnabled = enabled }
                    b.status.text = state.error
                }
            }
        }
    }
    private fun pickTime(key: EventEndpoint) {
        val draft = model.state.value.draft
        val value = when (key) {
            EventEndpoint.PLAY_START -> draft.playStart; EventEndpoint.PLAY_END -> draft.playEnd
            EventEndpoint.SHOP_START -> draft.shopStart; EventEndpoint.SHOP_END -> draft.shopEnd
        }
        val zone = ZoneId.of("Asia/Seoul")
        val initial = (value?.let(Instant::ofEpochMilli) ?: Instant.now()).atZone(zone)
        DatePickerDialog(requireContext(), { _, year, month, day ->
            TimePickerDialog(requireContext(), { _, hour, minute ->
                model.time(key, LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli())
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    companion object {
        fun create(profile: String, group: String? = null) = EventEditorFragment().apply {
            arguments = Bundle().apply { putString("profileKey", profile); putString("groupKey", group) }
        }
    }
}
