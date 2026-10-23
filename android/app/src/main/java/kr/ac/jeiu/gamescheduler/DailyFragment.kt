package kr.ac.jeiu.gamescheduler

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentDailyBinding
import kr.ac.jeiu.gamescheduler.databinding.DialogTaskBinding

fun resetText(minute: Int?, offset: String = "+08:00"): String {
    if (minute == null) return "초기화 시각 미설정\n선택한 시간 기준(UTC$offset)으로 초기화 시각을 입력해 주세요."
    val server = LocalTime.of(minute / 60, minute % 60)
    val korea = LocalDate.of(2000, 1, 1).atTime(server).atOffset(ZoneOffset.of(offset)).withOffsetSameInstant(ZoneOffset.ofHours(9))
    return "초기화: %02d:%02d (UTC%s) · 한국 %02d:%02d%s".format(server.hour, server.minute, offset,
        korea.hour, korea.minute, if (korea.dayOfMonth == 2) " (다음 날)" else "")
}

class DailyFragment : Fragment(R.layout.fragment_daily) {
    private val model: DailyViewModel by viewModels()
    override fun onResume() { super.onResume(); model.resumed() }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentDailyBinding.bind(view)
        val padding = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        val adapter = DailyAdapter(onCheck = { row, checked ->
            if (checked && row.needsConfirmation) {
                AlertDialog.Builder(requireContext()).setTitle("이번 회차 참여 가능 여부 확인")
                    .setMessage("공식 기간에 불명확한 시각이 있습니다. 게임에서 지금 참여할 수 있는지 확인하셨나요? 확인 후 이번 회차의 완료만 저장합니다. 공식 기간은 확인 필요로 유지합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("확인했고 완료했어요") { _, _ -> model.setChecked(row, true, true) }.show()
            } else model.setChecked(row, checked)
        }, onEdit = { id -> showTaskEditor(id) })
        binding.tasks.layoutManager = LinearLayoutManager(requireContext())
        binding.tasks.adapter = adapter
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.history.setOnClickListener { model.toggleHistory() }
        binding.addTask.setOnClickListener { showTaskEditor(null) }
        binding.clearReset.setOnClickListener { model.setResetMinute(null) }
        binding.setReset.setOnClickListener {
            val offset = model.state.value.game?.serverUtcOffset ?: "+08:00"
            val initial = model.state.value.game?.dailyResetMinute ?: Instant.now().atOffset(ZoneOffset.of(offset)).let { it.hour * 60 + it.minute }
            TimePickerDialog(requireContext(), { _, hour, minute -> model.setResetMinute(hour * 60 + minute) },
                initial / 60, initial % 60, true).apply { setTitle("초기화 시각 · UTC$offset") }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.title.text = (state.game?.name ?: "내 게임") + " · 일일 숙제"
                    binding.settings.text = resetText(state.game?.dailyResetMinute, state.game?.serverUtcOffset ?: "+08:00") + "\n이전 회차 완료 기록은 유지됩니다."
                    binding.addTask.isEnabled = state.game != null && !state.saving
                    binding.cycle.text = state.cycle?.let { "현재 회차 (한국 시간)\n${displayTime(it.startAt)} ~ ${displayTime(it.endAt)}" }
                        ?: "시각 설정 후 현재 회차를 계산합니다."
                    binding.setReset.isEnabled = !state.saving
                    binding.clearReset.isEnabled = !state.saving && state.game?.dailyResetMinute != null
                    binding.history.text = if (state.showHistory) "현재 회차 보기" else "완료 기록 보기 (${state.history.size + state.personalHistory.size})"
                    val rows = dailyRows(state)
                    binding.status.text = state.message + when {
                        state.showHistory && rows.isEmpty() -> "\n저장된 완료 기록이 없습니다."
                        !state.showHistory && rows.isEmpty() -> "\n내 일일 숙제를 등록해 주세요."
                        state.showHistory -> "\n완료 기록은 조회 전용입니다. 현재 회차의 체크만 해제할 수 있습니다."
                        else -> ""
                    }
                    adapter.submitList(rows)
                }
            }
        }
    }
    private fun showTaskEditor(id: String?) {
        val task = id?.let { key -> model.state.value.personalTasks.find { it.taskId == key } }
        if (id != null && task == null) return
        val editor = DialogTaskBinding.inflate(layoutInflater)
        editor.title.setText(task?.title.orEmpty())
        editor.notes.setText(task?.notes.orEmpty())
        editor.enabled.isChecked = task?.enabled ?: true
        val dialog = AlertDialog.Builder(requireContext()).setTitle(if (id == null) "일일 숙제 등록" else "일일 숙제 수정")
            .setView(editor.root).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (editor.title.text.toString().isBlank()) editor.title.error = "숙제 이름을 입력해 주세요."
                else { model.saveTask(id, editor.title.text.toString(), editor.notes.text.toString(), editor.enabled.isChecked); dialog.dismiss() }
            }
        }
        dialog.show()
    }
}
