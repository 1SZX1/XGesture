package com.slideindex.app.freezer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.launcher.AppTarget
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class FreezerAppLaunchTrampolineActivity : ComponentActivity() {
    @Inject lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        // 多开 / 分身：同一个包名，但快捷方式要启动的是指定 user 下的那个实例。
        val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
        val target = AppTarget(packageName, userId)
        lifecycleScope.launch {
            val settings = deps.settingsRepository.readSnapshot()
            val app = withContext(Dispatchers.IO) {
                FreezerMembers.resolve(
                    this@FreezerAppLaunchTrampolineActivity,
                    deps.appRepository,
                    setOf(FreezerOperations.keyOf(target)),
                ).firstOrNull() ?: deps.appRepository.loadApps(force = false)
                    .firstOrNull { it.packageName == packageName && !it.isClone }
            }
            if (app != null) {
                FreezerOperations.launchAndRestore(
                    this@FreezerAppLaunchTrampolineActivity,
                    deps.appRepository,
                    settings,
                    app
                )
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_freezer_launch_package"
        const val EXTRA_USER_ID = "extra_freezer_launch_user_id"
    }
}
