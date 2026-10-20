# 0.10.8 验证记录（2026-10-20）

设备：vivo S50 / V2528A，覆盖安装 versionCode 55、versionName 0.10.8。沿用现有签名及数据库 v7。

## 自动收录

修复前可重复观察到：`enabled_notification_listeners` 包含滤见，但 `dumpsys notification` 的实际 Live notification listeners 中没有滤见，`dumpsys activity services com.lvjian.app` 为空。新通知无法到达仓储。旧实现又把授权查询当作连接查询，界面错误显示正在收集。

修复后以 `onListenerConnected` / `onListenerDisconnected` 驱动连接状态；请求重绑后等待，仍未连接时刷新自身组件。启动时通知排队等待收集范围，串行解析入库；重连与范围变更补收仍在通知栏中的通知，沿用数据库去重，补收不撤销或重新提醒来源通知。

真机结果：

- 覆盖安装并打开后，系统实际监听列表中出现滤见。
- 独立本地通知测试应用发送 `lvjian_probe_live_20261020`，消息正常进入“未整理”。
- 暂时断开并恢复同一监听授权后，系统重新连接；测试消息仍只有一条。
- 测试消息移出未整理，临时测试来源关闭并卸载；手机字体缩放恢复 1.0，系统夜间模式恢复 no。

## 计划页

沿用现有计数：本周为第1周，下周为第2周，最远偏移52周。第一周左箭头禁用。左右箭头替代滑到边缘自动换周；周内横向滚动与双指缩放保留。

真机结果：点击右箭头显示第2周；连续向左滑过日历右边缘后仍为第2周；点击左箭头回到第1周。新增标题及箭头在系统字体缩放1.3时仍可见。

## 构建与检查

- `:app:assembleRelease` 成功，包含 release 必需的 Lint Vital 检查。
- `:app:compileDebugUnitTestKotlin` 后运行 `tools/run-unit-tests.ps1`：103项通过，包含4项监听恢复回归测试。直接运行 JUnit 避免便携 JDK / Gradle worker 在中文路径上的参数文件编码问题；MockWebServer 测试需要本机回环网络权限。
- `git diff --check` 通过。
- 完整 `:app:lintRelease`：1项原有错误、18项警告。错误为 Manifest 中既有的 `QUERY_ALL_PACKAGES` 权限策略检查，与本轮改动无关；未移除个人侧载应用所需的已安装应用列表能力。

交付：根目录 `滤见-0.10.8.apk`。SHA-256：`A101B51E46CEE60263520C93416E26C4A47F175B7A9628E3DA028A81F7EA016E`。
