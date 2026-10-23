package kr.ac.jeiu.gamescheduler

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentHomeBinding

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val model: HomeViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHomeBinding.bind(view)
        val padding = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        val adapter = EventAdapter(onCheck = model::setChecked, onSource = { url ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, onEdit = { event ->
            parentFragmentManager.beginTransaction().replace(R.id.container,
                EventEditorFragment.create(event.profileKey, event.groupKey)).addToBackStack("edit-event").commit()
        })
        binding.events.layoutManager = LinearLayoutManager(requireContext())
        binding.events.adapter = adapter
        binding.refresh.setOnClickListener { model.refresh() }
        binding.dailyTasks.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(R.id.container, DailyFragment()).addToBackStack("daily").commit()
        }
        binding.manageGames.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(R.id.container, GamesFragment()).addToBackStack("games").commit()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.refresh.isEnabled = !state.busy
                    binding.status.text = buildString {
                        append(state.message)
                        state.sync?.let { append("\n원문 확인: ${displayTime(it.sourceCheckedAt)} · 기기 저장: ${displayTime(it.lastSuccessAt)}") }
                        if (state.events.isEmpty() && !state.busy) append("\n저장된 일정이 없습니다. 새로고침으로 가져오세요.")
                    }
                    adapter.submitList(state.events.map { EventCard(it, state.now, state.gameNames[it.event.profileKey].orEmpty()) }.sortedWith(
                        compareBy<EventCard> { it.rank }.thenBy { it.sortTime }.thenBy { it.details.event.title }))
                }
            }
        }
    }
}
