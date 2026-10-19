# 内置营销拦截词库 · 加载约定与来源声明

## 加载约定（给实现代码看）

- 每行一个词；`#` 开头为注释，加载时跳过；空行跳过；匹配时忽略大小写。
- 文件按数字前缀分三层：
  - `00_whitelist.txt`：**保护词白名单**。标题+正文命中任一词即放行，不再进入拦截判断（最高优先级）。
  - `10_* ~ 15_*`：校园群广告六类（兼职/校园卡/商业推广/联系方式/利益诱导/伪装官方）。
  - `20_general_ads.txt`：通用广告垃圾词。
  - `30_push_marketing.txt`：自拟 App 推送营销词。
- 命中 10~30 任一词 → 判定为营销通知（随后按用户设置决定「进已过滤」或「直接消失」）。

## 词库来源与许可证（打包进 APK 必须保留本文件）

| 文件 | 来源仓库 | 许可证 |
| --- | --- | --- |
| 10~15_campus_*.txt | https://github.com/excniesNIED/campus-ad-detection-words | MIT |
| 20_general_ads.txt | https://github.com/fwwdn/sensitive-stop-words（取其中「广告.txt」） | Apache-2.0 |
| 00_whitelist.txt、30_push_marketing.txt | 滤见自拟 | — |

## 参考实现（未复制代码，仅借鉴思路）

- https://github.com/tomxposed/buzz-buster（MIT）：三层过滤与默认规则 seed 结构。
- https://github.com/lm060719/io.mo.mnblocker（MIT）：白名单优先（allow-first）策略。
- https://github.com/chantleyw/NotifSilencer（GPLv3，仅思路参考）：保护词先行的放行设计。

## 词量统计（2026-10-15 快照）

- 白名单 37 词；校园六类约 1115 词；通用广告约 123 词；推送营销 29 词；合计约 1300 词。
- 上游更新方式：重新下载对应仓库文件、保留首行注释头即可。
