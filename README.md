# AndbnA

把整套 QQ 官方机器人跑在手机里的 Android App：Python 运行时（Chaquopy）打包进 APK，由本机前台服务托管，不依赖模块、不上报任何数据。

当前版本：**1.1.0**（versionCode 1100）。

## 功能

**多机器人**
- 多个 bot 同时在线，各自独立的人格 / 称呼 / 记忆 / 群配置 / 定时问候；
- 内置人格：奥黛塔 / 哥伦比娅 / 桑多涅 / 沃雅妮莎 / 猫娘 / 鲸鱼娘，也可导入 `.md` / `.txt` 自己的；
- Bot 页开关**即时生效**：3 秒内上下线，不需要重启 App。

**多 bot 在同一个群**
- 「总模型」统一决定这条消息由**哪一个** bot 回，或都不回；同一条消息只调用一次；
- 跨 bot 协同按**消息正文哈希 + 30 秒时间桶**匹配（群号是每个 bot 独立的，无法直接对齐）；
- bot 之间能看见彼此的发言（`[桑多涅] …`），可以接某只的话，也能分辨真人；
- 每个 bot 连续发言上限 6 句，到上限由它自己收尾，之后对刚才那位对话方静默 10 分钟；静默只针对 bot，真人不受影响；
- 真人每小时软上限 40 条，超过只是让判定更保守，不拒答。

**回复判定**
- 三档：保守 / 标准 / 积极（默认标准），Bot 编辑页可单独覆盖；
- 「名字命中就当被叫」默认开启：正文出现人格名或称呼，跳过判定直接回；
- `@` 语义结构化：`@你` / `@作者本人` / `@用户xxxxxxxx`；正文里出现 `<@…>` 但平台没下发 mentions 时按「@ 了别人」处理（fail-closed）；
- 回复延迟：全局默认 3~7 秒随机，可 per-bot 覆盖，更像真人在打字；指令类（`#搜`、`#读`、回时间）不吃延迟。

**群聊闸门**
- 群级配置（群名、人数、每日额度、群里是否接话、人格覆盖）优先于 bot 覆盖，再优先于全局；
- 每日额度三态：跟随全局 / 不限 / 指定条数；
- 回声去重、作者三信号（真人 / 自己 / 其他 bot）、限流与熔断。

**记忆与存档**
- 记忆存本机 JSON，多选删除、10 秒撤销；
- 消息存档 + FTS5 全文检索（trigram，≥3 字最快；2 字回退 LIKE），可导出。

**模型与联网**
- OpenAI 兼容接口，主模型 / 识图 / 判定 / 摘要 / 搜索 / 上下文窗口都可以分开配；
- 识图：图片内存直传，不落盘；不支持图片的模型写日志并退回纯文本；
- 联网搜索：DeepSeek 官方 / SearXNG / 自定义 JSON 接口，可让模型自己决定查不查，结果按人格转述；
- 成本统计：今日次数、token 用量、按上游余额差值估算的金额（含同一 Key 其它端的影响）。

**定时问候**
- 每个 bot 独立时段，窗口内随机错开；可发主人私聊与已知群；
- 内置人格的问候文案只读，自定义人格可自己写。

**保活**
- 三种模式：普通 / ADB(Shizuku) / root，运行内核完全相同，差别只在保活权限；
- 统一的「自动拉回」看门狗（进程外脚本）：每 60 秒检查，被杀自动拉起，10 分钟内最多 3 次；
- 你点「停止全部」时不会被拉回，点「启动」才恢复；达上限后可在状态页卡片上直接恢复。

**更新**
- 默认开启自动检查；默认从静态 JSON（jsDelivr CDN）读版本信息，不消耗 GitHub API 配额，开代理也不会 403；静态源失败回退 GitHub API；
- 打开 App 先读缓存立即显示，联网间隔最少 30 分钟；每天 12:00 后服务补查一次；
- 下载失败会按 HTTP 码说明原因（403 限流 / 404 文件不存在 / 5xx / 连接中断），点「更新」立即出现下载通知；
- 自更新需要 root 或 Shizuku，安装前会做包名 / 版本更高 / ABI / 签名四项校验。

**日志**
- 固定 6 类：`消息` / `判定` / `回复` / `系统` / `更新` / `错误`，格式 `[时间] [bot 名字] 类别 · 一句话`；
- 日志页二级界面可切「简洁 / 详细」与按类别筛选；简洁下一条消息通常 2~3 行。

## 申请一个 QQ 机器人（拿 AppID / AppSecret）

本 App 托管的是 **QQ 官方机器人**，所以先在 QQ 开放平台注册一个：

| 用途 | 地址 |
|---|---|
| 申请 / 管理机器人（创建后拿 **AppID**、**AppSecret**） | https://q.qq.com/ |
| 官方开发者文档（接口、事件、沙箱与上线流程） | https://bot.q.qq.com/wiki/ |

1. 打开 https://q.qq.com/ ，用 QQ 登录，选择「机器人」→ 创建；
2. 创建后记下 **AppID** 与 **AppSecret**（AppSecret 只显示一次）；
3. 在「开发设置」里配置沙箱 / 正式环境的权限与事件订阅（本 App 走 WebSocket 网关，需要开启群聊与私聊事件）；
4. 把 AppID / AppSecret 填进本 App 的「Bot」→「添加」，保存后即可上线；
5. 先在沙箱里 @ 一下机器人确认权限与事件都通了，再申请发布。

> AppSecret 在本 App 里用 Android Keystore（AES-256/GCM）加密后落盘，不会明文写入；但它等于机器人的密码，**不要外传、不要提交进仓库**。
> 同一个 AppID **不要在两台设备上同时跑**：QQ 的 access token 有约 2 小时缓存，改 secret 也不会立刻踢掉旧实例，会出现「自己没回但群里有人以它名义回话」。

## 运行模式：普通 / ADB(Shizuku) / root

三种模式下 bot 的运行内核完全相同（Python 跑在 App 进程里），差别只在「保活权限」。

| | 普通模式 | ADB 模式（Shizuku） | root 模式 |
|---|---|---|---|
| 需要什么 | 什么都不用装 | 安装 [Shizuku](https://shizuku.rikka.app/) 并授权 | KernelSU / Magisk 等 root |
| 加电池白名单 | ❌ | ✅ 通过 shell | ✅ 通过 su |
| 被杀后自动拉回 | ❌ | ✅ | ✅ |
| 稳定性 | 一般 | 好 | 最好 |

### ADB 模式（Shizuku）

1. 建议 **Shizuku 13.7+**（更旧版本在 Android 17 上服务进程会被打崩，表现为「授权了但连不上」）；
2. 启动 Shizuku 服务：打开 Shizuku App 按提示启动，或用电脑 `adb shell <Shizuku APK 目录>/lib/arm64/libshizuku.so`；
3. 回 App → 状态页 → 点 hero 进入「运行控制」→ 选 **ADB 模式** → 弹出授权框选「始终允许」；
4. 成功后显示「ADB 托管」「shell 服务 已连上」，并自动加入电池白名单。

> 先启动 Shizuku，再打开 AndbnA。顺序反了 App 拿不到 binder，需要重启一次 App。

### root 模式

设备已 root，首次切换弹 su 授权选允许；App 通过 `su` 执行 `dumpsys deviceidle whitelist +<包名>` 加白名单，并用 `am start` 把自己拉回来。

### 自动拉回（看门狗）

- ADB / root 模式下默认开启（设置页可关）；进程外 sh 脚本每 60 秒检查一次；
- 被杀就用对应通道拉起，**10 分钟内最多 3 次**，超限暂停，等你在状态页卡片上手动恢复；
- 状态页显示：`已启用 · 上次 HH:MM:SS` / `未启用` / `已暂停（达上限）` / `已暂停（手动关闭）`。

### 不想装 Shizuku 也想保活

```bash
adb shell dumpsys deviceidle whitelist +com.wjljh.andbna.qbot
```

加入电池白名单后被杀概率明显降低（但不会自动回来，那需要 ADB/root 模式）。

### 常见问题

**Q：切到 ADB 模式提示「Shizuku 没在运行」？** 服务没起或版本太旧，升级到 13.7+ 后重试。

**Q：Shizuku 里看不到 AndbnA？** 先在 App 里点一次「申请 Shizuku 授权」触发请求。

**Q：通知栏里有几十个 `AndbnA 本机 Bot` 渠道？** 那是早期版本的遗留渠道（系统标记为已删除的“墓碑”，App 侧无法清除）。1.0.8 起只会新建 `andbna_local_bot` 与 `andbna_update_download` 两条。

## 构建

需要 JDK 21、Android SDK 37、Python 3.13（Chaquopy 用）。

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew :app:assembleRelease --no-daemon --max-workers=1
```

签名口令不写入仓库，请在 `~/.gradle/gradle.properties` 配置：

```properties
andbnaStorePassword=***
andbnaKeyPassword=***
```

并把签名库放到 `signing/wjljh-andbna_sign.jks`（alias `andbna`）。

运行测试（纯 Python，不需要设备）：

```bash
cd andbna-app && for f in tests/test_*.py; do python3 "$f"; done
```

## 目录

- `andbna-app/` Android 应用（Kotlin + Compose/MiuiX + Chaquopy）
- `andbna-app/app/src/main/python/bot_runner.py` 机器人运行时（qq-botpy）
- `andbna-app/app/src/main/assets/personas/` 内置人格文本
- `andbna-app/tests/` 运行时单测（判定 / 闸门 / 存档 / 成本 / 群配置 / 日志格式 / 跨 bot 协同 …）
- `docs/latest.json` 更新元数据（由 `.github/workflows/update-latest-json.yml` 在发布 Release 时自动更新）
- `delivery/` 交付说明（构建、更新日志、验收清单、已知边界）
- `docs/` 人格来源与许可

## 第三方与许可

本项目以 MIT 发布。内置人格文本来自以下 MIT 项目，保留其署名：

- [DGP-Studio/Genshin.Skill](https://github.com/DGP-Studio/Genshin.Skill) — 奥黛塔 / 哥伦比娅 / 桑多涅 / 沃雅妮莎
- [linsport/dsh-whale-persona](https://github.com/linsport/dsh-whale-persona) — 鲸鱼娘（本仓库为裁剪版，见 `docs/鲸鱼娘-来源与许可.md`）

UI 结构与数值参考了 SukiSU-Ultra 与 KernelSU-Style-UI-Kit，但**未复制其代码**；引导页动效参考 JetBrains/compose-multiplatform 的示例。
