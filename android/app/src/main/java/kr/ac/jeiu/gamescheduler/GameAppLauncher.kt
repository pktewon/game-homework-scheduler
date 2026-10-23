package kr.ac.jeiu.gamescheduler

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kr.ac.jeiu.gamescheduler.data.LaunchableApp
import kr.ac.jeiu.gamescheduler.data.validAppPackage

enum class LaunchResult { STARTED, NOT_LINKED, UNAVAILABLE, BLOCKED }
fun launchMessage(result: LaunchResult) = when (result) {
    LaunchResult.STARTED -> "앱을 열었습니다. 숙제 완료는 플레이 후 직접 체크해 주세요."
    LaunchResult.NOT_LINKED -> "실행할 앱이 연결되지 않았습니다. 실행 앱 연결에서 설치된 게임 앱을 선택해 주세요."
    LaunchResult.UNAVAILABLE -> "연결한 앱이 설치되어 있지 않거나 실행할 수 없습니다. 설치 상태를 확인하거나 실행 앱을 다시 연결해 주세요."
    LaunchResult.BLOCKED -> "앱 실행이 제한되어 있습니다. 앱의 사용 중지·기기 제한 설정을 확인해 주세요."
}

class GameAppLauncher(private val context: Context) {
    private val pm get() = context.packageManager
    @Suppress("DEPRECATION")
    fun installedApps(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0).mapNotNull { row ->
            val activity = row.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName
            if (pkg == context.packageName || !validAppPackage(pkg) || !activity.enabled || !activity.exported ||
                !activity.applicationInfo.enabled) return@mapNotNull null
            val label = row.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }.take(120)
            LaunchableApp(pkg, label)
        }.distinctBy { it.packageName }.sortedWith(compareBy<LaunchableApp> { it.label.lowercase() }.thenBy { it.packageName })
    }
    private fun launchIntent(packageName: String?): Intent? {
        if (packageName == null || packageName == context.packageName || !validAppPackage(packageName)) return null
        return pm.getLaunchIntentForPackage(packageName)
    }
    fun available(packageName: String?): Boolean = try { launchIntent(packageName) != null } catch (_: RuntimeException) { false }
    fun launch(packageName: String?): LaunchResult {
        if (packageName == null) return LaunchResult.NOT_LINKED
        return try {
            val intent = launchIntent(packageName) ?: return LaunchResult.UNAVAILABLE
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LaunchResult.STARTED
        } catch (_: ActivityNotFoundException) { LaunchResult.UNAVAILABLE
        } catch (_: SecurityException) { LaunchResult.BLOCKED
        } catch (_: RuntimeException) { LaunchResult.UNAVAILABLE }
    }
}
