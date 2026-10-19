// 作用范围弹层（对齐设计稿 sheet 'rule-scope'）：多选 + 按应用分组折叠 + 全选开关
// key 规范见 ScopeKeys；「全部群」与其它互斥，「X 的全部群」与 X 的具体群互斥，清空兜底回全部群
package com.lvjian.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lvjian.app.data.GroupEntity
import com.lvjian.app.data.ScopeKeys
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.util.AppNames

/** key → 展示文案 */
fun scopeKeyLabel(key: String, context: android.content.Context? = null): String = when {
    key == ScopeKeys.ALL -> "全部应用"
    key.startsWith("app:") -> "${nameOf(context, key.removePrefix("app:"))} 的全部通知"
    key.startsWith("grp:") -> {
        val rest = key.removePrefix("grp:")
        val pkg = rest.substringBefore("|")
        val name = rest.substringAfter("|")
        "${nameOf(context, pkg)} · $name"
    }
    else -> key
}

private fun nameOf(context: android.content.Context?, pkg: String): String =
    if (context != null) AppNames.label(context, pkg) else AppNames.shortName(pkg)

/** key 集合 → 逗号连接的展示文案 */
fun scopeSetLabel(keys: String, context: android.content.Context? = null): String {
    val list = ScopeKeys.decode(keys)
    if (list.isEmpty() || ScopeKeys.ALL in list) return "全部应用"
    return list.joinToString("、") { scopeKeyLabel(it, context) }
}

/**
 * 多选切换（对齐 preview.js scopeToggleKey 的互斥规则）：
 * 「全部群」与其它互斥；「X 的全部群」与 X 的具体群互斥；
 * 全选开着时点具体群 = 把这一个从全选摘出去；手动勾齐一个应用的全部群 → 归并成全选。
 */
fun scopeToggle(keys: String, key: String, groups: List<GroupEntity>): String {
    var set = ScopeKeys.decode(keys).filter { it != ScopeKeys.ALL }.toMutableList()
    when {
        key == ScopeKeys.ALL ->
            set = if (ScopeKeys.decode(keys).contains(ScopeKeys.ALL)) mutableListOf() else mutableListOf(ScopeKeys.ALL)
        set.contains(key) -> set.remove(key)
        key.startsWith("app:") -> {
            val pkg = key.removePrefix("app:")
            set.removeAll { it.startsWith("grp:$pkg|") }
            set.add(key)
        }
        key.startsWith("grp:") -> {
            val rest = key.removePrefix("grp:")
            val pkg = rest.substringBefore("|")
            val allKey = ScopeKeys.app(pkg)
            if (set.contains(allKey)) {
                // 全选态下单击某个群 = 取消这一个：展开成全选减一的具体群列表
                set.remove(allKey)
                groups.filter { it.appPackage == pkg }
                    .map { ScopeKeys.group(it.appPackage, it.name) }
                    .filter { it != key }
                    .forEach { if (it !in set) set.add(it) }
            } else {
                set.add(key)
                // 勾齐一个应用的全部群 → 自动归并成全选
                val appKeys = groups.filter { it.appPackage == pkg }
                    .map { ScopeKeys.group(it.appPackage, it.name) }
                if (appKeys.isNotEmpty() && appKeys.all { it in set }) {
                    set.removeAll { it.startsWith("grp:$pkg|") }
                    set.add(allKey)
                }
            }
        }
    }
    if (set.isEmpty()) set = mutableListOf(ScopeKeys.ALL)
    return ScopeKeys.encode(set)
}

/**
 * 作用范围弹层：多选。
 * @param groups 已知群列表（分组数据源）
 * @param allLabel 「全部群」选项的副标题（营销规则用「不限应用」，默认 null）
 */
@Composable
fun ScopeSheet(
    currentKeys: String,
    groups: List<GroupEntity>,
    onChange: (String) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    // 弹层内编辑中的集合（完成时才写回）
    var sel by remember { mutableStateOf(currentKeys) }
    // 按应用折叠状态（默认收起；选中项所在的应用自动展开）
    val collapsed = remember {
        mutableStateMapOf<String, Boolean>().apply {
            val apps = groups.map { it.appPackage }.distinct()
            apps.forEach { this[it] = true }
            // 选中项所在应用展开
            ScopeKeys.decode(currentKeys).forEach { k ->
                val pkg = when {
                    k.startsWith("app:") -> k.removePrefix("app:")
                    k.startsWith("grp:") -> k.removePrefix("grp:").substringBefore("|")
                    else -> null
                }
                if (pkg != null) this[pkg] = false
            }
        }
    }
    val selList = ScopeKeys.decode(sel)
    val selCount = if (ScopeKeys.ALL in selList) 0 else selList.size

    LvSheet(
        title = "作用范围",
        onClose = onDone,
        backIcon = true,
        actions = {
            LvPrimaryButton(
                if (selCount > 0) "完成（已选 $selCount 项）" else "完成",
                onClick = { onChange(sel); onDone() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column {
            Text("可多选；选「全部应用」会清掉其它选项。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            // 全部群
            ScopeChoice(
                label = "全部应用", sub = "不限应用",
                on = ScopeKeys.ALL in selList, indent = false
            ) { sel = scopeToggle(sel, ScopeKeys.ALL, groups) }

            // 按应用分组（可折叠 + 全选开关）
            val appPkgs = groups.map { it.appPackage }.distinct()
            for (pkg in appPkgs) {
                val appGroups = groups.filter { it.appPackage == pkg }
                val allKey = ScopeKeys.app(pkg)
                val allOn = allKey in selList
                val isCollapsed = collapsed[pkg] != false
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { collapsed[pkg] = !isCollapsed }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(AppNames.label(context, pkg), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            LvIcons.ChevronDown, contentDescription = null, tint = LvMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    // 全选开关
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { sel = scopeToggle(sel, allKey, groups) }
                    ) {
                        Text("全选", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        LvSwitch(checked = allOn, onChange = { sel = scopeToggle(sel, allKey, groups) })
                    }
                }
                if (!isCollapsed) {
                    for (g in appGroups) {
                        val key = ScopeKeys.group(g.appPackage, g.name)
                        ScopeChoice(
                            label = g.name, sub = null,
                            on = allOn || key in selList, indent = true
                        ) { sel = scopeToggle(sel, key, groups) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 单个候选项行：选中 = 浅主色底 + 对勾 */
@Composable
private fun ScopeChoice(label: String, sub: String?, on: Boolean, indent: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (on) LvFill else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .padding(start = if (indent) 18.dp else 0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label + (sub?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (on) Icon(LvIcons.Check, contentDescription = null, tint = LvAccent, modifier = Modifier.size(18.dp))
        }
    }
}
