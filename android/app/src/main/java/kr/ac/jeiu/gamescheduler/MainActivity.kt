package kr.ac.jeiu.gamescheduler

import android.os.Bundle
import android.content.Intent
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) openDeadline(intent)
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { (application as SchedulerApp).deadlines.reconcile() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); openDeadline(intent)
    }
    private fun openDeadline(intent: Intent) {
        val profile = intent.getStringExtra("deadlineProfile") ?: return
        val event = intent.getStringExtra("deadlineEvent") ?: return
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction().replace(R.id.container, GameEventsFragment().apply {
            arguments = Bundle().apply { putString("profileKey", profile); putString("focusEventKey", event) }
        }).addToBackStack("notification-event").commit()
    }
}
