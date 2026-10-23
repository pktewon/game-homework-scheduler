package kr.ac.jeiu.gamescheduler

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

class GamesFragment : Fragment(R.layout.fragment_games) {
    private val model: GamesViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentGamesBinding.bind(view)
        val pad = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        val adapter = GamesAdapter(onOpen = { game ->
            val daily = DailyFragment().apply { arguments = Bundle().apply { putString("profileKey", game.profileKey) } }
            parentFragmentManager.beginTransaction().replace(R.id.container, daily).addToBackStack("game-daily").commit()
        }, onEdit = ::editGame, onEvents = { game ->
            val events = GameEventsFragment().apply { arguments = Bundle().apply { putString("profileKey", game.profileKey) } }
            parentFragmentManager.beginTransaction().replace(R.id.container, events).addToBackStack("game-events").commit()
        })
        binding.games.layoutManager = LinearLayoutManager(requireContext())
        binding.games.adapter = adapter
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.addGame.setOnClickListener { editGame(null) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.addGame.isEnabled = !state.busy
                    binding.status.text = state.message
                    adapter.submitList(state.games)
                }
            }
        }
    }
    private fun editGame(game: GameEntity?) {
        val editor = DialogGameBinding.inflate(layoutInflater)
        editor.name.setText(game?.name.orEmpty())
        editor.server.setText(game?.serverLabel.orEmpty())
        editor.preset.isVisible = game == null
        editor.preset.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf("직접 입력", "명조 · Asia", "소녀전선 2 · HAOPLAY 한국판", "명일방주: 엔드필드 · Asia"))
        editor.preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (game == null && position > 0) {
                    editor.name.setText(listOf("", "명조", "소녀전선 2", "명일방주: 엔드필드")[position])
                    editor.server.setText(if (position == 2) "HAOPLAY 한국판" else "Asia")
                }
            }
        }
        editor.offset.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf("UTC+9 · 한국 시각으로 입력", "UTC+8 · 해당 서버 시각으로 입력", "UTC+0 · 기준시로 입력"))
        editor.offset.setSelection(USER_TIME_OFFSETS.indexOf(game?.serverUtcOffset ?: "+09:00").coerceAtLeast(0))
        if (game != null && !game.isUserCreated) {
            editor.server.isEnabled = false; editor.offset.isEnabled = false
            editor.help.text = "공식 엔필 Asia 일정은 UTC+8 기준을 유지합니다. 표시 이름과 초기화 시각은 직접 설정할 수 있습니다."
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(if (game == null) "게임 등록" else "게임 수정")
            .setView(editor.root).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    editor.name.text.toString().isBlank() -> editor.name.error = "게임 이름을 입력해 주세요."
                    editor.server.text.toString().isBlank() -> editor.server.error = "서버·지역을 입력해 주세요."
                    else -> {
                        model.saveGame(game?.profileKey, editor.name.text.toString(), editor.server.text.toString(), USER_TIME_OFFSETS[editor.offset.selectedItemPosition])
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }
}

private class GamesAdapter(private val onOpen: (GameEntity) -> Unit, private val onEdit: (GameEntity) -> Unit,
                           private val onEvents: (GameEntity) -> Unit) :
    ListAdapter<GameEntity, GamesAdapter.Holder>(object : DiffUtil.ItemCallback<GameEntity>() {
        override fun areItemsTheSame(a: GameEntity, b: GameEntity) = a.profileKey == b.profileKey
        override fun areContentsTheSame(a: GameEntity, b: GameEntity) = a == b
    }) {
    class Holder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = getItem(position)
        with(holder.binding) {
            title.text = game.name
            info.text = "${game.serverLabel}\n${resetText(game.dailyResetMinute, game.serverUtcOffset)}\n" +
                if (game.profileKey == PROFILE) "공식 일정 수신 + 직접 등록 이벤트·숙제" else "직접 등록 이벤트·숙제 · 공식 일정 수집은 아직 지원하지 않음"
            openDaily.setOnClickListener { onOpen(game) }
            editGame.setOnClickListener { onEdit(game) }
            openEvents.setOnClickListener { onEvents(game) }
        }
    }
}
