// 应用展示工具：包名 → 短中文名（界面展示用，不查 PackageManager，保证列表渲染轻量）
package com.lvjian.app.util

import com.lvjian.app.data.KnownApps

object AppNames {

    /**
     * 包名转展示名。只用于没有 Context、也没有已保存应用名的地方。
     * 内置微信/QQ/企业微信给中文名；其余退回包名最后一段。
     * 有 Context 时用 [label]，不要用这个，否则飞书会显示成 lark。
     */
    fun shortName(packageName: String): String =
        KnownApps.PRESET.firstOrNull { it.first == packageName }?.second
            ?: packageName.substringAfterLast('.')

    /** 界面展示名：优先系统里的应用名（飞书，不是 lark），取不到再退回短名。 */
    fun label(context: android.content.Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: shortName(packageName)
}
