// 分享时间表弹层 + 整周 PNG 导出（对齐 preview.js exportWeekPng 的布局口径）
// 微信/QQ：生成图片后用系统分享直接投递到对应应用；其它平台：系统分享面板；导出：存相册
package com.lvjian.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lvjian.app.data.EventColors
import com.lvjian.app.data.PlanEventEntity
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoField

@Composable
fun ShareWeekSheet(
    vm: MainViewModel,
    weekOffset: Int,
    weekMonday: LocalDate,
    events: List<PlanEventEntity>,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    /** 生成 PNG → 缓存文件 Uri（分享用） */
    fun renderPng(): File {
        return exportWeekPng(context, weekOffset, weekMonday, events)
    }

    /** 系统分享（可指定目标应用包名） */
    fun shareTo(pkg: String?) {
        val file = renderPng()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        context.startActivity(Intent.createChooser(intent, "分享时间表").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    LvSheet(
        title = "分享时间表",
        onClose = onClose,
        note = "分享内容为整周时间表图片（含全部时间块）"
    ) {
        ShareChoice("微信") {
            onClose()
            runCatching { shareTo("com.tencent.mm") }
                .onFailure { vm.showToast("未安装微信") }
        }
        ShareChoice("QQ") {
            onClose()
            runCatching { shareTo("com.tencent.mobileqq") }
                .onFailure { vm.showToast("未安装 QQ") }
        }
        ShareChoice("其它平台…") {
            onClose()
            shareTo(null)
        }
        ShareChoice("导出图片到本地") {
            onClose()
            runCatching {
                val file = renderPng()
                // 存进相册（MediaStore，无需权限，API 29+）
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/滤见")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("相册写入失败")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().copyTo(out)
                }
                vm.showToast("已导出时间表图片到相册")
            }.onFailure { vm.showToast("导出失败：${it.message}") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShareChoice(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = LvFill,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 手绘整周时间表 PNG（与页面同口径：08:00~22:00 默认、块超界扩展、今天列浅底）。
 * 2 倍分辨率输出，奶油底（分享通用）。
 */
private fun exportWeekPng(
    context: Context,
    weekOffset: Int,
    weekMonday: LocalDate,
    events: List<PlanEventEntity>
): File {
    val S = 2                                  // 输出分辨率倍数
    val topPad = 64f; val headH = 58f; val bottomPad = 26f
    val dayW = 100f; val gutterW = 46f; val hourPx = 64f

    // 可视范围（与页面同口径）
    var vStart = 8 * 60; var vEnd = 22 * 60
    events.forEach {
        vStart = minOf(vStart, (it.startMin / 60) * 60)
        vEnd = maxOf(vEnd, ((it.endMin + 59) / 60) * 60)
    }
    vStart = vStart.coerceAtLeast(0); vEnd = vEnd.coerceAtMost(24 * 60)
    val totalH = (vEnd - vStart) / 60f * hourPx

    val W = gutterW + 7 * dayW
    val H = topPad + headH + totalH + bottomPad
    val bmp = Bitmap.createBitmap((W * S).toInt(), (H * S).toInt(), Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.scale(S.toFloat(), S.toFloat())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val weekDates = (0..6).map { weekMonday.plusDays(it.toLong()) }
    val todayIdx = if (weekOffset == 0) LocalDate.now().get(ChronoField.DAY_OF_WEEK) - 1 else -1

    // 背景 + 标题
    paint.color = 0xFFF7F8F4.toInt(); cv.drawRect(0f, 0f, W, H, paint)
    paint.color = 0xFF22241F.toInt()
    paint.textSize = 21f; paint.isFakeBoldText = true
    cv.drawText(if (weekOffset == 0) "本周安排" else "第 ${weekOffset + 1} 周安排", 16f, 40f, paint)
    paint.isFakeBoldText = false
    paint.color = 0xFF6F7268.toInt(); paint.textSize = 13f
    val range = "${weekDates[0].monthValue}月${weekDates[0].dayOfMonth}日 – ${weekDates[6].monthValue}月${weekDates[6].dayOfMonth}日 · 滤见"
    cv.drawText(range, W - 16 - paint.measureText(range), 39f, paint)

    // 星期表头（今天墨黑胶囊）
    val wdNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    paint.textAlign = Paint.Align.CENTER
    wdNames.forEachIndexed { i, d ->
        val cx = gutterW + i * dayW + dayW / 2
        paint.textSize = 14f; paint.isFakeBoldText = true
        if (i == todayIdx) {
            paint.color = 0xFF201D15.toInt()
            cv.drawRoundRect(RectF(cx - 22, topPad + 4, cx + 22, topPad + 28), 12f, 12f, paint)
            paint.color = 0xFFF6F2E7.toInt()
        } else {
            paint.color = 0xFF22241F.toInt()
        }
        cv.drawText(d, cx, topPad + 21f, paint)
        paint.isFakeBoldText = false
        paint.color = 0xFF6F7268.toInt(); paint.textSize = 12f
        cv.drawText("${weekDates[i].monthValue}月${weekDates[i].dayOfMonth}日", cx, topPad + 42f, paint)
    }
    paint.textAlign = Paint.Align.LEFT

    // 小时横线 + 时间刻度
    val gy = topPad + headH
    paint.color = 0xFFE2E4DC.toInt(); paint.strokeWidth = 1f
    paint.textSize = 11f
    for (h in vStart..vEnd step 60) {
        val yy = gy + (h - vStart) / 60f * hourPx
        paint.color = 0xFFE2E4DC.toInt()
        cv.drawLine(gutterW, yy, W, yy, paint)
        if (h < vEnd) {
            paint.color = 0xFF9B9E91.toInt()
            cv.drawText(MainViewModel.fmtMin(h), 6f, yy + 13f, paint)
        }
    }
    // 今天列浅底（仅本周）
    if (todayIdx >= 0) {
        paint.color = 0x0D201D15
        cv.drawRect(gutterW + todayIdx * dayW, gy, gutterW + (todayIdx + 1) * dayW, gy + totalH, paint)
    }

    // 时间块（圆角矩形 + 左侧色条 + 时间 + 标题，标题最多两行截断）
    events.forEach { ev ->
        val c = EventColors.light[ev.color] ?: EventColors.light.getValue("gray")
        val evDate = LocalDate.ofEpochDay(ev.epochDay)
        val dayIdx = evDate.get(ChronoField.DAY_OF_WEEK) - 1
        val top = gy + (ev.startMin - vStart) / 60f * hourPx + 1
        val hgt = (ev.endMin - ev.startMin) / 60f * hourPx - 2
        val lx = gutterW + dayIdx * dayW + 3
        val w = dayW - 7
        paint.color = c.second.toInt()
        cv.drawRoundRect(RectF(lx, top, lx + w, top + hgt), 7f, 7f, paint)
        paint.color = c.first.toInt()
        cv.drawRoundRect(RectF(lx, top, lx + 3, top + hgt), 1.5f, 1.5f, paint)
        var ty = top + 16f
        if (hgt >= 46) {
            paint.textSize = 11f; paint.isFakeBoldText = true
            paint.color = c.first.toInt()
            cv.drawText("${MainViewModel.fmtMin(ev.startMin)}-${MainViewModel.fmtMin(ev.endMin)}", lx + 9, ty, paint)
            paint.isFakeBoldText = false
            ty += 15f
        }
        paint.textSize = 12f
        paint.color = 0xFF22241F.toInt()
        // 简单截断两行
        val maxW = w - 14
        var line = ""
        var lines = 0
        for (ch in ev.title) {
            if (paint.measureText(line + ch) > maxW && line.isNotEmpty()) {
                if (ty < top + hgt - 3) { cv.drawText(line, lx + 9, ty, paint); ty += 15f; lines++ }
                line = "$ch"
                if (lines >= 1) { // 第二行末尾加省略号
                    if (paint.measureText(line + "…") > maxW) line = line.dropLast(1)
                    line += "…"
                }
                if (lines >= 2) break
            } else line += ch
        }
        if (lines < 2 && line.isNotEmpty() && ty < top + hgt - 3) cv.drawText(line, lx + 9, ty, paint)
    }

    // 写进缓存（FileProvider 共享用）
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "滤见-本周安排-${weekDates[0]}.png")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
