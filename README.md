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
