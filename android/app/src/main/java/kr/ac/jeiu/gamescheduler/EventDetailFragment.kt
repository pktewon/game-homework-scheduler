package kr.ac.jeiu.gamescheduler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentEventDetailBinding

class EventDetailFragment : Fragment(R.layout.fragment_event_detail) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEventDetailBinding.bind(view)
        val key = arguments?.getString("eventKey")
        val app = requireActivity().application as SchedulerApp
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun open(fragment: Fragment, tag: String) {
            parentFragmentManager.beginTransaction().replace(R.id.container, fragment).addToBackStack(tag).commit()
        }
        val adapter = EventAdapter(onCheck = { event, item, done ->
            viewLifecycleOwner.lifecycleScope.launch {
                try { app.repository.setChecked(event, item, done); b.status.text = "완료 기록을 저장했습니다." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { b.status.text = "완료 기록을 저장하지 못했습니다. 다시 시도해 주세요." }
            }
        }, onSource = { url ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: android.content.ActivityNotFoundException) { b.status.text = "공지를 열 브라우저를 설치해 주세요." }
        }, onEdit = { open(EventEditorFragment.create(it.profileKey, it.groupKey), "edit-event") },
            onMemos = { open(MemoBoardFragment.create(it.profileKey, it.sourceKey), "event-memos") })
        b.detailEvents.layoutManager = LinearLayoutManager(requireContext()); b.detailEvents.adapter = adapter
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val ticks = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }
                combine(app.database.dao().observeAllEvents(), app.database.dao().observeGames(), ticks) { events, games, now ->
                    Triple(events, games, now)
                }.collect { (events, games, now) ->
                    val selected = events.firstOrNull { it.event.sourceKey == key }
                    val game = games.firstOrNull { it.profileKey == selected?.event?.profileKey }
                    b.heading.text = selected?.event?.groupTitle ?: "이벤트를 찾을 수 없습니다."
                    b.detailInfo.text = game?.let { "${it.name} · ${it.serverLabel}\n선택한 일정 먼저 · 참여와 교환은 별도 체크\n모든 시각은 한국 시간" } ?: "홈에서 다른 이벤트를 선택해 주세요."
                    b.detailDaily.isEnabled = game != null
                    b.detailDaily.setOnClickListener { game?.let { g -> open(DailyFragment().apply {
                        arguments = Bundle().apply { putString("profileKey", g.profileKey) }
                    }, "daily") } }
                    adapter.submitList(if (selected == null) emptyList() else events.filter {
                        it.event.profileKey == selected.event.profileKey && it.event.groupKey == selected.event.groupKey
                    }.sortedWith(compareBy({ it.event.sourceKey != key }, { it.event.startAt ?: Long.MAX_VALUE }, { it.event.sourceKey }))
                        .map { EventCard(it, now) })
                }
            }
        }
    }
    companion object {
        fun create(eventKey: String) = EventDetailFragment().apply { arguments = Bundle().apply { putString("eventKey", eventKey) } }
    }
}
