// 全局主题：「IP 趣味」皮肤 —— 奶油底 + 墨黑主色 + 四色平涂点缀
// 色板对齐 web-preview/preview.css（含 [data-theme=dark] 翻转）；支持浅色/深色/跟随系统
package com.lvjian.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 一套完整配色（字段与 preview.css 变量一一对应） */
class LvPalette(
    val bg: Color, val surface: Color, val ink: Color, val muted: Color,
    val line: Color, val hairline: Color, val fill: Color, val fill2: Color, val elev: Color,
    val fieldBorder: Color, val checkBorder: Color,
    val accent: Color, val onAccent: Color, val highlight: Color,
    val pink: Color, val yellow: Color, val green: Color, val blue: Color, val coral: Color,
    val greenSoft: Color, val greenInk: Color, val danger: Color,
    val plum: Color, val plumSoft: Color,
    val navBg: Color, val navItem: Color,
    val toastBg: Color, val toastInk: Color, val toastAct: Color,
    val scrim: Color
)

// ---- 浅色（默认）：奶油底 --bg #f6f2e7 ----
val LvLightPalette = LvPalette(
    bg = Color(0xFFF6F2E7), surface = Color(0xFFFFFFFF), ink = Color(0xFF201D15),
    muted = Color(0xFF6E6A5C), line = Color(0xFFEDE8DA), hairline = Color(0x0F201D15),
    fill = Color(0xFFEFE9DB), fill2 = Color(0xFFF3EEE1), elev = Color(0xFFFFFFFF),
    fieldBorder = Color(0xFFE2DCCC), checkBorder = Color(0xFFB3AC99),
    accent = Color(0xFF201D15), onAccent = Color(0xFFF6F2E7), highlight = Color(0xFFF7C948),
    pink = Color(0xFFF5A8C4), yellow = Color(0xFFF7C948), green = Color(0xFF7CC96F),
    blue = Color(0xFF5B8DEF), coral = Color(0xFFF26D5B),
    greenSoft = Color(0xFFDFF0D4), greenInk = Color(0xFF3F7A34), danger = Color(0xFFD8462F),
    plum = Color(0xFF6B5A8E), plumSoft = Color(0xFFECE8F6),
    // 浅色导航条 = 奶油面（对齐 preview.css --nav-bg:#fffdf6）：
    // 用户 2026-10-15 明确反馈过浅色下墨黑大 pill「太黑太重」，当时只改了预览，App 漏承接
    navBg = Color(0xFFFFFDF6), navItem = Color(0xFF8A8577),
    toastBg = Color(0xFF201D15), toastInk = Color(0xFFF6F2E7), toastAct = Color(0xFFF7C948),
    scrim = Color(0x52201D15)
)

// ---- 深色：暖墨黑底（不是纯黑），动作色翻转为奶油白，四色压暗一档 ----
val LvDarkPalette = LvPalette(
    bg = Color(0xFF1B1913), surface = Color(0xFF262319), ink = Color(0xFFF2EEE0),
    muted = Color(0xFFA39E8D), line = Color(0xFF3A362A), hairline = Color(0x12F6F2E7),
    fill = Color(0xFF2E2A1F), fill2 = Color(0xFF232016), elev = Color(0xFF2C2920),
    fieldBorder = Color(0xFF454033), checkBorder = Color(0xFF6B6552),
    accent = Color(0xFFF2EEE0), onAccent = Color(0xFF201D15), highlight = Color(0xFFF7C948),
    pink = Color(0xFFC97E9E), yellow = Color(0xFFD9AB34), green = Color(0xFF5DA653),
    blue = Color(0xFF5F83C9), coral = Color(0xFFD05F4E),
    greenSoft = Color(0xFF26371C), greenInk = Color(0xFF9ED88F), danger = Color(0xFFF26D5B),
    plum = Color(0xFFC2B3E0), plumSoft = Color(0xFF322B45),
    navBg = Color(0xFF100E0A), navItem = Color(0xFF8A8577),
    toastBg = Color(0xFFF2EEE0), toastInk = Color(0xFF201D15), toastAct = Color(0xFF8A6D1F),
    scrim = Color(0x80000000)
)

val LocalLvPalette = compositionLocalOf { LvLightPalette }

/*
 * 便捷访问器：界面代码里直接写 LvInk / LvBg ……即可随主题翻转。
 * 注意：这些是 @Composable getter，只能在 Composable 作用域用；
 * 非 Composable 处（默认参数等）请直接用 LvLightPalette.XXX。
 */
val LvBg: Color @Composable get() = LocalLvPalette.current.bg
val LvSurface: Color @Composable get() = LocalLvPalette.current.surface
val LvInk: Color @Composable get() = LocalLvPalette.current.ink
val LvMuted: Color @Composable get() = LocalLvPalette.current.muted
val LvLine: Color @Composable get() = LocalLvPalette.current.line
val LvHairline: Color @Composable get() = LocalLvPalette.current.hairline
val LvFill: Color @Composable get() = LocalLvPalette.current.fill
val LvFill2: Color @Composable get() = LocalLvPalette.current.fill2
val LvElev: Color @Composable get() = LocalLvPalette.current.elev
val LvFieldBorder: Color @Composable get() = LocalLvPalette.current.fieldBorder
val LvCheckBorder: Color @Composable get() = LocalLvPalette.current.checkBorder
val LvAccent: Color @Composable get() = LocalLvPalette.current.accent
val LvOnAccent: Color @Composable get() = LocalLvPalette.current.onAccent
val LvHighlight: Color @Composable get() = LocalLvPalette.current.highlight
val LvPink: Color @Composable get() = LocalLvPalette.current.pink
val LvYellow: Color @Composable get() = LocalLvPalette.current.yellow
val LvGreen: Color @Composable get() = LocalLvPalette.current.green
val LvBlue: Color @Composable get() = LocalLvPalette.current.blue
val LvCoral: Color @Composable get() = LocalLvPalette.current.coral
val LvGreenSoft: Color @Composable get() = LocalLvPalette.current.greenSoft
val LvGreenInk: Color @Composable get() = LocalLvPalette.current.greenInk
val LvDanger: Color @Composable get() = LocalLvPalette.current.danger
val LvPlum: Color @Composable get() = LocalLvPalette.current.plum
val LvPlumSoft: Color @Composable get() = LocalLvPalette.current.plumSoft
val LvNavBg: Color @Composable get() = LocalLvPalette.current.navBg
val LvNavItem: Color @Composable get() = LocalLvPalette.current.navItem
val LvToastBg: Color @Composable get() = LocalLvPalette.current.toastBg
val LvToastInk: Color @Composable get() = LocalLvPalette.current.toastInk
val LvToastAct: Color @Composable get() = LocalLvPalette.current.toastAct
val LvScrim: Color @Composable get() = LocalLvPalette.current.scrim

/*
 * 字号阶梯（对齐 preview.css，界面下限 12sp，正文 15sp 起）：
 *  大标题 27/800（字距收紧）/ 区块题 17/600 / 摘要 16/600 / 正文 15 / 说明 14 / 导航标签 12
 *
 * lineHeight 逐档对齐 preview.css 的实测值（预览那边每档都写死了 line-height，
 * App 之前完全没设 → 走字体自然度量，行距偏紧）：
 *   27 → 36 (.page-heading)              20 → 30 (等比补齐，预览无 20px 档)
 *   17 → 26 (.rule-name / .group-name)   16 → 25 (.paragraph / .toggle-row .label)
 *   15 → 22 (.rule-kv / .mini-switch-label)  14 → 21 (.meta / .detail-source)
 *   12 → 18 (等比补齐)
 */
private fun lvTypography(p: LvPalette) = Typography(
    headlineSmall = TextStyle(fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = p.ink, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = p.ink),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = p.ink),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, color = p.ink),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = p.ink),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = p.muted),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = p.ink),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = p.muted)
)

/** 主题模式：跟随系统 / 浅色 / 深色（设置页开关，持久化在 Prefs） */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun LvJianTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) LvDarkPalette else LvLightPalette
    val mColors = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.highlight,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.fieldBorder, error = p.danger
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.highlight,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.fieldBorder, error = p.danger
    )
    CompositionLocalProvider(LocalLvPalette provides p) {
        MaterialTheme(colorScheme = mColors, typography = lvTypography(p), content = content)
    }
}
