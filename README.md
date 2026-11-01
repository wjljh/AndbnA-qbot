# AndbnA

把 QQ 机器人整套跑在手机里的 Android App：Python 运行时（Chaquopy）打包在 APK 内，由本机前台服务托管，不依赖模块、不上报任何数据。

## 功能

- 多个 bot 同时在线，各自独立的人格 / 记忆 / 称呼 / 定时问候
- 内置人格：奥黛塔 / 哥伦比娅 / 桑多涅 / 沃雅妮莎 / 猫娘 / 鲸鱼娘；支持导入 .md/.txt
- 群聊 fail-closed 闸门（@ 判定、作者三信号、回声去重、限流与熔断）
- 记忆（本机 JSON）、多选删除、10 秒撤销
- 模型配置（OpenAI 兼容接口）+ 识图（不支持图片时写日志并退回纯文本）
- 定时问候：每个 bot 独立时段，窗口内随机错开，可发主人私聊与已知群
- 三种运行模式：普通 / ADB(Shizuku) / root；统一的「自动拉回」看门狗（进程外脚本，被杀可自动拉起）
- 首次使用引导（含 Shizuku / root / 电池白名单真检测）

## 运行模式：普通 / ADB(Shizuku) / root

三种模式下 **bot 的运行内核完全相同**（Python 跑在 App 进程里，Chaquopy），差别只在「保活权限」：能不能把 App 加进电池白名单、被系统杀掉之后能不能自己回来。

| | 普通模式 | ADB 模式（Shizuku） | root 模式 |
|---|---|---|---|
| 需要什么 | 什么都不用装 | 安装 [Shizuku](https://shizuku.rikka.app/) 并授权本应用 | KernelSU / Magisk 等 root |
| 加电池白名单 | ❌ | ✅ 通过 shell 执行 | ✅ 通过 su 执行 |
| 被杀后自动拉回 | ❌ | ✅（看门狗走 shell 通道） | ✅（看门狗走 su 通道） |
| 稳定性 | 一般 | 好 | 最好 |

### ADB 模式（Shizuku）

1. **建议 Shizuku 13.7 及以上版本**。13.6 及更旧版本在 Android 17 上服务进程会被系统新回调打崩（`AbstractMethodError: IProcessObserver.onProcessStarted`），表现就是「授权了但连不上」；
2. 启动 Shizuku 服务（两种任选）：
   - 打开 Shizuku App 按提示启动；
   - 或电脑上用 adb：`adb shell <Shizuku APK 目录>/lib/arm64/libshizuku.so`（13.7 起 starter 就是 `libshizuku.so`，不再是 `start.sh`）；
3. 回 App → 状态页 → 点 hero 进入「运行控制」→ 选 **ADB 模式**，会弹出 Shizuku 的授权框，选「始终允许」；
4. 成功后状态页显示「ADB 托管」「shell 服务 已连上」，并自动把本应用加入电池白名单（可在日志里看到 `Added: <包名>`）。

> 注意：**先启动 Shizuku，再打开 AndbnA**。如果先开 App 再起 Shizuku，App 进程拿不到 binder，需要重启一次 App 才会重新绑定。

### root 模式

1. 设备已 root（KernelSU / Magisk 等），首次切换会弹 su 授权，选允许；
2. 选 **root 模式** 后，App 通过 `su` 执行 `dumpsys deviceidle whitelist +<包名>` 加白名单，并用 `am start` 把自己拉回来；
3. 这是最稳的模式：不依赖 Shizuku 服务，也不会受 Shizuku 版本兼容性影响。

### 自动拉回（看门狗）

ADB / root 模式下默认开启（设置页可关）：

- 进程外的一份 sh 脚本常驻，**每 60 秒**检查一次 App 是否还活着；
- 发现被杀就用对应通道（Shizuku shell / su）把 App 拉起来；
- **10 分钟内最多拉 3 次**，超过就记「已达上限，暂停」并停止尝试，等你手动点「启动」；
- 你在 App 里点「停止全部」时不会拉回（尊重你的意图），点「启动」后恢复；
- 状态页会显示 `自动拉回：已启用 · 上次 HH:MM:SS` / `未启用（未绑定 Shizuku）` / `已暂停（达上限）` / `已关闭（设置里开启）`。

### 不用 Shizuku 也想保活：电脑 adb 一条命令

如果你不想装 Shizuku、也暂时不 root，可以用电脑执行一次（重启后需重做）：

```bash
adb shell dumpsys deviceidle whitelist +com.wjljh.andbna.qbot
```

这会把 App 加进系统的电池优化白名单，被杀概率明显降低（但**不会**让它被杀后自动回来——那需要 ADB/root 模式）。

### 常见问题

**Q：切到 ADB 模式后提示「Shizuku 没在运行」？**
A：Shizuku 服务没起，或版本太旧。升级到 13.7+ 后按上面第 2 步启动服务，再回来切一次。

**Q：切 ADB 模式后在 Shizuku 里看不到 AndbnA？**
A：先在 App 里点一次「申请 Shizuku 授权」触发请求，Shizuku 才会把本应用列出来。

**Q：ADB 模式下多出 `com.wjljh.andbna.qbot:shell` 进程占内存？**
A：那是 Shizuku 的 UserService 进程（Shizuku 在客户端退出后不回收它）。App 内置清理器会在孤儿进程存活超过 5 分钟后回收，通常不需要你处理。

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

## 目录

- `andbna-app/` Android 应用（Kotlin + Compose/MiuiX + Chaquopy）
- `andbna-app/app/src/main/python/bot_runner.py` 机器人运行时（qq-botpy）
- `andbna-app/app/src/main/assets/personas/` 内置人格文本
- `delivery/` 交付说明（构建、数据布局、已知边界）
- `docs/` 人格来源与许可

## 第三方与许可

本项目以 MIT 发布。内置人格文本来自以下 MIT 项目，保留其署名：

- [DGP-Studio/Genshin.Skill](https://github.com/DGP-Studio/Genshin.Skill) — 奥黛塔 / 哥伦比娅 / 桑多涅 / 沃雅妮莎
- [linsport/dsh-whale-persona](https://github.com/linsport/dsh-whale-persona) — 鲸鱼娘（本仓库为裁剪版，见 `docs/鲸鱼娘-来源与许可.md`）

UI 结构与数值参考了 SukiSU-Ultra 与 KernelSU-Style-UI-Kit，但**未复制其代码**；引导页动效参考 JetBrains/compose-multiplatform 的示例。
