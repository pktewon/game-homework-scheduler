package kr.ac.jeiu.gamescheduler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.data.*
import kr.ac.jeiu.gamescheduler.databinding.*

class MemoBoardFragment : Fragment(R.layout.fragment_memo_board) {
    private val model: MemoBoardViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentMemoBoardBinding.bind(view)
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun edit(id: String? = null, event: String? = null) {
            parentFragmentManager.beginTransaction().replace(R.id.container, MemoEditorFragment.create(model.profile, id, event))
                .addToBackStack("memo-editor").commit()
        }
        val adapter = MemoAdapter(onEdit = { edit(it.memo.memoId) }, onEvent = { row ->
            parentFragmentManager.beginTransaction().replace(R.id.container, GameEventsFragment().apply {
                arguments = Bundle().apply { putString("profileKey", model.profile); putString("focusEventKey", row.memo.eventKey) }
            }).addToBackStack("memo-event").commit()
        })
        b.memos.layoutManager = LinearLayoutManager(requireContext()); b.memos.adapter = adapter
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.addMemo.setOnClickListener { edit(event = model.state.value.filterKey) }
        b.allMemos.setOnClickListener { model.allMemos() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    b.heading.text = "${state.game?.name.orEmpty()} · 개인 메모"
                    b.info.text = if (state.filterKey == null) "이 기기에 보관하는 나만의 메모 · 수정한 순서" else "연결 이벤트: ${state.eventTitle ?: "확인 필요"}"
                    b.addMemo.isEnabled = state.game != null
                    b.allMemos.isVisible = state.filterKey != null
                    b.empty.isVisible = state.rows.isEmpty()
                    adapter.submitList(state.rows)
                }
            }
        }
    }
    companion object {
        fun create(profile: String, event: String? = null) = MemoBoardFragment().apply {
            arguments = Bundle().apply { putString("profileKey", profile); putString("eventKey", event) }
        }
    }
}

private class MemoAdapter(private val onEdit: (MemoRow) -> Unit, private val onEvent: (MemoRow) -> Unit) :
    ListAdapter<MemoRow, MemoAdapter.Holder>(object : DiffUtil.ItemCallback<MemoRow>() {
        override fun areItemsTheSame(a: MemoRow, b: MemoRow) = a.memo.memoId == b.memo.memoId
        override fun areContentsTheSame(a: MemoRow, b: MemoRow) = a == b
    }) {
    class Holder(val b: ItemMemoBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemMemoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.b) {
            title.text = row.memo.title; body.text = row.memo.body
            modified.text = "수정: ${displayTime(row.memo.updatedAt)} (한국 시각)"
            event.text = if (row.memo.eventKey == null) "게임 메모 · 이벤트 연결 없음" else "연결: ${row.eventTitle ?: "이벤트 확인 필요"}" +
                if (row.eventMissing == true) " · 보관 일정" else if (row.eventTimeState == "NEEDS_REVIEW") " · 기간 확인 필요" else ""
            openEvent.isVisible = row.memo.eventKey != null
            openEvent.setOnClickListener { onEvent(row) }
            editMemo.setOnClickListener { onEdit(row) }; root.setOnClickListener { onEdit(row) }
        }
    }
}
