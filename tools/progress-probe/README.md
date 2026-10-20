# 独立进度验证页

使用本项目的Gradle环境执行 `:app:assembleDebug -PprogressProbe=true`。生成的是独立包 `com.lvjian.progresscheck`，仅复用正式整理卡片，不启动正式Application、不读取正式数据库、不调用AI。

安装该调试包后执行：

```powershell
adb shell am start -f 0x18000000 -n com.lvjian.progresscheck/com.lvjian.app.ui.ProgressProbeActivity --ez dark false
```

`dark true` 验证深色。页面1.2秒后模拟40条同帧返回并立即结束后台，n/N应从0逐个显示到40，再恢复按钮；最终40/40保留。再次点按钮重演。

正式构建省略 `progressProbe` 属性；验证后卸载 `com.lvjian.progresscheck`。
