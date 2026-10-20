package com.lvjian.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lvjian.app.ui.theme.LvJianTheme
import com.lvjian.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/** 仅 -PprogressProbe=true 的独立调试包包含此页，复用正式操作卡，全部是假数据。 */
class ProgressProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (intent.getBooleanExtra("dark", false)) ThemeMode.DARK else ThemeMode.LIGHT
        setContent {
            LvJianTheme(mode) {
                var run by remember { mutableLongStateOf(1) }
                var progress by remember { mutableStateOf(MainViewModel.OrganizeProgress(0, 40, run)) }
                var active by remember { mutableStateOf(true) }
                LaunchedEffect(run) {
                    active = true
                    progress = MainViewModel.OrganizeProgress(0, 40, run)
                    delay(1200)
                    // 模拟最不利情况：40条同帧返回，后台也立即结束。
                    progress = MainViewModel.OrganizeProgress(40, 40, run)
                    active = false
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().padding(22.dp)) {
                        OrganizeCardContent(40 - progress.done, active, progress, { run++ }, {})
                    }
                }
            }
        }
    }
}
