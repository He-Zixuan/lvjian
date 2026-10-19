// 群聊导入向导：截图 OCR 兜底方案（通知监听路线下没有一键读群列表的接口）
// 流程：步骤引导 → 系统图片选择器多选截图 → 本地 OCR → 多选确认（可取消误识别/手动补充）→ 批量入库
// 导入目标：全部已监控应用 + 「其它」自定义应用名（对齐设计稿 renderImportGuide）
package com.lvjian.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.KnownApps
import com.lvjian.app.groups.OcrException
import com.lvjian.app.groups.ScreenshotOcrProvider
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.util.AppNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 向导步骤 */
private enum class ImportStep {
    GUIDE,      // 步骤引导（说明 + 选来源应用 + 选截图）
    RUNNING,    // OCR 识别中
    CONFIRM     // 候选群名多选确认
}

@OptIn(ExperimentalLayoutApi::class) // FlowRow（芯片流式排列）
@Composable
fun ImportGroupsScreen(
    vm: MainViewModel,
    initialTarget: String?,          // 从会话子页进入时预选该应用
    onClose: () -> Unit,
    onImportedToApp: (String) -> Unit // 导入完成 → 落在目标应用的会话子页
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitored by vm.monitoredApps.collectAsStateWithLifecycle()

    var step by remember { mutableStateOf(ImportStep.GUIDE) }
    // 导入目标：包名或 "custom"（自定义应用名）
    var targetApp by remember { mutableStateOf(initialTarget ?: KnownApps.WECHAT) }
    var customName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // OCR 提取的候选群名 + 勾选状态（默认全选）
    var candidates by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    // 手动补充输入
    var manualInput by remember { mutableStateOf("") }

    // 导入目标候选：全部已建档的监控应用（含自定义）+「其它」
    val targets = monitored.map { it.packageName }.ifEmpty { listOf(KnownApps.WECHAT) }

    /** 目标展示名 */
    fun targetName(): String =
        if (targetApp == "custom") customName.trim().ifEmpty { "其它应用" }
        else monitored.firstOrNull { it.packageName == targetApp }?.appName
            ?: AppNames.label(context, targetApp)

    // OCR 执行器：选完图后跑识别，成功进确认页，失败留在引导页给错误提示
    fun runOcr(uris: List<Uri>) {
        if (uris.isEmpty()) return
        step = ImportStep.RUNNING
        errorMsg = null
        scope.launch {
            try {
                val provider = ScreenshotOcrProvider(context)
                provider.setImages(uris)
                val names = withContext(Dispatchers.Default) {
                    provider.fetchGroups(targetApp)
                }
                if (names.isEmpty()) {
                    errorMsg = "没有提取到候选群名，请换更清晰的截图重试。"
                    step = ImportStep.GUIDE
                } else {
                    candidates = names.map { it to true } // 默认全选
                    step = ImportStep.CONFIRM
                }
            } catch (e: OcrException) {
                errorMsg = e.message
                step = ImportStep.GUIDE
            } catch (e: Exception) {
                errorMsg = "识别失败：${e.message ?: "未知原因"}，请重试。"
                step = ImportStep.GUIDE
            }
        }
    }

    // 系统图片选择器（Photo Picker，无需存储权限），一次选多张
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> -> runOcr(uris) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item {
            SubHeader(
                if (step == ImportStep.CONFIRM) "确认导入" else "导入群聊",
                onBack = onClose
            )
        }

        when (step) {
            // ---------- 步骤引导 ----------
            ImportStep.GUIDE -> {
                item {
                    LvCard(Modifier.fillMaxWidth()) {
                        Text("怎么做", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. 打开「${targetName()}」的群列表（如微信：通讯录 → 群聊）\n" +
                                "2. 逐屏截图\n" +
                                "3. 回到这里，点下方按钮选图\n" +
                                "4. 识别出群名后勾选确认，批量导入（导入即默认开启监控）\n\n" +
                                "识别在本机完成，截图不会上传。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // 来源应用选择
                item {
                    LvCard(Modifier.fillMaxWidth()) {
                        Text("从哪个应用导入？", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        // 芯片流式排列
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            targets.forEach { pkg ->
                                val on = targetApp == pkg
                                Surface(
                                    onClick = { targetApp = pkg },
                                    shape = RoundedCornerShape(50),
                                    color = if (on) LvAccent else LvFill
                                ) {
                                    Text(
                                        AppNames.label(context, pkg),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (on) LvOnAccent else LvInk,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            // 其它（自定义应用名）
                            val customOn = targetApp == "custom"
                            Surface(
                                onClick = { targetApp = "custom" },
                                shape = RoundedCornerShape(50),
                                color = if (customOn) LvAccent else LvFill
                            ) {
                                Text(
                                    "其它",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (customOn) LvOnAccent else LvInk,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                        if (targetApp == "custom") {
                            Spacer(Modifier.height(10.dp))
                            LvInput(customName, { customName = it }, "输入应用名，如：钉钉")
                        }
                    }
                }
                // 错误提示（识别失败/无文字，明示不静默）
                if (errorMsg != null) {
                    item {
                        Text(errorMsg!!, style = MaterialTheme.typography.bodyMedium, color = LvDanger)
                    }
                }
                item {
                    LvPrimaryButton(
                        "选择截图",
                        onClick = {
                            pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---------- 识别中 ----------
            ImportStep.RUNNING -> {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = LvAccent)
                        Spacer(Modifier.height(14.dp))
                        Text("正在识别截图…", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("首次使用需加载识别模型，可能稍慢", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------- 确认页 ----------
            ImportStep.CONFIRM -> {
                item {
                    LvListCard(Modifier.fillMaxWidth()) {
                        candidates.forEachIndexed { i, (name, checked) ->
                            if (i > 0) LvDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { now ->
                                        candidates = candidates.mapIndexed { j, c ->
                                            if (j == i) c.first to now else c
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = LvAccent,
                                        checkmarkColor = LvOnAccent,
                                        uncheckedColor = LvCheckBorder
                                    )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    name, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                // 手动补充一条
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            LvInput(manualInput, { manualInput = it }, "漏了？手动补一个群名")
                        }
                        Spacer(Modifier.width(8.dp))
                        LvSecondaryButton(
                            "添加",
                            onClick = {
                                val n = manualInput.trim()
                                if (n.isNotEmpty() && candidates.none { it.first == n }) {
                                    candidates = candidates + (n to true)
                                    manualInput = ""
                                } else if (n.isNotEmpty()) {
                                    vm.showToast("这个群名已经在清单里了")
                                }
                            }
                        )
                    }
                }
                // 导入按钮
                item {
                    val selected = candidates.filter { it.second }.map { it.first }
                    Text(
                        "已选 ${selected.size} 个群，导入后默认开启监控",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LvPrimaryButton(
                        "导入 ${selected.size} 个群到「${targetName()}」",
                        onClick = {
                            scope.launch {
                                // 自定义应用先建档（伪包名），再导入
                                val pkg = if (targetApp == "custom") {
                                    vm.ensureCustomApp(customName.trim().ifEmpty { "其它应用" })
                                } else targetApp
                                vm.importGroups(pkg, selected) { added ->
                                    vm.showToast(
                                        if (added > 0) "已导入 $added 个群到「${targetName()}」"
                                        else "这些群都已存在，没有新增"
                                    )
                                    if (targetApp != "custom") onImportedToApp(pkg) else onClose()
                                }
                            }
                        },
                        enabled = selected.isNotEmpty() && (targetApp != "custom" || customName.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
