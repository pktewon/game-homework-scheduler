package kr.ac.jeiu.gamescheduler

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kr.ac.jeiu.gamescheduler.data.EventDetails
import kr.ac.jeiu.gamescheduler.databinding.ItemEventBinding

private val timeFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
fun displayTime(value: Long?): String = value?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "확인 필요"

data class EventCard(val details: EventDetails, val now: Long, val gameName: String = "") {
    private val event get() = details.event
    val allDone get() = details.checks.filterNot { it.missingFromLatest }.let { it.isNotEmpty() && it.all { c -> c.isDone } }
    val status get() = eventStatus(details, now)
    val rank get() = status.rank
    val sortTime get() = when (status) {
        EventStatus.UPCOMING -> event.startAt
        EventStatus.INSTANT -> event.occursAt
        else -> event.endAt
    } ?: Long.MAX_VALUE
    val label get() = if (status == EventStatus.ONGOING && event.endAt!! - now <= 86_400_000L)
        "종료 임박 · 24시간 이내" else when (status) { EventStatus.REVIEW -> "기간 확인 필요"; EventStatus.ARCHIVED -> "보관 · 기록 유지"; else -> status.label }

}

class EventAdapter(private val onCheck: (String, String, Boolean) -> Unit, private val onSource: (String) -> Unit,
                   private val onEdit: (kr.ac.jeiu.gamescheduler.data.EventEntity) -> Unit = {},
                   private val onMemos: (kr.ac.jeiu.gamescheduler.data.EventEntity) -> Unit = {},
                   private val onDetail: ((kr.ac.jeiu.gamescheduler.data.EventEntity) -> Unit)? = null) :
    ListAdapter<EventCard, EventAdapter.Holder>(object : DiffUtil.ItemCallback<EventCard>() {
        override fun areItemsTheSame(a: EventCard, b: EventCard) = a.details.event.sourceKey == b.details.event.sourceKey
        override fun areContentsTheSame(a: EventCard, b: EventCard) = a == b
    }) {
    class Holder(val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position)
        val details = card.details
        val event = details.event
        with(holder.binding) {
            section.text = "${card.label} · ${phaseLabel(event)} · ${eventProgress(details)}\n${eventTimeHint(event, card.now)}"
            gameName.text = card.gameName
            gameName.isVisible = card.gameName.isNotBlank()
            title.text = event.title
            period.text = if (event.timeShape == "INSTANT") "발생: ${displayTime(event.occursAt)} (한국 시간)"
                else "시작: ${displayTime(event.startAt)}\n종료: ${displayTime(event.endAt)} (한국 시간)" +
                    if (event.timeState == "NEEDS_REVIEW") "\n불명확한 기간은 자동 활성화·마감 알림을 사용하지 않습니다." else ""
            notes.text = event.notes
            notes.isVisible = event.notes.isNotBlank()
            checks.removeAllViews()
            details.checks.sortedBy { it.sortOrder }.forEach { item ->
                checks.addView(CheckBox(root.context).apply {
                    text = item.title + (if (item.missingFromLatest) " (이전 항목 · 기록 유지)" else "") +
                        (if (item.isDone) "\n완료: ${displayTime(item.completedAt)} (한국 시간)" else "")
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isChecked = item.isDone
                    isEnabled = !(event.isUserCreated && event.missingFromLatest)
                    setOnCheckedChangeListener { _, checked -> onCheck(event.sourceKey, item.sourceItemKey, checked) }
                })
            }
            daily.text = details.dailyTasks.joinToString("\n") {
                "일일 확인: ${it.title}" + if (it.missingFromLatest) " · 이전 템플릿" else " · 일일 숙제 화면에서 체크"
            }
            daily.isVisible = details.dailyTasks.isNotEmpty()
            source.setOnClickListener { onSource(event.sourceUrl) }
            source.isVisible = event.sourceUrl.isNotBlank()
            editEvent.isVisible = event.isUserCreated
            editEvent.setOnClickListener { onEdit(event) }
            eventMemos.setOnClickListener { onMemos(event) }
            eventDetail.isVisible = onDetail != null
            eventDetail.setOnClickListener { onDetail?.invoke(event) }
        }
    }
}
