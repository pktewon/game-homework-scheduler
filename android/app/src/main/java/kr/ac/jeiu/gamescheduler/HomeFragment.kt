package kr.ac.jeiu.gamescheduler

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.*
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.*

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val model: HomeViewModel by viewModels()
    override fun onResume() { super.onResume(); model.resumed() }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        b.events.layoutManager = LinearLayoutManager(requireContext())
        val h = ItemHomeHeaderBinding.inflate(layoutInflater, b.events, false)
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun open(fragment: Fragment, tag: String) {
            parentFragmentManager.beginTransaction().replace(R.id.container, fragment).addToBackStack(tag).commit()
        }
        fun detail(card: EventCard) { open(EventDetailFragment.create(card.details.event.sourceKey), "event-detail") }
        val adapter = EventSummaryAdapter(::detail)
        b.events.adapter = ConcatAdapter(StaticViewAdapter(h.root), adapter)
        h.refresh.setOnClickListener { model.refresh() }
        h.manageGames.setOnClickListener { open(GamesFragment(), "games") }
        h.deadlineSettings.setOnClickListener { open(DeadlineSettingsFragment(), "deadline-settings") }
        val filters = EventFilter.entries.associateWith { filter -> Button(requireContext()).apply {
            isAllCaps = false; setOnClickListener { model.filter(filter) }; h.filters.addView(this)
        } }
        var previousImminent: List<EventCard>? = null
        var previousDaily: List<GameDailySummary>? = null
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val cards = homeCards(state.events, state.now, state.gameNames)
                    val imminent = imminentCards(cards)
                    h.imminentHeading.text = "종료 임박 · 24시간 이내 ${imminent.size}개"
                    if (previousImminent != imminent) {
                        previousImminent = imminent
                        h.imminentItems.removeAllViews()
                        if (imminent.isEmpty()) h.imminentItems.addView(TextView(requireContext()).apply {
                            text = "24시간 이내 마감되는 미완료 일정이 없습니다."
                        })
                        imminent.take(3).forEach { card -> h.imminentItems.addView(Button(requireContext()).apply {
                            isAllCaps = false; text = "${card.gameName} · ${card.details.event.title}\n${eventTimeHint(card.details.event, state.now)}"
                            setOnClickListener { detail(card) }
                        }) }
                        if (imminent.size > 3) h.imminentItems.addView(Button(requireContext()).apply {
                            text = "진행 중 목록에서 나머지 마감 보기"
                            setOnClickListener { model.filter(EventFilter.ONGOING); h.filterInfo.requestFocus(); b.events.smoothScrollToPosition(1) }
                        })
                    }
                    val configured = state.daily.filter { it.cycle != null }
                    h.dailySummary.text = "현재 회차 완료 ${configured.sumOf { it.done }}/${configured.sumOf { it.total }} · 기간 확인 필요 ${configured.sumOf { it.needsReview }}개\n게임별 초기화 기준 · 확인 필요 미체크 숙제는 합계 제외"
                    if (previousDaily != state.daily) {
                        previousDaily = state.daily; h.dailyGames.removeAllViews()
                        if (state.daily.isEmpty()) h.dailyGames.addView(TextView(requireContext()).apply { text = "내 게임을 등록하고 숙제를 추가해 보세요." })
                        state.daily.forEach { summary -> h.dailyGames.addView(Button(requireContext()).apply {
                            isAllCaps = false
                            text = "${summary.game.name} · " + (summary.cycle?.let {
                                "${summary.done}/${summary.total} 완료 · 확인 필요 ${summary.needsReview}\n다음 초기화 ${displayTime(it.endAt)} (한국 시간)"
                            } ?: "초기화 시각 설정 필요")
                            setOnClickListener { open(DailyFragment().apply {
                                arguments = Bundle().apply { putString("profileKey", summary.game.profileKey) }
                            }, "daily") }
                        }) }
                    }
                    h.refresh.isEnabled = !state.busy
                    h.status.text = state.message + (state.sync?.let { "\n원문 확인 ${displayTime(it.sourceCheckedAt)} · 기기 저장 ${displayTime(it.lastSuccessAt)}" } ?: "")
                    filters.forEach { (filter, button) ->
                        button.text = "${if (state.filter == filter) "✓ " else ""}${filter.label} ${filteredCards(cards, filter).size}"
                        button.isSelected = state.filter == filter
                        button.contentDescription = "${filter.label}, ${filteredCards(cards, filter).size}개" + if (button.isSelected) ", 선택됨" else ""
                    }
                    val filtered = filteredCards(cards, state.filter)
                    h.filterInfo.text = "${state.filter.label} · ${filtered.size}개" + (if (filtered.isEmpty()) "\n해당 상태의 이벤트가 없습니다." else "\n카드를 눌러 기간·체크·연결 메모를 확인하세요.")
                    adapter.submitList(filtered)
                }
            }
        }
    }
}

internal class StaticViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(view) {}.apply { setIsRecyclable(false) }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}

private class EventSummaryAdapter(private val onOpen: (EventCard) -> Unit) :
    ListAdapter<EventCard, EventSummaryAdapter.Holder>(object : DiffUtil.ItemCallback<EventCard>() {
        override fun areItemsTheSame(a: EventCard, b: EventCard) = a.details.event.sourceKey == b.details.event.sourceKey
        override fun areContentsTheSame(a: EventCard, b: EventCard) = a == b
    }) {
    class Holder(val b: ItemEventSummaryBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemEventSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position); val e = card.details.event
        with(holder.b) {
            summaryStatus.text = "${card.label} · ${phaseLabel(e)} · ${eventProgress(card.details)}"
            summaryTitle.text = "${card.gameName} · ${e.title}"
            summaryTime.text = eventTimeHint(e, card.now) + if (e.timeShape == "WINDOW") "\n종료: ${displayTime(e.endAt)} (한국 시간)" else ""
            root.setOnClickListener { onOpen(card) }; openDetail.setOnClickListener { onOpen(card) }
        }
    }
}
