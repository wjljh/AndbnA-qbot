# AndbnA 上一版 APK 逆向：功能与页面清单

| 项 | 值 |
|---|---|
| 目标 APK | `/home/wjljh/.dsh/attachments/v1/files/d9/d9401a728330d39dca1e78830de4a0c6cf33ea191ceaa389de6dad3f586ea3b8/AndbnA_1.1.0.apk` |
| 大小 / 包名 | 42,209,261 B / `com.wjljh.andbna.qbot` |
| 版本 | `versionCode=110`、`versionName=1.1.0`（`_prev-apk/jadx-out/resources/AndroidManifest.xml:4-5`） |
| 运行时 | Chaquopy Python 3.13（`assets/chaquopy/build.json` → `"python_version": "3.13"`） |
| 反编译产物 | `_prev-apk/jadx-out/`（库 + 资源）、**`_prev-apk/jadx-app/`**（App 自有类，本任务补跑）、**`_prev-apk/jadx-app-badcode/`**（`--show-bad-code`，用于 jadx 常规模式放弃的方法） |

### 证据来源与复现命令

上一轮中断导致 `jadx-out/sources` 只落了库代码：App 自有类仅 4 个
（`com/wjljh/andbna/local/IUserService.java`、`local/LocalBot.java`、`qbot/R.java`、`R.java`）。
App 代码实际分布在 **classes2/3/4/5.dex**（`grep -ac 'com/wjljh/andbna'` 分别 2/40/18/5 命中，
classes.dex、6、7、8 为 0）。补跑命令（单进程，`-Xmx1g`，未开并发、未构建 Gradle）：

```bash
export JAVA_HOME=/home/wjljh/toolchain/jdk21; export JAVA_OPTS="-Xmx1g"
cd _prev-apk/apk
# 常规模式产出（结构性证据）
/home/wjljh/toolchain/jadx/bin/jadx -d ../jadx-app -j 1 classes2.dex classes3.dex classes4.dex classes5.dex
# AppKt.PanelApp / Tab 列表等被 jadx 判定"未反编译"，用 bad-code 模式补（属性证据）
/home/wjljh/toolchain/jadx/bin/jadx --show-bad-code -r -d ../jadx-app-badcode -j 1 \
    classes2.dex classes3.dex classes4.dex classes5.dex
```

下文凡形如 `Xxx.java:NN` 均指 `_prev-apk/jadx-app-badcode/sources/com/wjljh/andbna/` 下文件
（该目录是 `jadx-app` 的严格超集，行号以 bad-code 版为准）。

---

## 1. 页面清单

### 1.1 一级页面：4 个（底部 NavigationBar，4 个 tab）

证据：`AppKt.java:311-314`（`PanelApp` 里构造的 Tab 数组，jadx 常规模式对该方法只留
`UnsupportedOperationException`，需 bad-code 版才可见）

| # | Tab 文案 | 图标（Miuix） | 承载 Composable |
|---|---|---|---|
| 0 | 状态 | `InfoKt.getInfo` | `StatusPage` |
| 1 | 内容 | `EditKt.getEdit` | `TextsPage` |
| 2 | Bot | `CommunityKt.getCommunity` | 本机模式 `BotsPage` / 面板模式 `MemoryPage` |
| 3 | 设置 | `SettingsKt.getSettings` | `SettingsPage` |

**关键结构：tab2 内容由模式决定，二者互斥。** `AppKt.java:869-880`：
`if (localMode) BotsPage(...) else MemoryPage(...)`。
也就是说「Bot 页」只在非模块模式出现；模块模式下同一个 tab 变成「记忆/称呼表」页。

`localMode` 的判定：`AppKt.java:377` —— `localMode = (mode != RunMode.MODULE)`。
即 **模块模式以外的三种模式（普通/ADB/root）共用同一套本机 UI**。

### 1.2 独立全屏引导：2 个

| 屏 | 触发条件 | 证据 |
|---|---|---|
| `SetupGate` 连接引导 | `Panel.configured == false`（token 为空） | `AppKt.java:412`；`SetupGate` 定义在 `App.kt:203`（`AppKt.java:970` 的 traceEvent 带源码行号） |
| `ModePickScreen` 运行模式选择 | `showModePicker==true`；首装与「设置 → 运行模式」都走它 | `AppKt.java:358`；`ModePickScreenKt.ModePickScreen(metrics, current, allowBack, onPick, onBack)` |

### 1.3 页内二级：3 个

| 二级 | 宿主页 | 证据 |
|---|---|---|
| `PersonaPicker` 人格选择器 | BotsPage | `BotsPageKt.PersonaPicker`；文案「给 X 选人格」`BotsPageKt.java:493` |
| 文本编辑态（文件列表 → 编辑器） | TextsPage | 「编辑文件」`TextsPageKt.java:462`、「保存」`:665`、「重新读取」`:683` |
| 确认弹层（删除/清空/重启） | 全部页 | `ComponentsKt.ConfirmButton`；「确认删？」`MemoryPageKt.java:622`、「真的清空？」`:704`、「确认重启？」`StatusPageKt.java:521` |

### 1.4 每页从上到下的区块

分两层写：面板模式（MODULE）与本机模式（NORMAL/ADB/ROOT）。
本机模式的分流证据：`StatusPageKt.java:148-151`（`if (localMode3) LocalStatusContent(...)`）、
`TextsPageKt.java:126-132`（`LocalTextsContent(...)`）。

**状态页 · 面板模式**（`StatusPageKt.StatusPage` + `HeroCard` + `InfoRow`）

1. HeroCard：状态标题（读取中 / 工作中 / 已停止 / 网络异常 / 未连接，`StatusPageKt.java:240`）
   + 副标题（bot 在线，网络通畅 / bot 进程没在跑 / QQ 接口连不上，bot 会反复重连 / 连不上面板服务，同 `:240`）
   + 模式标签（`HeroCard_FNF3uiM(..., modeLabel, ...)`）+ 运行时长（`fmtDur`，「已跑 · 天/小时/分」，`:416`）
2. 读数行 `InfoRow` ×6，顺序固定（`StatusPageKt.java:404-438`）：
   面板版本 → 面板地址 → 运行模式 → bot 进程 → 看门狗 → 内存 / CPU（最后一行 `last=true`）
3. 快捷操作（`:355`）：重启 bot（`:521`，`Panel.restart("bot")`）、重启看门狗（`:530`，`restart("watchdog")`）、
   试跑模型（`:579`，`Panel.testLlm()`）、读日志（`:586`，`Panel.log(150)`）
4. 日志（最近 150 行）（`:368`）

**状态页 · 本机模式**（`LocalModeScreenKt.LocalStatusContent`）

1. Hero：已上线 / 连接中 + 「已跑 N」（`LocalModeScreenKt.java:262`）、运行中 / 未启动（`:588`）
2. 控制（`:462`）：启动（`:730`）、停止（`:750`）、自检（`:771`，自检中… `:782`）
3. 信息（`:469`）：启用的 bot 数（`:850`）、root 托管卡（`RootCard`）、ADB 托管卡（`AdbCard`）、
   电池白名单状态（`:1097`）、通知权限告警（`:431`）
4. 日志（每 2 秒刷新）（`:494`）；空态「还没有日志。点「启动」后这里会实时滚出来。」（`:875`）

**内容页 · 面板模式**（`TextsPageKt.TextsPage`）

1. 当前人设（`:449`）+「当前人设来自 X（N 字）」（`:452-453`）
2. 角色预设（`:455`）：完整 / 精华 / 精简 三级；说明「完整版还原度最高但吃 token；精简版最省。切换会把当前人设备份到 persona_backup/。」（`:506`）
3. 编辑文件（`:462`）：文件列表 → 编辑器（保存 `:665` / 重新读取 `:683`）

**内容页 · 本机模式**（`LocalTextsContentKt.LocalTextsContent`）

1. 内置人格（`:236`）：当前：X / 还没选（默认不带人格）；说明「选完要重启 bot 才生效。」（`:292`）
2. 记忆（`:437`）：按 bot 分开，「当前 X · 按 bot 分开存」；删单条（删 / 确认删？`:652`）、清空该 bot（`:716`）、刷新（`:731`）；
   路径提示「存于 localbot/<AppID>/memory.json」（`:770`）

**Bot 页 · 本机模式**（`BotsPageKt.BotsPage`）

1. 顶部动作条：启动全部（`:757`）、停止全部（`:763`）、收起（`:764`）、添加（`:764`）
2. bot 卡片 `BotCard`：状态（已上线 / 连接中 `:1361`；已启用，待启动 / 已停用 `:1467`）
   + 状态行 / 人格行 / 凭据行（`:1469-1491`）+ 模型行（`:547-553`）
   + 动作：选人格（`:1551`）、改密钥（`:1567`）、卸载（`:1583`）
3. 添加 / 修改表单（`:531`）：AppID（纯数字）（`:1703`）、AppSecret、角色预设名（可空）（`:1741`）、
   继承当前 bot 的模型配置（`:1832`）、创建（`:1766`）
4. 模型配置（`:1088`）：接口地址（`:1171`）、模型名（`:1191`）、保存 → 「模型配置已保存，重启服务生效」（`:1311`）

**Bot 页 · 面板模式 = 记忆页**（`MemoryPageKt.MemoryPage`）

1. 会话（`:449`）：每条显示 N 条 · 时间（`:588`）、删 / 确认删？（`:622`）
2. 称呼表（`:456`，「记忆里她怎么叫这些人」）：改名（`:885`）/ 新称呼（`:945`）/ 保存（`:952`）/ 取消（`:864`）、
   空态（`:995`）、「记忆里出现过、但还没进称呼表的」（`:1009`）
3. 批量清理：删 30 天前（`:685`）、清空全部（`:704`）

**设置页**（`SettingsPageKt.SettingsPage`）

| 分组 | 内容 | 证据 |
|---|---|---|
| 回复策略 | 「改了立刻热生效，不用重启」；判据（模型判断 / 随机）、判定用的模型、闲聊概率 0~1、每天最多回复条数、问候时段 | `:870-877`、`:939-1014`、`:1155-1171` |
| 凭据 | 只显示掩码；留空表示不改；模型 Key / 新 AppSecret / 新模型 Key / 模型地址 / 模型名 → 保存凭据 | `:877`、`:1280-1388` |
| bot 管理 | 列表：当前 / 备用 · 会话 N · 记忆 N · ⚠ 凭据 AppID 与目录不符；切换（会重启）/ 删除 / 新建 bot | `:884`、`:1602-1684` |
| 高级 | 运行模式（当前 label · summary `:1946-1947`）、面板地址（`:1985`）、重新设置连接（`:2012`） | `:897` |
| 关于 | — | `:904` |

> 分组**先后顺序**是按同一 lambda 内相邻行号（870/877/884/897/904）读出的；
> 「运行模式 / 面板地址」归属「高级」为**推断**（行号 1946/1985 落在不同 lambda，Compose 输出已按重组作用域拆散）。

---

## 2. 功能清单逐项拆解

### 2.1 多 bot 管理（新建 / 切换 / 删除）

两套实现，看模式。

**(A) 本机模式（Chaquopy）—— 本地 JSON 文件，不经过面板**

- 数据：`filesDir/localbots.json`（`BotStore.file()` → `new File(ctx.getFilesDir(), "localbots.json")`）
- 字段（4 个，`LocalBot`）：`appid`、`secret`、`persona`、`enabled`。
  序列化见 `LocalBot.toJson()`；反序列化 `LocalBot.Companion.from()` 用 `optString/optBoolean`（缺字段容错）
- 操作：`load` / `save` / `upsert`（按 appid 命中则替换）/ `remove` / `setEnabled` / `setPersona` / `enabledJson`（只挑 enabled → 给 Python）
- 当前选中 bot：SharedPreferences `andbna_local/selected_bot`（`BotStore.selectedAppid/selectAppid`）
- 迁移兼容：`load()` 若 `localbots.json` 为空，会读旧 key `andbna_local/local_appid`+`local_secret` 造一条再落盘（`BotStore.java` `load` 内 `if (out.isEmpty())` 分支）
- 多 bot 同时在线：`startAll()` 把所有 enabled 的交给 Python，`start_all(enabledJson, dataDir)`
  （`BotHost.java:92`）；「多个 bot 可以同时在线」（`LocalModeScreenKt.java:851`）

**(B) 面板模式 —— 全部经模块的 HTTP 面板**

- `GET /api/bots`、`POST /api/bots/use`、`POST /api/bots/new`（`appid/secret/role/inherit_llm`）、
  `POST /api/bots/delete`（带 `confirm:"DELETE"`）—— `Panel.java` `bots/useBot/newBot/deleteBot`
- 语义差异：「**一个 AppID 一个身份，同时只跑一个**」（`SettingsPageKt.java:884`），
  即面板模式下多 bot 是「切换」，与本机模式的「同时在线」相反。这一点很重要，见 §6 冲突 C2。

### 2.2 13 档人格（完整 / 精华 / 精简）

- **资源位置**：APK 内 `assets/personas/*.txt`，13 个文件，共 144K（`du -sh assets/personas`）
  `wc -l` 实测：奥黛塔 / 哥伦比娅 / 桑多涅 / 沃雅妮莎 各 3 档（`-完整`、`-精华`、无后缀=精简）+ 猫娘 1 档 = 13
- **列举**：`PersonaAssets.list()` → `ctx.getAssets().list("personas")`，去 `.txt` 后缀、排序
- **分档规则**（`PersonaAssets.group()`）：后缀 `-完整` → 「完整」，`-精华` → 「精华」，其余 → 「精简」；
  空桶不返回。**注意：分档靠文件名后缀，不是靠元数据**——重命名文件即改档位
- **怎么选**：
  - 本机模式：`LocalTextsContent`「内置人格」直接列 13 档（含「还没选（默认不带人格）」），
    选中后写盘（`PersonaAssets.select()`）：`filesDir/localbot/persona.txt` + `persona_name.txt`，
    并把 key `andbna_local/local_persona` 记住；提示「选完要重启 bot 才生效」
  - bot 级人格：`BotStore.setPersona` + `PersonaAssets.writeTo(ctx, name, botDir)` → 落到
    `filesDir/localbot/<appid>/persona.txt`、`persona_name.txt`（`BotHost.startAll()` 启动前统一刷一遍）
  - 面板模式：`GET /api/personas`、`POST /api/personas/use`（`Panel.personas/usePersona`）
- **怎么应用**：本机模式是「文件即人格」——Python 侧读 <botdir>/persona.txt；
  模块模式由面板把人格写进模块目录并重启 bot，切换时会备份到 `persona_backup/`（`TextsPageKt.java:506`）

### 2.3 称呼表 / 记忆清理

**面板模式（HTTP，唯一实现）**

| 功能 | 调用 | 证据 |
|---|---|---|
| 记忆会话列表 | `GET /api/memory` | `Panel.memory` |
| 删单条 | `POST /api/memory/delete` `{key}` | `Panel.deleteChat` |
| 删 N 天前 | `POST /api/memory/old` `{days}` | `Panel.deleteOld`；UI 固定 30 天（`MemoryPageKt.java:685`） |
| 清空全部 | `POST /api/memory/purge` `{confirm:"CLEAR"}` | `Panel.purgeMemory` |
| 称呼表读 | `GET /api/names` | `Panel.names` |
| 改名 | `POST /api/names/rename` `{who,name}` | `Panel.rename` |
| 移出称呼表 | `POST /api/names/unname` `{uid}` | `Panel.unname` |

**本机模式（Python 直接读写 JSON）**

- `BotHost.memoryList/memoryDelete/memoryClear` → `callAttr("memory_list"/"memory_delete"/"memory_clear", botDir)`
- 存储：`filesDir/localbot/<appid>/memory.json`
- 记录格式与模块侧一致：`[[role, content, ts], ...]`（bot_runner.pyc 文档串：
  「和模块那边的记录格式保持一致：[[role ...」）——为的是以后能把模块历史搬过来不改结构
- 本机模式的称呼表：**未实现**。`LocalTextsContent` 只有「记忆 / 删 / 清空该 bot / 刷新」，
  没有 rename/unname 入口（`grep -rn 'names' LocalTextsContentKt.java` 无 UI 调用）

### 2.4 试跑模型（LLM 测试）与日志查看

| 能力 | 面板模式 | 本机模式 |
|---|---|---|
| 模型试跑 | `GET /api/llm/test`（`Panel.testLlm`），按钮「试跑模型」`StatusPageKt.java:579` | **无同名按钮**；等价物是「自检」`LocalModeScreenKt.java:771` → `BotHost.probe()` → Python `probe()` |
| 日志 | `GET /api/log?lines=150`（`Panel.log`），按钮「读日志」`StatusPageKt.java:586` | `BotHost.logTail(150)` → Python `log_text(n)`；每 2 秒轮询（`LocalModeScreenKt.java:494`） |
| 重启 | `POST /api/restart` `{target: "bot" / "watchdog"}` | 「启动/停止」= `BotService.Companion.start/stop` |

本机模式没有「试跑模型」的独立入口，是因为模型配置写在 `localbot/llm.json`（`BotStore.writeLlmJson`），
由 Python 在收到消息时现场调用；「连通测试」退化为：LLM 未配置时直接回「（本机模式连通测试）收到：…」。

### 2.5 运行模式切换（普通 / ADB(Shizuku) / root / 模块）★ 本节最重要

#### 2.5.0 四模式的声明值

`RunMode`（`local/RunMode.java:13-17`），key 落盘在 SP `andbna_mode/mode`：

| key | label | summary（APK 原文） |
|---|---|---|
| `normal` | 普通模式 | bot 跑在 App 里，前台服务保活 |
| `adb` | ADB 模式 | 借 Shizuku 起独立进程 |
| `root` | root 模式 | 开机自启 + 电池白名单 |
| `module` | 模块模式 | 连接已装的 KernelSU 模块面板 |

首装默认值：`ModeStore.initial()` —— 已配置面板 token（`Panel.configured`）则 `MODULE`，否则 `NORMAL`。

#### 2.5.1 关键结论：三种本机模式共用同一个执行宿主

**bot 进程只有一条启动路径**：`BotService`（前台服务）+ Chaquopy Python。
ADB / root 模式**不改变 bot 的宿主**，只增加「保活与自启」能力。证据：

- `BotService` 是唯一的 `startForegroundService`/`stopService` 调用点
  （`local/BotService.java:102/107`，`Companion.start/stop`），调用方只有 `LocalModeScreen`（`:816/:826`）与 `BotsPageKt.java:888`
- Shizuku 侧 `IUserService` **只暴露一个方法** `String exec(String)`（`local/IUserService.java:13`），
  实现是 `Runtime.getRuntime().exec(new String[]{"sh","-c",cmd})`（`UserService$stub$1.java:14`），8 秒超时。
  即：Shizuku 在本 App 里是**命令通道**，不是 bot 宿主
- `RootHost` 的全部能力都是 `su -c` 的一次性命令（`RootHost.sh(cmd, timeoutMs=8000)`），没有常驻进程

> 因此 `RunMode.ADB` 的 summary「借 Shizuku 起独立进程」**与实现不符**（实现里没有独立 bot 进程）。
> 这是一个应当在重写时修正的说法，不是应当复刻的行为。

#### 2.5.2 各模式写了哪些文件 / 起了什么 / 权限怎么拿 / 失败怎么降级

**(1) 普通模式 NORMAL**

| 项 | 内容 |
|---|---|
| 写文件 | `filesDir/localbots.json`、`filesDir/localbot/llm.json`、`filesDir/localbot/<appid>/{persona.txt,persona_name.txt,memory.json,local_bot.log,botpy.log}` |
| 起服务 | `BotService`（`android:foregroundServiceType="dataSync"`，`exported=false`，Manifest）；通知渠道 `andbna_local_bot`、`NOTIF_ID=4711`，常驻通知「AndbnA / 运行中」（`BotService.java` `onStartCommand`） |
| 权限 | 仅 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` + `RECEIVE_BOOT_COMPLETED`（Manifest） |
| 失败降级 | 无 root 也能跑，这是基线；缺通知权限时仅告警「bot 仍在跑，但通知栏看不到，也更容易被系统清掉」（`LocalModeScreenKt.java:431`） |
| 已知弱点 | APK 自述：省电策略可能清掉进程，掉线期间平台不补发消息（`RunMode.java:14` 的 detail） |

**(2) ADB 模式（Shizuku）**

| 项 | 内容 |
|---|---|
| 写文件 | 不写文件（无落地脚本）；只通过 shell 改系统状态：`dumpsys deviceidle whitelist +<pkg>` |
| 起服务 | Shizuku **UserService**（`Shizuku.bindUserService`）：`new Shizuku.UserServiceArgs(ComponentName(pkg, UserService.class)).daemon(false).processNameSuffix("shell").debuggable(true).version(1)`（`ShizukuShell.java:45`）→ 一个以 shell 身份运行的独立进程，提供 `exec` |
| 权限怎么拿 | ①装 Shizuku 并用无线调试授权它；②App 申请 `Shizuku.requestPermission(code)`（`AdbHost.request`）；③前置声明 `moe.shizuku.manager.permission.API_V23` + `ShizukuProvider`（`authorities=``<pkg>.shizuku``，`INTERACT_ACROSS_USERS_FULL`） |
| 能力 | `AdbHost.available()`=`Shizuku.pingBinder()`、`granted()`=`checkSelfPermission()==0`、`version()`；`ShizukuShell` 提供 `exec / whitelist / unwhitelist / isWhitelisted / relaunch` |
| 失败降级 | 全部 API 都 try/catch 成「不可用」：`available()` 返回 false、`version()` 返回 0、`granted()` 返回 false（`AdbHost.java:16-38`）；`exec` 在未绑定 binder 时返回字符串「UserService 没连上」（`ShizukuShell.java:73-78`）；UI 文案分级：Shizuku 没在运行 / 在运行但未授权 / 已授权（`AdbHost.summary()`、`LocalModeScreenKt.java:1339/1486/1488`） |
| 重启后 | bot 不会自启（无开机脚本）；需手动「连接 shell」再「启动」（`LocalModeScreenKt.java:1564`） |

**(3) root 模式**

| 项 | 内容 |
|---|---|
| 写文件 | `/data/adb/service.d/andbna_qbot.sh`（常量 `RootHost.BOOT_SCRIPT`，`RootHost.java:25`）。写入方式：脚本内容 base64 内联，一次 `su -c` 完成（见下行） |
| 写入命令 | `mkdir -p /data/adb/service.d && echo '<b64>' | base64 -d > …/andbna_qbot.sh && chmod 755 …`（`RootHost.java:225`） |
| 脚本内容 | `#!/system/bin/sh` + 注释「只有 root 能启动 non-exported 的前台服务；普通 shell 会被拦。」+ `sleep 25` + `am start-foreground-service -n <pkg>/com.wjljh.andbna.local.BotService -a com.wjljh.andbna.START_LOCAL --user 0`（`<pkg>` 取 `context.getPackageName()`，故实际是 `com.wjljh.andbna.qbot`） |
| 起服务 | 不新起常驻进程；开机时由 init 执行脚本 → 拉起同一个 `BotService` |
| 权限怎么拿 | `Runtime.exec(new String[]{"su","-c",cmd})`（`RootHost.java` `sh()`），8 秒超时；`available()` 判据是 `id` 输出含 `uid=0` |
| 其它写入 | 电池白名单 `dumpsys deviceidle whitelist +<pkg>`（`RootHost.whitelist`）；`setup()` = 写脚本 + 加白名单，逐步返回「开机脚本: 已写入/失败 …」「电池白名单: 已加入/失败 …」 |
| 失败降级 | `sh()` 三态：超时 → `R(false,"超时")`；`exitValue!=0` → `ok=false`；异常 → `R(false, e.message)`。`installBoot` 失败不影响继续用普通模式；`removeBoot()` 可回滚（`rm -f`）；`teardown()` 只删脚本，**不移出电池白名单**（`RootHost.teardown` 只调 `removeBoot`） |

**(4) 模块模式 MODULE**

| 项 | 内容 |
|---|---|
| 写文件 | 不写任何文件（除 SP `andbna_panel/{host,token}`） |
| 起服务 | 不启动任何本机服务；纯 HTTP 客户端。bot 由模块的 root 服务托管（服务 + tmux + 看门狗，见 `RunMode.java:17` 的 detail） |
| 权限 | 只需 `INTERNET` |
| 地址 | 默认 `127.0.0.1:8788`（`Panel.DEFAULT_HOST`）；`baseUrl()` 规范化：补 `http://`、缺端口补 `:8788`、去尾 `/` |
| 失败降级 | `Res.ok` 要求 2xx 且 `json.ok != false`；`Res.err()` 依次回退 `error → hint → raw 前 160 字`；网络异常在 UI 上显示为「网络异常 / 连不上面板服务」（`StatusPageKt.java:240`）；`SetupGate`「重新设置连接」可清空 token 回到连接页（`SettingsPageKt.java:2012/2025`） |

#### 2.5.3 切换模式时做了什么

`AppKt.PanelApp$lambda$13$0`（`AppKt.java:634-638`）**只做三件事**：

1. `ModeStore.set(ctx, picked)` → 写 SP `andbna_mode/mode`
2. 更新内存态 `mode`
3. 关掉模式选择页

**不停服务、不杀 Python、不与模块做任何握手。** 全仓库 `stopService` 只有
`BotService.Companion.stop` 一处（`BotService.java:107`），调用方是「停止」按钮和 BotsPage 的停止全部。
→ 从本机模式切到模块模式后，先前启动的 `BotService` 仍在跑（直到用户手动停或系统回收）。
这是**实现缺口**（见 §4、§6-C3），不是设计上的互斥保证。

### 2.6 本机模式（Chaquopy）

#### 2.6.1 Python 侧模块

`assets/chaquopy/app.imy`（zip，15,603 B）解包后**只有 2 个 .pyc，无 .py 源码**：

| 文件 | 大小 | 作用（证据） |
|---|---|---|
| `bot_runner.pyc` | 28,634 B | 本机 bot 管理器主模块 |
| `probe.pyc` | 1,357 B | 自检：`platform`、`__version__`、`apscheduler` 可用性 |

`bot_runner` 暴露给 Kotlin 的顶层函数（`BotHost` 的 `callAttr` 实参逐一对上）：
`start_all(bots_json, base_dir)`、`start_one`、`stop_all`、`stop_one(appid)`、`is_running`、
`state_text`、`snapshot`、`log_text(n)`、`probe`、`memory_list(botdir)`、`memory_delete(botdir,key)`、`memory_clear(botdir)`。

内部实现痕迹（pyc 符号）：`_run_bot`、`_make_client`、`_fix_botpy_logging`、`_build_messages`、
`_call_llm_sync`、`_reply`、`_llm_conf`、`_persona`、`_stamp`、`threading`+`new_event_loop/set_event_loop`、
`ThreadPoolExecutor/run_in_executor`（`to_thread`）、`apscheduler`。

协议与依赖（`build.json` + `requirements-common.imy` 目录清单）：
**`qq_botpy 1.2.1`**、`aiohttp 3.14.5`、`apscheduler 3.11.3`、`attrs`、`pyyaml`、`yarl`、`multidict`、`idna`、`frozenlist`、`aiosignal`、`aiohappyeyeballs`、`propcache`、`tzlocal`、`_yaml(chaquopy_libyaml)`。
`requirements-arm64-v8a.imy` 只有 22 B（空 zip）→ **纯 Python 依赖，无额外 native 轮子**。

#### 2.6.2 怎么启动

1. `BotService.onStartCommand`：`startForeground(NOTIF_ID, …)` → `BotHost.startAll(this)` → `pinNotification("AndbnA","运行中")`
2. `BotHost.startAll`：`BotStore.writeLlmJson(ctx)`（写 `localbot/llm.json`）
   → 对每个 bot 调 `PersonaAssets.writeTo(ctx, persona, dataDir/appid)`
   → `module.callAttr("start_all", enabledJson, dataDir)`（`BotHost.java:84-92`）
3. `BotHost.ensure()`：首次 `Python.start(new AndroidPlatform(ctx))`，然后 `Python.getInstance().getModule("bot_runner")`；
   失败把原因存进 `lastError`（「Python 启动失败：…」「加载 bot_runner 失败：…」）
4. Python 侧：每个 bot 一条线程 + 自己的 asyncio 事件循环 + 自己的 `botpy` Client 类（pyc 文档串：「每个实例一套自己的 Client 类，避免事件回调串台」）
5. 停止：`BotService.onStartCommand(ACTION_STOP)` → `BotHost.stopAll()`，然后 `stopSelf()`；`onDestroy()` 也兜一次 `stopAll()`

#### 2.6.3 Kotlin ↔ Python 通信

Chaquopy 的 `PyObject.callAttr(name, args...) → PyObject.toString()`，**全部走字符串 / JSON 字符串**，
不做对象映射（pyc 注释原话：「从 Kotlin 侧传过来的是 JSON 字符串（Chaquopy 不帮你反序列化）」）。

| 方向 | 载荷 |
|---|---|
| K→P | `start_all(botsJson, baseDir)`：`botsJson` = `[{appid,secret,persona,enabled}]`（只含 enabled），`baseDir` = `filesDir/localbot` 绝对路径 |
| K→P | `memory_delete(botDir, key)`、`memory_clear(botDir)`、`log_text(n)` |
| P→K | `snapshot()` / `memory_list()` 返回 JSON 字符串；`state_text()` 返回一句话；`log_text` 返回纯文本尾 |

隐式契约（**文件即接口**）：`localbot/llm.json`（`{key, api, model}`，`model` 空则默认 `deepseek-chat`，
`BotStore.writeLlmJson`）、`localbot/<appid>/persona.txt`、`persona_name.txt`、`memory.json`、`local_bot.log`、`botpy.log`。

一个真实的坑（可复用的教训）：`BotStore.enabledJson` 传的是 JSON **字符串**，早期 Python 直接当 list 迭代 →
拿到单个字符 → 每条都抛异常 → 静默什么都没起（pyc 文档串明确记录了这个 bug 及修复）。

#### 2.6.4 多 bot 怎么隔离

按 bot 一个目录 + 一套运行时对象：

- 数据：`filesDir/localbot/<appid>/`（`BotHost.botDir`）
- 人格：每目录一份 `persona.txt` / `persona_name.txt`
- 记忆：每目录一份 `memory.json`；UI 明确「按 bot 分开存」（`LocalTextsContentKt.java:437`）
- 日志：每目录一份 `local_bot.log`
- 运行时：每 bot 一条线程 + 独立 event loop + 独立 botpy Client 子类
- 为什么必须隔离（pyc 文档串，直接引用 ≤3 行）：
  「QQ 的 openid 按 bot 分配，不同 bot 看到同一个人的 openid 不同，记忆必须隔离」
  —— 这是本机模式与模块模式在多 bot 语义上**不可调和**的根因（模块模式按 appid 目录 + 单一 active bot）。

### 2.7 面板地址 + TOKEN，与模块面板 API 的关系

- 配置：SP `andbna_panel`，key `host`、`token`（`Panel.PREF`；`Panel.init` 用 applicationContext）
- `configured = token 非空`（`Panel.getConfigured`）→ 决定首装是否停在 `SetupGate`
- 地址规范化：`baseUrl()`（见 §2.5.2(4)）
- 鉴权：模块模式请求带 `Bearer <token>`（对照 kit 的 `PanelClient.kt:72` 同语义；APK 侧见 `Panel.java` `call`）
- TOKEN 来源文案：「在管理器里对模块点「执行」（或 `sh action.sh panel`）会打印 TOKEN」（`AppKt.java:1171` 附近的 SectionCard）
- 与模块的关系一句话（APK 原文，`SettingsPageKt.java:1997`）：
  「面板只连手机本机（panel.py 绑 127.0.0.1）。bot 本体跑在模块里，关掉这个 App 不影响它。」
- 完整 API 面（`Panel.java` 全量，22 个端点）：`/api/health`、`/api/status`、`/api/bots`、
  `/api/bots/use`、`/api/bots/new`、`/api/bots/delete`、`/api/config`(GET+POST)、`/api/texts`、
  `/api/text/{name}`(GET+PUT)、`/api/personas`、`/api/personas/use`、`/api/memory`、
  `/api/memory/delete`、`/api/memory/purge`、`/api/memory/old`、`/api/names`、
  `/api/names/rename`、`/api/names/unname`、`/api/creds`(GET+PUT)、`/api/restart`、`/api/log`、`/api/llm/test`

---

## 3. 数据与配置

### 3.1 SharedPreferences 全清单（3 个文件，共 9 个 key）

| SP 名 | key | 类型 | 写入者 / 含义 |
|---|---|---|---|
| `andbna_panel` | `host` | String | `Panel.setHost`；默认回退 `127.0.0.1:8788` |
| `andbna_panel` | `token` | String | `Panel.setToken`；空 = 未配置 |
| `andbna_mode` | `mode` | String | `ModeStore.set`；值 `normal/adb/root/module`；`clear()` 会清空整个 SP |
| `andbna_local` | `selected_bot` | String | `BotStore.selectAppid`；当前选中 bot 的 appid |
| `andbna_local` | `local_appid` | String | 旧版遗留，仅 `BotStore.load()` 迁移时读 |
| `andbna_local` | `local_secret` | String | 同上 |
| `andbna_local` | `local_persona` | String | `PersonaAssets.select`；全局内置人格名（`PersonaAssets.KEY`） |
| `andbna_local` | `llm_key` | String | `BotStore.setLlmKey` |
| `andbna_local` | `llm_api` | String | `BotStore.setLlmApi`（接口基址） |
| `andbna_local` | `llm_model` | String | `BotStore.setLlmModel`；空则默认 `deepseek-chat` |

> 注意：`ui/Prefs.java` 是一个通用 `get/put(ctx,key)` 包装器（同样绑 `andbna_local`），
> 但全仓库无调用点（`grep -rn 'Prefs.INSTANCE'` 仅命中自身声明）——**死代码**，重写时不必复刻。

### 3.2 文件与 DB 路径

App 私有目录 `filesDir` = `/data/data/com.wjljh.andbna.qbot/files`（**运行期推断**，见 §8）：

| 路径 | 用途 | 证据 |
|---|---|---|
| `files/localbots.json` | 本机多 bot 清单（JSON 数组，4 字段） | `BotStore.file()` |
| `files/localbot/llm.json` | `{key, api, model}`，给 Python 读 | `BotStore.writeLlmJson` |
| `files/localbot/persona.txt` | 全局内置人格正文（trim + 末尾换行） | `PersonaAssets.select` |
| `files/localbot/persona_name.txt` | 全局人格名 | 同上 |
| `files/localbot/<appid>/persona.txt` / `persona_name.txt` | bot 级人格 | `PersonaAssets.writeTo` |
| `files/localbot/<appid>/memory.json` | 记忆，`[[role, content, ts], …]` | `BotHost.memory*` + pyc 文档串 |
| `files/localbot/<appid>/local_bot.log` | 每 bot 日志 | pyc 符号 `local_bot.log` |
| `files/localbot/botpy.log` | botpy 默认日志被重定向到此 | pyc `_fix_botpy_logging`（「botpy 默认把日志写到 os.getcwd，Android 的 cwd 是只读的」） |
| `/data/adb/service.d/andbna_qbot.sh` | root 模式开机脚本 | `RootHost.BOOT_SCRIPT` |
| （模块侧）`persona_backup/` | 面板切人格前的备份 | `TextsPageKt.java:506` 文案 |

**无数据库**：全项目不使用 SQLite/Room（`grep -rn 'sqlite' app 包` 与 `grep -rn 'Room' app 包` 均无命中）；
状态全部是 JSON 文件 + SharedPreferences。

---

## 4. 与模块 `/data/adb/modules/qqbotfix` 的关系

**结论：在"谁能托管 bot"这一层是互斥的两条路；但在进程层面无强制，切换模式不会停已启动的服务。**

| 维度 | 模块模式 | 本机模式 |
|---|---|---|
| bot 宿主 | 模块的 root 服务（服务 + tmux + 看门狗） | App 内的 `BotService` + Chaquopy |
| 依赖 | 必须先装模块（`RunMode.java:17`：需要 v9.46+） | 不需要模块、不需要 root |
| 通信 | HTTP `127.0.0.1:8788` + Bearer TOKEN | 进程内 `PyObject.callAttr` + JSON 文件 |
| 多 bot | 同时只跑一个（切换语义） | 可多个同时在线 |
| 关掉 App | bot 不受影响 | bot 停 |
| 写入模块目录 | 面板负责 | **完全不写**（App 无任何 `/data/adb/modules/*` 路径字面量） |

证据补充：

- 全 APK 只有 **1 处**写 `/data/adb/`：`RootHost` 的 `/data/adb/service.d/andbna_qbot.sh`（旁挂脚本，**不是**模块目录内的文件）
- 模式选择互斥：`localMode = mode != RunMode.MODULE`（`AppKt.java:377`）——同一时刻 UI 只有一套
- 但**切换不停服务**（§2.5.3）→ 从本机切到模块后，本机 `BotService` 可能仍在跑，
  与模块托管的 bot 同时在线 = 同一个 QQ 号被两条连接同时占用。**这是一个真实风险点，建议重写时修掉**
- 「模块模式 → 本机模式」不需要与模块交互；反之亦然。二者唯一的交汇点是「同一台手机上同一个 QQ bot 凭据」
- 无法确证模块目录内容：`adb shell ls /data/adb/modules/qqbotfix` → `Permission denied`（adb 未 root），见 §8

---

## 5. UI 结构要点（只记结构与字段）

### 5.1 组件库（`ComponentsKt`，10 个可复用件）

`SectionCard`、`KeyValue`、`InfoRow`（在 StatusPageKt 内）、`LineField`、`Note`、`Pill`、`Dot`、
`Gap`、`PageColumn`、`ActionChip`、`ConfirmButton`，外加 `rememberMetrics()`。
主题走 **Miuix**（`top.yukonga.miuix.kmp`）+ `Tokens`（`ui/Tokens.java` 提供 `okSurface/okIcon` 等语义色）。

### 5.2 hero（`HeroCard_FNF3uiM`）

| 字段 | 说明 |
|---|---|
| `title` | 大标题，状态词（工作中 / 已停止 / 网络异常 / 未连接 / 读取中 / 已上线 / 连接中） |
| `subtitle` | 一句话解释当前状态 |
| `modeLabel` | 模式胶囊（普通/ADB/root/模块；未选时显示「未选模式」`StatusPageKt.java:248`） |
| `iconTint` / `titleColor` / `bg` | 随 `working` 与动态取色变化（`Tokens.okIcon / okSurfaceLight / okSurfaceDark`） |
| 运行时长 | `fmtDur` → 「N 天 M 小时」「M 小时 K 分」 |

### 5.3 控制区按钮

| 页面 | 按钮 |
|---|---|
| 状态·面板 | 重启 bot、重启看门狗、试跑模型、读日志 |
| 状态·本机 | 启动、停止、自检；root 卡：加白名单、移出；ADB 卡：授权、连接 shell、重新检测 |
| Bot 页 | 启动全部、停止全部、收起、添加；卡片内：选人格、改密钥、卸载 |
| 内容页 | 保存、重新读取、（切人格） |
| 记忆/称呼表 | 删、删 30 天前、清空全部、改名、保存、取消、刷新 |

### 5.4 读数行（`InfoRow` × 6，面板模式固定顺序）

面板版本 → 面板地址 → 运行模式 → bot 进程 → 看门狗 → 内存 / CPU。
其中「内存 / CPU」是 `last=true` 的合行（「N MB · 累计 N 秒」）。

### 5.5 设置页分组

回复策略 / 凭据 / bot 管理 / 高级（运行模式、面板地址、重新设置连接）/ 关于。

### 5.6 文案密度特征（重写时的取舍依据）

大量文案承担「解释为什么」的职责，例如：
「完整版还原度最高但吃 token；精简版最省。」、
「四种方式的区别只有一个：谁负责让 bot 一直活着。」（`ModePickScreenKt.java:257`）。
这不是装饰，而是**用文案替代了文档**——kit 若不复刻这些句子，用户会遇到同样的困惑。

---

## 6. 冲突清单（与现行 kit 方案 `andbna-qbot-uikit/.../data/` 的取舍点）

现行 kit 的 data 层：`AppContainer.kt`、`PanelClient.kt`、`PanelError.kt`、`PanelModels.kt`、
`PanelPrefs.kt`、`PanelRepository.kt` —— **只调面板 HTTP**，无本机模式、无 Chaquopy、无 root/Shizuku。

### C1 · 面板 API 覆盖面：无冲突（可判定为已对齐）

APK `Panel.java` 的 22 个端点在 `PanelRepository.kt` 里全部有对应（`grep -o '"/api/[^"]*"' | sort -u` 输出 22 条，逐条对上）。
SP 名/键也一致（`andbna_panel` + `host`/`token`，`PanelPrefs.kt:49-50`）。
→ **结论：面板模式无需返工。**

### C2 · 多 bot 语义：A 或 B，必须选一个（**最高优先级**）

| | A · 面板语义（现状） | B · 本机语义（APK 的 local 侧） |
|---|---|---|
| 同时在线 | 1 个（`/api/bots/use` 切换） | N 个（`start_all` 全启） |
| 记忆归属 | 按 appid 目录，但只有 active 那个在跑 | 每 bot 独立目录 + 独立 openid 视角 |
| 实现代价 | 0（已实现） | 需 Chaquopy + 每 bot 线程/loop/Client 类 |
| 风险 | 用户想「同时挂两个号」时无解 | openid 隔离必须自己保证；日志/记忆面翻倍 |

推荐 **A**（理由：kit 已有、成本为 0、面板 API 是模块的唯一事实来源；B 的全部价值都依赖 Chaquopy）。
推翻条件：人类确认「必须同一台手机同时挂多个 QQ bot」。

### C3 · 模式切换是否停服务：APK 的做法是缺口，不要复刻

APK：切模式只写 SP（§2.5.3）。kit 若照抄，会出现「切到模块模式但本机服务还在跑」的双开。
→ 建议 kit 显式规定：**切换模式 = 先停旧宿主，再切**。这是「要么 A 要么 B」里唯一不需要人类拍板的一条
（APK 行为是 bug 而非设计，直接修）。

### C4 · 运行模式枚举是否保留 4 档：A 或 B

| | A · 只保留 `module`（+ 可选的 `normal` 占位） | B · 完整 4 档 |
|---|---|---|
| 与 kit 一致 | 是 | 否，需新增 root/Shizuku 权限与脚本写入 |
| 用户价值 | 模块用户 100% 覆盖 | 只对「不装模块」的用户有价值 |
| 代价 | 0 | +Shizuku 依赖、+`/data/adb/service.d` 写权限、+电池白名单改系统配置 |

推荐 **A**。推翻条件：产品明确要求「不装模块也能用」。

### C5 · 人格资源：A 或 B（**与 C6 强耦合**）

| | A · 走面板 `/api/personas` | B · 把 13 个 txt 打进 App，本地写文件 |
|---|---|---|
| 是否需要 Chaquopy | 否 | 否（只是文件 I/O） |
| 是否需要模块 | 是 | 否 |
| 资产体积 | 0 | +144 KB（可忽略） |
| 冲突 | 无 | 与「只调面板 HTTP」的定位冲突，但**不引入 Chaquopy** |

→ 若保留模块模式，选 **A**；若为「无模块也能改人格」留后路，**B 的代价只有 144 KB，是安全的加法**。

### C6 · ★ 本机模式是否必须引入 Chaquopy（APK 回到 ~40MB）？——**否，可以只保留模块模式**

**体积实测**（`du -sh` / `ls -la` 于 `_prev-apk/apk`）：

| 组成 | 大小 |
|---|---|
| `assets/chaquopy`（stdlib-common 4.33M + requirements-common 1.71M + stdlib-arm64 1.44M + bootstrap 0.60M + app 0.02M） | **9.7 MB** |
| `lib/arm64-v8a` Python 相关（`libpython3.13.so` 5.42M + `libssl/libcrypto/sqlite3` 的 python/chaquopy 双份 + `libchaquopy_java.so`） | **≈ 8–10 MB** |
| **Chaquopy 小计（未压缩）** | **≈ 18–19 MB** |
| `assets/personas`（13 txt） | 144 KB |
| dex（8 个，含 Compose/Miuix 库） | 38 MB |
| APK 实际大小 | 42.2 MB |

**必须引入 Chaquopy 的唯一理由**：本机模式要在手机上跑 **`qq_botpy 1.2.1`**（真·QQ 官方 bot SDK）
+ aiohttp + apscheduler。没有 Python 运行时，这条路径不存在；Kotlin 侧无法替代（`bot_runner` 的
全部业务都在 Python：连接、事件、LLM 调用、记忆写入）。

**因此 A/B 是：**

| | A · 只保留模块模式（推荐） | B · 保留本机模式 |
|---|---|---|
| 体积 | 现在的量级（不含 18–19 MB） | +18–19 MB，APK ≈ 40 MB 量级 |
| 免装模块 | 否 | 是（唯一卖点） |
| 维护面 | 1 套（HTTP） | 2 套（HTTP + Chaquopy 生命周期 + 13 个 pyc 黑盒） |
| 不可控风险 | — | `app.imy` 只有 .pyc，**无源码**；改一行 Python 都要重跑 pip/Chaquopy 构建 |
| 与 kit 定位 | 一致 | 冲突（kit 是纯 HTTP 客户端） |

**建议：选 A。** 理由三条：①本机模式的差异化价值只有「不装模块」，而模块已是既定方案；
②Python 侧是 .pyc 黑盒，等于把一个无法读改的子系统背进 App；③18–19 MB 换来的是与现有
kit 架构正交的第二套运行时，收益/成本比最差。

**推翻条件（任一成立就该选 B）**：产品要求「零模块、商店可直接装」；或要求「同一手机同时挂多 bot」（C2 选 B）。

### C7 · 称呼表 / 记忆清理：无冲突

面板模式已 1:1（`/api/memory*`、`/api/names*` 全在 kit 里）。本机模式**本来就没实现称呼表**（§2.3），
所以「只保留模块模式」不会丢功能。

### C8 · 试跑模型与日志：小缺口

kit 有 `/api/llm/test` 与 `/api/log?lines=`，但需确认 UI 是否提供了 APK 的「试跑模型 / 读日志」两个入口
（APK 在 `快捷操作` 区，`StatusPageKt.java:579/586`）。**这是 UI 层补齐，不是架构冲突。**

---

## 7. 可复用资产

### 7.1 13 档人格 txt（**可直接复用，零改造**）

- 已解包位置：`_prev-apk/apk/assets/personas/*.txt`（13 个，144 KB）
- 从原 APK 重新提取：

```bash
mkdir -p /tmp/personas
unzip -o -j AndbnA_1.1.0.apk 'assets/personas/*.txt' -d /tmp/personas
ls /tmp/personas | wc -l   # 13
```

- 清单（`wc -l` 实测行数，可据此判断「完整/精华/精简」的体量比例）：
  `奥黛塔-完整 339 / 奥黛塔-精华 49 / 奥黛塔 30`、`哥伦比娅-完整 318 / -精华 39 / 29`、
  `桑多涅-完整 327 / -精华 35 / 30`、`沃雅妮莎-完整 342 / -精华 39 / 27`、`猫娘 5`
- 分档规则：`-完整` / `-精华` / 无后缀=精简（`PersonaAssets.group()`）

### 7.2 Python 脚本（**只有 .pyc，无源码**）

- 位置：`_prev-apk/py/app/{bot_runner,probe}.pyc`（已从 `app.imy` 取出）
- 从原 APK 重新提取：

```bash
unzip -o AndbnA_1.1.0.apk 'assets/chaquopy/app.imy' -d /tmp/chq
cd /tmp/chq/assets/chaquopy && unzip -o app.imy -d /tmp/chq/app   # bot_runner.pyc, probe.pyc
```

- 可读性：Python 3.13 字节码，宿主机为 3.10，`marshal` 无法直接加载；
  本任务用「UTF-8 容错解码 + 正则抽 CJK 串/标识符」取得了文档串与全部符号（§2.6），
  **未做反编译级还原**（未确证：逐行逻辑）。若要真读源码，需 3.13 解释器 + 支持 3.13 的反编译器。

### 7.3 图标（**无自定义图标可提取**）

`grep -c 'android:icon' AndroidManifest.xml` → **0**，`<application>` 未声明 `android:icon`
（`_prev-apk/jadx-out/resources/AndroidManifest.xml`）。`res/` 下也没有 `mipmap*` 或 `ic_launcher*`：

```bash
find _prev-apk/apk/res -name '*ic_launcher*' -o -name 'mipmap*'   # 空
```

`res/drawable*` 里只有 `notification_*`、`ic_call_*` 等 **AndroidX 模板资源**。
→ 重写时的图标需自备，APK 无可复用图形资产。

### 7.4 主题与配色

`res/values` 未在 `apk/res` 中单列（只有 `color/drawable/layout`）；语义色在代码里：
`ui/Tokens.java`（`okSurfaceLight/okSurfaceDark/okIcon`）、`ui/ThemeKt.java`。
Miuix 主题键 `Theme.AndbnA`（Manifest `android:theme`）。

---

## 8. 未确证项

| # | 项 | 原因 | 影响 |
|---|---|---|---|
| U1 | `/data/adb/modules/qqbotfix` 的实际内容（panel.py、tmux 会话名、看门狗、版本） | `adb shell ls /data/adb/modules/qqbotfix` → `Permission denied`（adb 未 root；设备已连 `56a2558e`）；工作区内无模块源码，在 md 中 grep `qqbotfix` 与 `8788` 均无命中 | §4 只能从 App 侧证据推断；模块面板的行为未直接核对 |
| U2 | 设备上 UI 的真实排布 | 设备装的是**更新的开发版**（`com.wjljh.andbna.qbot` → `versionCode=200 / versionName=0.2.0`），不是本任务的目标 APK（vc110 / 1.1.0）；另有 `qbotrel`、`panel.debug`、`uipreview`。用它的界面截图会污染结论，故**未做 UI dump** | 页面区块顺序来自静态字符串，非运行时截图 |
| U3 | `filesDir` 绝对路径 `/data/data/com.wjljh.andbna.qbot/files` | 由包名 + Android 惯例推断；未在设备上 `run-as` 验证（debuggable=true 本可验证，但目标版本未安装） | 路径**结构**（`localbots.json`、`localbot/<appid>/…`）已由代码确证，只有前缀是推断 |
| U4 | Python 侧逐行逻辑 | `app.imy` 只有 3.13 `.pyc`，宿主机 Python 3.10，且未装支持 3.13 的反编译器 | §2.6 的函数清单/文件契约/文档串均为**符号+字符串级**证据，非源码级 |
| U5 | `botpy.log` 的确切落点层级（`localbot/botpy.log` vs `localbot/<appid>/botpy.log`） | pyc 里只有 `/botpy.log`、`botpy.log` 两个片段，未见完整 join | 不影响结构结论 |
| U6 | 设置页分组的**严格**上下顺序 | Compose 编译产物按重组作用域拆分到多个 lambda，行号不保序（§1.4 已标注） | 仅影响排布，不影响功能 |
| U7 | ADB 模式"起独立进程"是否曾另有实现 | 现行 v1.1.0 里 `IUserService` 只有 `exec`，无 bot 宿主能力；`RunMode.``ADB` 的 summary 与实现不一致（§2.5.1） | 判断为**文案先于实现**，但无法排除历史版本差异 |
| U8 | `probe()` 返回的确切字段 | `probe.pyc` 仅 1,357 B，符号为 `platform/__version__/apscheduler` | 「自检」展示什么未逐字确证 |

---

## 附：一页速查

- **页面**：4 个一级 tab（状态/内容/Bot/设置）+ 2 个全屏引导（SetupGate、ModePickScreen）+ 3 个页内二级。
  tab2「Bot」在模块模式下变身为「记忆/称呼表」页 —— 这是最容易漏掉的一条结构事实。
- **4 种运行模式中只有 2 种是真宿主**：模块模式（面板 HTTP）与本机模式（BotService+Chaquopy）；
  ADB 与 root 只是保活增强，bot 永远跑在同一个 BotService 里。
- **Chaquopy 的 18–19 MB 只为 `qq_botpy` 而付**；若产品接受"必须先装模块"，可以整块砍掉。
- **多 bot 语义二选一**（同时在线 vs 单一 active），这是本机/模块两套设计的真正分水岭。
- **人格 13 档是 144 KB 的纯文本资产**，与 Chaquopy 无关，可独立复用。
