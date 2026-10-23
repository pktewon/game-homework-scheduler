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

fun resetText(minute: Int?): String {
    if (minute == null) return "초기화 시각 미설정\n게임에서 확인한 Asia 서버 시각(UTC+8)을 입력해 주세요."
    val server = LocalTime.of(minute / 60, minute % 60)
    val korea = LocalDate.of(2000, 1, 1).atTime(server).atOffset(ZoneOffset.ofHours(8)).withOffsetSameInstant(ZoneOffset.ofHours(9))
    return "초기화: 서버 %02d:%02d (UTC+8) · 한국 %02d:%02d%s".format(server.hour, server.minute,
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
        val adapter = DailyAdapter { row, checked ->
            if (checked && row.needsConfirmation) {
                AlertDialog.Builder(requireContext()).setTitle("이번 회차 참여 가능 여부 확인")
                    .setMessage("공식 기간에 불명확한 시각이 있습니다. 게임에서 지금 참여할 수 있는지 확인하셨나요? 확인 후 이번 회차의 완료만 저장합니다. 공식 기간은 확인 필요로 유지합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("확인했고 완료했어요") { _, _ -> model.setChecked(row, true, true) }.show()
            } else model.setChecked(row, checked)
        }
        binding.tasks.layoutManager = LinearLayoutManager(requireContext())
        binding.tasks.adapter = adapter
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.history.setOnClickListener { model.toggleHistory() }
        binding.clearReset.setOnClickListener { model.setResetMinute(null) }
        binding.setReset.setOnClickListener {
            val initial = model.state.value.game?.dailyResetMinute ?: Instant.now().atOffset(ZoneOffset.ofHours(8)).let { it.hour * 60 + it.minute }
            TimePickerDialog(requireContext(), { _, hour, minute -> model.setResetMinute(hour * 60 + minute) },
                initial / 60, initial % 60, true).apply { setTitle("초기화 시각 · Asia 서버 (UTC+8)") }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.settings.text = resetText(state.game?.dailyResetMinute) + "\n설정을 바꿔도 이전 회차 완료 기록은 유지됩니다."
                    binding.cycle.text = state.cycle?.let { "현재 회차 (한국 시간)\n${displayTime(it.startAt)} ~ ${displayTime(it.endAt)}" }
                        ?: "시각 설정 후 현재 회차를 계산합니다."
                    binding.setReset.isEnabled = !state.saving
                    binding.clearReset.isEnabled = !state.saving && state.game?.dailyResetMinute != null
                    binding.history.text = if (state.showHistory) "현재 회차 보기" else "완료 기록 보기 (${state.history.size})"
                    val rows = dailyRows(state)
                    binding.status.text = state.message + when {
                        state.showHistory && rows.isEmpty() -> "\n저장된 완료 기록이 없습니다."
                        !state.showHistory && rows.isEmpty() -> "\n숙제 템플릿이 없습니다. 일정 화면에서 새로고침해 주세요."
                        state.showHistory -> "\n완료 기록은 조회 전용입니다. 현재 회차의 체크만 해제할 수 있습니다."
                        else -> ""
                    }
                    adapter.submitList(rows)
                }
            }
        }
    }
}
