package kr.ac.jeiu.gamescheduler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
import kr.ac.jeiu.gamescheduler.data.LaunchableApp
import kr.ac.jeiu.gamescheduler.databinding.*

class GameAppFragment : Fragment(R.layout.fragment_game_app) {
    private val model: GameAppViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentGameAppBinding.bind(view)
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        val adapter = AppChoiceAdapter(model::connect)
        b.apps.layoutManager = LinearLayoutManager(requireContext()); b.apps.adapter = adapter
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.refresh.setOnClickListener { model.refresh() }
        b.disconnect.setOnClickListener { model.connect(null) }
        b.search.doAfterTextChanged { model.search(it.toString()) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    b.heading.text = "${state.game?.name.orEmpty()} · 실행 앱 연결"
                    b.current.text = state.game?.appPackageName?.let {
                        "현재 연결: ${state.game.appLabel ?: it}\n$it"
                    } ?: "연결된 실행 앱이 없습니다."
                    b.status.text = state.message
                    b.disconnect.isEnabled = !state.loading && !state.saving && state.game?.appPackageName != null
                    b.refresh.isEnabled = !state.loading && !state.saving
                    if (b.search.text.toString() != state.query) b.search.setText(state.query)
                    b.empty.isVisible = !state.loading && state.apps.isEmpty()
                    adapter.submitList(state.apps.map { AppChoice(it, it.packageName == state.game?.appPackageName,
                        !state.loading && !state.saving && state.game != null) })
                }
            }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    companion object {
        fun create(profile: String) = GameAppFragment().apply { arguments = Bundle().apply { putString("profileKey", profile) } }
    }
}

private data class AppChoice(val app: LaunchableApp, val selected: Boolean, val enabled: Boolean)
private class AppChoiceAdapter(private val connect: (LaunchableApp) -> Unit) : ListAdapter<AppChoice, AppChoiceAdapter.Holder>(object : DiffUtil.ItemCallback<AppChoice>() {
    override fun areItemsTheSame(a: AppChoice, b: AppChoice) = a.app.packageName == b.app.packageName
    override fun areContentsTheSame(a: AppChoice, b: AppChoice) = a == b
}) {
    class Holder(val b: ItemAppChoiceBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemAppChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.b) {
            label.text = row.app.label; packageName.text = row.app.packageName
            connectApp.text = if (row.selected) "연결됨" else "이 앱 연결"
            connectApp.isEnabled = row.enabled && !row.selected
            connectApp.setOnClickListener { connect(row.app) }
        }
    }
}
