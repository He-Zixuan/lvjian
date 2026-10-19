// 应用图标组件：PackageManager 取图标转 ImageBitmap，异步加载避免阻塞列表滚动
package com.lvjian.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.lvjian.app.ui.theme.LvMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 异步加载某应用的图标；加载中/失败返回 null（调用方给占位） */
@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
            } catch (e: Exception) {
                null // 应用已卸载或图标异常：交给占位图标
            }
        }
    }.value
}

/** 应用图标（带兜底占位）：应用管理页与群聊页共用 */
@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp) {
    val icon = rememberAppIcon(packageName)
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        // 图标不可用时的中性占位
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = LvMuted,
            modifier = Modifier.size(size)
        )
    }
}
