package kr.ac.jeiu.gamescheduler

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kr.ac.jeiu.gamescheduler.data.*
import kr.ac.jeiu.gamescheduler.databinding.ItemDailyBinding

data class DailyRow(
    val id: String, val eventKey: String, val taskKey: String, val eventTitle: String, val title: String,
    val info: String, val checked: Boolean, val enabled: Boolean,
    val needsConfirmation: Boolean = false, val cycle: DailyCycle? = null,
    val personalTaskId: String? = null, val sortTime: Long = 0,
)

fun dailyRows(state: DailyState): List<DailyRow> {
    if (state.showHistory) return (state.history.map { h ->
        val r = h.record
        DailyRow("${r.eventKey}:${r.sourceTaskKey}:${r.cycleStartAt}", r.eventKey, r.sourceTaskKey, h.eventTitle,
            h.taskTitle, "회차 시작: ${displayTime(r.cycleStartAt)} (한국 시간)\n완료: ${displayTime(r.completedAt)}" +
                if (r.periodConfirmedByUser) "\n참여 가능 여부를 직접 확인한 회차" else "", true, false, sortTime = r.cycleStartAt)
    } + state.personalHistory.map { h ->
        val r = h.record
        DailyRow("personal:${r.taskId}:${r.cycleStartAt}", "", r.taskId, "직접 등록한 숙제", h.taskTitle,
            "회차 시작: ${displayTime(r.cycleStartAt)} (한국 시간)\n완료: ${displayTime(r.completedAt)}\n당시 시간 기준: UTC${r.serverUtcOffsetAtCompletion}",
            true, false, sortTime = r.cycleStartAt)
    }).sortedByDescending { it.sortTime }
    val cycle = state.cycle
    val records = state.history.filter { it.record.cycleStartAt == cycle?.startAt }
        .associateBy { it.record.eventKey to it.record.sourceTaskKey }
    val official = state.tasks.map { details ->
        val task = details.task
        val record = records[task.eventKey to task.sourceTaskKey]?.record
        val availability = dailyAvailability(task, details.event, cycle != null, state.now)
        val allowed = cycle != null && (record != null || availability in setOf(DailyAvailability.READY, DailyAvailability.NEEDS_PERIOD_CONFIRMATION))
        DailyRow("${task.eventKey}:${task.sourceTaskKey}", task.eventKey, task.sourceTaskKey,
            details.event.title, task.title,
            availability.label + if (record != null) "\n이번 회차 완료: ${displayTime(record.completedAt)}" else "",
            record != null, allowed && !state.saving, availability == DailyAvailability.NEEDS_PERIOD_CONFIRMATION, cycle)
    }
    val ownRecords = state.personalHistory.filter { it.record.cycleStartAt == cycle?.startAt }.associateBy { it.record.taskId }
    val personal = state.personalTasks.map { task ->
        val record = ownRecords[task.taskId]?.record
        DailyRow("personal:${task.taskId}", "", task.taskId, "직접 등록한 숙제", task.title,
            when { cycle == null -> "초기화 시각을 먼저 설정해 주세요"; !task.enabled -> "사용 중지 · 기록 보관"; else -> "이번 회차 체크 가능" } +
                (if (task.notes.isNotBlank()) "\n${task.notes}" else "") +
                (if (record != null) "\n완료: ${displayTime(record.completedAt)}" else ""),
            record != null, cycle != null && (task.enabled || record != null) && !state.saving,
            cycle = cycle, personalTaskId = task.taskId)
    }
    return (personal + official).sortedWith(compareBy<DailyRow> { !it.enabled && !it.checked }.thenBy { it.checked }.thenBy { it.title })
}

class DailyAdapter(private val onCheck: (DailyRow, Boolean) -> Unit, private val onEdit: (String) -> Unit = {}) :
    ListAdapter<DailyRow, DailyAdapter.Holder>(object : DiffUtil.ItemCallback<DailyRow>() {
        override fun areItemsTheSame(a: DailyRow, b: DailyRow) = a.id == b.id
        override fun areContentsTheSame(a: DailyRow, b: DailyRow) = a == b
    }) {
    class Holder(val binding: ItemDailyBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemDailyBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            eventTitle.text = row.eventTitle
            info.text = row.info
            check.setOnCheckedChangeListener(null)
            check.text = row.title
            check.isChecked = row.checked
            check.isEnabled = row.enabled
            edit.isVisible = row.personalTaskId != null
            edit.setOnClickListener { row.personalTaskId?.let(onEdit) }
            check.setOnClickListener {
                // Render persisted state until confirmation/write succeeds, including dialog cancellation.
                val requested = !row.checked
                check.isChecked = row.checked
                onCheck(row, requested)
            }
        }
    }
}
