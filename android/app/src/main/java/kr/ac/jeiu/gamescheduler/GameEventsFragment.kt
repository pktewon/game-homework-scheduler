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
import kr.ac.jeiu.gamescheduler.data.PROFILE
import kr.ac.jeiu.gamescheduler.databinding.FragmentGameEventsBinding

class GameEventsFragment : Fragment(R.layout.fragment_game_events) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentGameEventsBinding.bind(view)
        val profile = arguments?.getString("profileKey") ?: PROFILE
        val app = requireActivity().application as SchedulerApp
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun edit(group: String? = null) {
            parentFragmentManager.beginTransaction().replace(R.id.container,
                EventEditorFragment.create(profile, group)).addToBackStack("event-editor").commit()
        }
        val adapter = EventAdapter(onCheck = { event, item, done ->
            viewLifecycleOwner.lifecycleScope.launch {
                try { app.repository.setChecked(event, item, done); b.status.text = "완료 기록을 저장했습니다." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { b.status.text = "완료 기록을 저장하지 못했습니다. 다시 시도해 주세요." }
            }
        }, onSource = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, onEdit = { edit(it.groupKey) })
        b.events.layoutManager = LinearLayoutManager(requireContext()); b.events.adapter = adapter
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.addEvent.setOnClickListener { edit() }
        val ticks = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.database.dao().observeEvents(profile), app.database.dao().observeGame(profile), ticks) { events, game, now ->
                    game to events.map { EventCard(it, now) }.sortedWith(compareBy<EventCard> { it.rank }.thenBy { it.sortTime }.thenBy { it.details.event.title })
                }.collect { (game, cards) ->
                    b.heading.text = "${game?.name.orEmpty()} · 이벤트"
                    b.addEvent.isEnabled = game != null
                    b.empty.text = if (cards.isEmpty()) "이벤트를 등록해 참여와 보상 교환을 따로 관리해 보세요." else "참여·교환은 별도 체크 · 직접 등록한 일정만 수정 가능"
                    adapter.submitList(cards)
                }
            }
        }
    }
}
