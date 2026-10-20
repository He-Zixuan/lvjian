# 滤见（LvJian）

把各应用的通知整理成清单，并通过「蛋黄」助手管理计划的 Android 应用。

**当前源码版本：0.10.11 · Android 8.0（API 26）及以上。**

[下载最新版 APK](https://github.com/He-Zixuan/lvjian/releases/latest) · [更新记录](CHANGELOG.md) · [使用许可](LICENSE)

## 功能

- 通知清单：按天分组，完成、忽略、恢复及复查已过滤消息。
- 收集范围：默认开启微信、QQ、企业微信；其他应用按需启用，支持批量开启或取消。
- 过滤规则：关键词、按人及 AI 语义过滤，内置营销词库；内置规则「滤掉无用信息」写明了什么留什么去。
- 消息摘要：AI 整理时为每条保留的消息生成一句话摘要，清单直接显示，不用展开原文。
- 按群整理：一键整理按应用、会话理解上下文，同一事项的转发、补充和催办合并到原项，展开可看各条原文。
- 提醒接管：按应用配置，再按会话选择提醒、静默或隐藏。
- 群聊导入：在本机通过截图 OCR 建立会话名单。
- 计划与对话：周时间表、提醒、对话排期、按名称或日期删除和改期，删除可撤销。
- AI 接口：自行配置 OpenAI 兼容的 Base URL、API Key 和模型。

## 安装与使用

1. 从 [Releases](https://github.com/He-Zixuan/lvjian/releases/latest) 下载最新版 APK，允许安装此来源的应用并安装。
2. 在「收集」页开启系统「通知访问权」。
3. 按需调整收集范围、过滤规则和计划提醒；使用 AI 时在设置中填入自己的接口配置。
4. 需要接管提醒时，按应用内向导将来源应用的通知渠道设为静默，并允许滤见发送通知。

同签名覆盖安装可保留已有数据。自行构建的 APK 使用自己的签名，无法直接覆盖官方签名包。

## 数据与能力边界

通知消息保存在手机本地；截图 OCR 在本机完成。通知监听只能接收来源应用实际发出的通知，无法回溯全部聊天历史；长文本可能被系统截断，图片、语音等通常只有占位文本。群聊截图导入只创建名单。

启用 AI 语义过滤后，规则作用范围内的消息会发送给你配置的服务提供商；使用 AI 对话时，会发送对话内容、所选图片以及为排期提供的计划快照。请按自身需要选择接口和功能。

## 从源码构建

环境：JDK 17、Android SDK Platform 35、Android SDK Build Tools、可访问 Maven 和 Gradle 分发站点的网络。工程附带 Gradle 8.9 Wrapper。

1. 克隆仓库，用 Android Studio 打开工程，安装所需 SDK。
2. 由 Android Studio 生成 `local.properties`，或自行设置 `sdk.dir` 指向本机 Android SDK。
3. 构建调试包：

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
```

```bash
# macOS / Linux
./gradlew :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

构建 release 时，可将 `signing.properties.example` 复制为 `signing.properties`，配置自己的签名密钥库和密码，再运行 `:app:assembleRelease`。未配置签名时生成未签名 release，安装前需要自行签名。签名配置与密钥库均已排除出版本控制。

运行已有测试：`./gradlew :app:testDebugUnitTest`（Windows 使用 `gradlew.bat`）。如果 Windows 环境出现中文路径编码问题，可将工程克隆到仅含英文字符的目录后构建。

使用本项目便携构建环境时，也可先编译 `:app:compileDebugUnitTestKotlin`，再运行 `./tools/run-unit-tests.ps1`，绕过 Gradle worker 的中文路径参数文件问题。

规则表单真机回归：手机解锁、授权 ADB 并安装待测版本后，运行 `./tools/verify-rule-save.ps1 -OpenRules -KeepKeyboardOpen`。脚本在键盘展开时点击“保存规则”，验证规则出现在列表，再删除自己创建的临时规则；省略 `-KeepKeyboardOpen` 可验证键盘收起场景。可用 `-AdbPath` 指定 ADB 路径。

AI 产品说明来源位于 `docs/product-manual.md`；修改后运行 `node tools/sync-product-manual.cjs` 同步到 Kotlin。若本地还保留开发用网页预览，脚本也会同步对应副本。

## 许可

本项目原创部分采用[个人非商业使用许可证](LICENSE)：允许自然人为个人非商业目的使用、学习、复制、修改和免费再分发，禁止未经书面授权的商业使用及组织业务使用。具体条件以许可证全文为准。

这是公开源码项目，许可证含非商业限制。第三方依赖及词库继续适用其原有许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
