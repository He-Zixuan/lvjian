# 0.10.11 已整理n/N显示修复

2026-10-20，Asia/Shanghai。

用户澄清需要修的是点击一键整理后的「已整理n/N」，不是顶部未整理角标。本轮仅修改处理进度的展示路径。

## 原因与修改

旧实现用 `key(runId) { rollingCount(done) }`，Animatable初值就是首次观察到的done。StateFlow合并快速回调时，UI首次观察到的可能已经是整批结果，因而没有从0开始的过渡。后台完成后按钮立刻回到「一键整理」，也会截断尚未完成的计数。

新增整数步进的 `animateSavedCount`，每个整数等待32–80ms及真实屏幕帧；每轮显式从0开始，目标改变时从已显示位置继续。只追到真实成功保存数。按钮保持n/N至追赶完成，再恢复一键整理；下方保留最终进度及失败提示。系统关闭动画时直接显示真实结果。

## 验证

- 完整JVM回归120项通过，新增5项：40条整批返回逐个显示、追加批次、部分失败、取消后续接、系统关闭动画。
- 独立包名 `com.lvjian.progresscheck`，标准Application，复用正式 `OrganizeCardContent`；不读取正式应用数据库，不调用AI。`tools/progress-probe`仅通过 `-PprogressProbe=true :app:assembleDebug` 编入调试包。
- vivo S50，系统动画缩放1.0，浅色/深色分别模拟40条同时返回并立即结束后台处理。录屏显示0/40、多个中间值及最终40/40，动画结束后按钮恢复且最终进度保留。录屏为6fps抽帧查看，逐个整数的完整序列由JVM测试验证。
- 原始录屏和抽帧在本地 `.snapshots/progress-check/`，不作为真实AI的整理速度或准确率证据。
- 正式构建不使用progressProbe属性，包名仍为 `com.lvjian.app`，versionCode58；继承0.10.10的按群合并与v7→v8迁移。
- 正式release构建及lintVitalRelease通过，最终120项测试通过，签名校验通过；vivo S50覆盖安装成功，读取版本为0.10.11/code58，已正常进入MainActivity。独立测试包及手机上的临时测试录屏已移除，未触发正式消息整理。
