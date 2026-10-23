package kr.ac.jeiu.gamescheduler

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kr.ac.jeiu.gamescheduler.databinding.FragmentDeadlineSettingsBinding

class DeadlineSettingsFragment : Fragment(R.layout.fragment_deadline_settings) {
    private val model: DeadlineSettingsViewModel by viewModels()
    private var binding: FragmentDeadlineSettingsBinding? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermission() }
    private val app get() = requireActivity().application as SchedulerApp
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDeadlineSettingsBinding.bind(view); binding = b
        val pad = b.settingsRoot.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(b.settingsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.enabled.setOnCheckedChangeListener { _, v -> model.edit { it.copy(enabled = v) } }
        b.playEnabled.setOnCheckedChangeListener { _, v -> model.edit { it.copy(playEnabled = v) } }
        b.shopEnabled.setOnCheckedChangeListener { _, v -> model.edit { it.copy(shopEnabled = v) } }
        b.playMinutes.doAfterTextChanged { text -> model.edit { it.copy(playMinutes = text.toString()) } }
        b.shopMinutes.doAfterTextChanged { text -> model.edit { it.copy(shopMinutes = text.toString()) } }
        b.save.setOnClickListener { model.save() }
        b.permission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !app.deadlineNotifier.permissionGranted()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSystemSettings()
        }
        b.systemSettings.setOnClickListener { openSystemSettings() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect { state ->
                    val d = state.draft
                    b.enabled.isChecked = d.enabled; b.playEnabled.isChecked = d.playEnabled; b.shopEnabled.isChecked = d.shopEnabled
                    if (b.playMinutes.text.toString() != d.playMinutes) b.playMinutes.setText(d.playMinutes)
                    if (b.shopMinutes.text.toString() != d.shopMinutes) b.shopMinutes.setText(d.shopMinutes)
                    b.status.text = state.message
                    val editable = !state.loading && !state.saving
                    listOf(b.enabled, b.playEnabled, b.shopEnabled, b.playMinutes, b.shopMinutes, b.save).forEach { it.isEnabled = editable }
                } }
                launch { model.schedule.collect { state ->
                    b.schedule.text = if (state.error.isNotBlank()) state.error else
                        "미발송 예약 ${state.pending}개\n다음 알림 기준 시각: ${state.nextAt?.let { displayTime(it) } ?: "없음"} (한국 시각)"
                } }
            }
        }
    }
    override fun onResume() { super.onResume(); updatePermission() }
    private fun updatePermission() {
        val b = binding ?: return
        val notifier = app.deadlineNotifier
        b.permissionStatus.text = "휴대폰 알림 상태: 참여 ${if (notifier.allowed(false)) "허용" else "차단"} / 교환 ${if (notifier.allowed(true)) "허용" else "차단"}\n차단된 종류는 발송하지 않습니다. 알림 허용 후 설정을 저장해 주세요."
        b.permission.text = if (notifier.permissionGranted()) "휴대폰 알림 허용 상태 확인" else "알림 권한 허용 요청"
        viewLifecycleOwner.lifecycleScope.launch { app.deadlines.reconcile() }
    }
    private fun openSystemSettings() { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)) }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
