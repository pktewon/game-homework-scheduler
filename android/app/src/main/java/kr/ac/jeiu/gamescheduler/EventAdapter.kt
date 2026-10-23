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

data class EventCard(val details: EventDetails, val now: Long) {
    private val event get() = details.event
    val allDone get() = details.checks.filterNot { it.missingFromLatest }.let { it.isNotEmpty() && it.all { c -> c.isDone } }
    val rank: Int get() = when {
        event.missingFromLatest -> 6
        event.timeShape == "INSTANT" -> 5
        event.timeState == "NEEDS_REVIEW" -> 1
        event.endAt!! <= now -> 4
        event.startAt!! > now -> 2
        allDone -> 3
        else -> 0
    }
    val sortTime get() = when (rank) { 0, 3, 4 -> event.endAt; 2 -> event.startAt; 5 -> event.occursAt; else -> null } ?: Long.MAX_VALUE
    val label get() = when (rank) {
        0 -> if (event.endAt!! - now <= 86_400_000L) "종료 임박 · 24시간 이내" else "진행 중 · 남은 항목 확인"
        1 -> "기간 확인 필요"
        2 -> "예정된 이벤트"
        3 -> "진행 중 · 체크 완료"
        4 -> "기간 종료 · 기록 보관"
        5 -> "콘텐츠 업데이트 알림"
        else -> "최신 공지에서 빠짐 · 기록 유지"
    }
}

class EventAdapter(private val onCheck: (String, String, Boolean) -> Unit, private val onSource: (String) -> Unit) :
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
            section.text = card.label
            title.text = event.title
            period.text = if (event.timeShape == "INSTANT") "발생: ${displayTime(event.occursAt)} (한국 시간)"
                else "시작: ${displayTime(event.startAt)}\n종료: ${displayTime(event.endAt)} (한국 시간)" +
                    if (event.timeState == "NEEDS_REVIEW") "\n불명확한 기간은 자동 활성화·마감 알림을 사용하지 않습니다." else ""
            notes.text = event.notes
            notes.isVisible = event.notes.isNotBlank()
            checks.removeAllViews()
            details.checks.sortedBy { it.sortOrder }.forEach { item ->
                checks.addView(CheckBox(root.context).apply {
                    text = item.title + if (item.missingFromLatest) " (이전 항목 · 기록 유지)" else ""
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isChecked = item.isDone
                    setOnCheckedChangeListener { _, checked -> onCheck(event.sourceKey, item.sourceItemKey, checked) }
                })
            }
            daily.text = details.dailyTasks.joinToString("\n") {
                "일일 확인: ${it.title}" + if (it.missingFromLatest) " · 이전 템플릿" else " · 일일 숙제 화면에서 체크"
            }
            daily.isVisible = details.dailyTasks.isNotEmpty()
            source.setOnClickListener { onSource(event.sourceUrl) }
        }
    }
}
