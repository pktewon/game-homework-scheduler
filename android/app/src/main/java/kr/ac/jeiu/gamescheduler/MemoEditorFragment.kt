package kr.ac.jeiu.gamescheduler

import android.os.Bundle
import android.view.View
import android.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentMemoEditorBinding

class MemoEditorFragment : Fragment(R.layout.fragment_memo_editor) {
    private val model: MemoEditorViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentMemoEditorBinding.bind(view)
        val pad = b.editorRoot.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(b.editorRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.title.doAfterTextChanged { model.title(it.toString()) }
        b.body.doAfterTextChanged { model.body(it.toString()) }
        b.save.setOnClickListener { model.save() }
        b.clearEvent.setOnClickListener { model.event(null) }
        var choices: List<Pair<String?, String>> = emptyList()
        b.eventChoice.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("연결할 이벤트 선택")
                .setSingleChoiceItems(choices.map { it.second }.toTypedArray(),
                    choices.indexOfFirst { it.first == model.state.value.draft.eventKey }) { dialog, position ->
                    choices.getOrNull(position)?.let { model.event(it.first) }; dialog.dismiss()
                }.setNegativeButton("취소", null).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.saved) { parentFragmentManager.popBackStack(); return@collect }
                    b.heading.text = if (state.draft.memoId == null) "메모 등록" else "메모 수정"
                    b.gameName.text = state.game?.name.orEmpty()
                    if (b.title.text.toString() != state.draft.title) b.title.setText(state.draft.title)
                    if (b.body.text.toString() != state.draft.body) b.body.setText(state.draft.body)
                    val next = buildList<Pair<String?, String>> {
                        add(null to "이벤트 연결 없음 · 게임 메모")
                        state.events.sortedBy { it.event.title }.forEach {
                            val e = it.event
                            add(e.sourceKey to (e.title + when {
                                e.missingFromLatest -> " (보관 일정)"
                                e.timeState == "NEEDS_REVIEW" -> " (기간 확인 필요)"
                                else -> ""
                            }))
                        }
                        val key = state.draft.eventKey
                        if (key != null && none { it.first == key }) add(key to "연결 이벤트를 찾을 수 없음 · 다시 선택해 주세요")
                    }
                    choices = next
                    b.eventChoice.text = choices.find { it.first == state.draft.eventKey }?.second ?: "이벤트 연결 없음"
                    b.clearEvent.isVisible = state.draft.eventKey != null
                    val enabled = !state.loading && !state.saving && state.game != null
                    listOf(b.title, b.body, b.eventChoice, b.clearEvent, b.save).forEach { it.isEnabled = enabled }
                    b.status.text = state.error
                }
            }
        }
    }
    companion object {
        fun create(profile: String, memo: String? = null, event: String? = null) = MemoEditorFragment().apply {
            arguments = Bundle().apply { putString("profileKey", profile); putString("memoId", memo); putString("eventKey", event) }
        }
    }
}
