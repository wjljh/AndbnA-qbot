# 15 · hero 卡点击交互与动画（G1 ★）

> 交付：2026-10-21 ｜ 对象：SukiSU Ultra v4.2.1（miuix 0.9.4）
> 上游已确认的静态规格不重复（颜色/字号见 `00-总览`，hero 三态见 `10-主页` §4、`11-主页状态取值` §2）。本文只讲**点下去会发生什么**。
> 引用格式：SukiSU 路径相对 `manager/app/src/main/java/com/sukisu/ultra/`，库里路径相对 `miuix-src/src/commonMain/top/yukonga/miuix/kmp/`。

---

## 0. 先纠一个前提（很重要，影响你整套做法）

需求书写的是「SukiSU 的 hero 点击是重启菜单」。**源码里不是这样。**

| 事实 | 证据 |
|---|---|
| hero 卡（工作中态）点击 = **跳新页** `Route.Install()`，不是弹层 | `ui/screen/home/HomeMiuix.kt:282-286` → `ui/screen/home/HomeScreen.kt:68`（`onInstallClick = { navigator.push(Route.Install()) }`） |
| 「重启菜单」这个弹层挂在**顶栏右上角的 × 图标**上，与 hero 卡没有任何关系 | `ui/screen/home/HomeMiuix.kt:237`（`actions = { RebootListPopupMiuix() }`）→ `ui/component/rebootlistpopup/RebootListPopupMiuix.kt:30-63` |

**所以你要的两件东西，在源码里是拆开的两半**：
- **弹层 + 动画参数** = 顶栏那个 `OverlayListPopup`（`RebootListPopupMiuix.kt:41-63`）——骨架、时序、spring 全在这里，可以直接照抄；
- **hero 卡的点击/按压反馈** = `StatusCard` 里的 `Card(onClick=..., showIndication=..., pressFeedbackType=Tilt)`（`HomeMiuix.kt:273-289`）——点击行为是"跳页"，你把它换成"打开弹层"即可。

你真正要做的是**把这两半接起来**：卡片按下有 Tilt → 抬手把弹层弹出来（锚点改成卡片）。§4「照抄骨架」给的就是这个接法。

---

## 1. 七问速答（细节在 §2）

| 问 | 答案 |
|---|---|
| ① 弹什么 | **`OverlayListPopup`**（非 BottomSheet、非 Dialog、非新页）。锚点：**锚在「调用处父布局节点」**（不是被点的按钮）。源码用 `Align.TopEnd` + 自写 `MenuPositionProvider`（左留 20dp）；接 hero 卡时推荐 `Align.End` + 库 `DropdownPositionProvider`（垂直 8dp，§4 骨架第 4–5 行） |
| ② 条目 | 7 项，顺序固定：重启 / 用户空间重启（条件）/ 软重启 / 重启到 Recovery / 重启到 Bootloader / 重启到 Download / 重启到 EDL（`RebootListPopup.kt:29-39`） |
| ③ 进出场 | 缩放 `0.15→1.0` + 方向性 clip 揭开 + alpha `0→1`；缩放 `spring(damping=0.82, stiffness=362.5)`，alpha 进 `tween(200ms)` / 退 `tween(150ms)`，遮罩进 `tween(300ms, SinOutEasing)` / 退 `150ms`；**退场总时长 ≈150ms**（alpha 收尾即卸载） |
| ④ 按压反馈 | **Tilt**（不是 Sink）：最大 **±8°**，`spring(damping=0.6, stiffness=400)`，`cameraDistance = 12×density`，以按下点反侧为轴心 |
| ⑤ 图标 | **不变**。hero 的 110dp 图标在按下/点击态不变色、不缩放、不旋转（倾斜是整卡一起转）；顶栏 × 按钮靠 `holdDownState` 停在水波 10% 叠加态 |
| ⑥ 长按 | **无**。hero 未传 `onLongPress`（`HomeMiuix.kt:273-289`），`Card` 有这个参数但没被用（`basic/Card.kt:96`） |
| ⑦ 触感 | **无**。hero 点击、弹层打开/关闭、条目选中都不触发 HapticFeedback；home 页唯一触感是滚动到底（`scrollEndHaptic`，`HapticFeedbackType.TextHandleMove`） |

---

## 2. 逐条展开

### 2.1 ① 点下去弹出什么 + 锚点

**七项格式 · hero 卡（工作中态 H1）**

1. **结构**：`Card(fillMaxWidth, colors=绿/白底, onClick, showIndication, pressFeedbackType=Tilt)` → `Box` → 三个叠放层（110dp 图标 Box / 左下模式文本 Box / 左上标题 Column）。`ui/screen/home/HomeMiuix.kt:273-369`
2. **文本**：「工作中 [安全模式] [越狱模式]」22sp SemiBold；「版本：x-y」15sp；左下「LKM / Built-in」16sp Medium。`HomeMiuix.kt:252-265`、`:329-343`
3. **间距**：图标 `offset(27dp, 31dp)`；模式文本内边距 水平 16dp·垂直 10dp；标题块内边距 水平 16dp·垂直 14dp。`HomeMiuix.kt:294`、`:312`、`:325`
4. **组件**：miuix `Card` 可点击重载（`basic/Card.kt:87-152`）
5. **交互**：见下表
6. **状态取值**：见 `11-主页状态取值` §2（H1/H2/H3），本文只讲点击分派
7. **图标**：`Icons.Rounded.CheckCircleOutline`，110dp，tint 动态 `primary@0.8`／静态 `#36D167`。`HomeMiuix.kt:297-306`

**点击分派表（源码实际行为）**

| 区域 | 条件 | 点下去发生什么 | 出处 |
|---|---|---|---|
| **整张 hero 卡** | `ksuVersion != null`（H1）且 `!isLateLoadMode` | 水波（onBackground 10%）+ 整卡 Tilt → 抬手 `navigator.push(Route.Install())` 进安装页 | `HomeMiuix.kt:282-288`、`HomeScreen.kt:68` |
| **整张 hero 卡** | H1 且 `isLateLoadMode` | 只用 Tilt 反馈；onClick 内部短路，**什么也不发生**；水波被关掉（`showIndication=false`） | `HomeMiuix.kt:283-287` |
| 卡内「LKM/Built-in」文本、标题、图标 | 任何 | **不可单独点**，没有 clickable；事件落到整卡 | `HomeMiuix.kt:308-366`（无 clickable） |
| H2 未安装卡 / H3 不支持卡 | `ksuVersion == null` | 同 H1 逻辑（整卡可点 → 安装页；late-load 下无效）。H2 右上另有独立 `TextButton「越狱」`，它自己吃点击 | `HomeMiuix.kt:372-407`、`:409-432` |
| 卡内无任何"更多/菜单"入口 | — | 源码 hero 卡里**没有**任何弹层触发点 | 全卡仅 1 个 onClick |

> 结论：**「哪块可点」= 整张卡**（一个 `combinedClickable` 覆盖全卡），没有死角、没有子元素抢事件；`isLateLoadMode` 只让"点了不做事 + 无水波"，**不取消可点性，也不取消 Tilt**（见 §6 勘误 1）。

**弹层类型选定（为什么是 OverlayListPopup）**

| 候选 | 源码用法 | 关键参数 | 是否推荐 |
|---|---|---|---|
| **`OverlayListPopup`** | 重启菜单 | 见 2.3 | ✅ 菜单项 3–8 个、要贴着锚点弹 |
| `OverlayBottomSheet` | **全 app 0 处**（grep `OverlayBottomSheet` 在 manager 模块无命中） | 进场 `folmeSpring(damping=0.9, response=0.38)` ≡ `spring(0.9, ≈273)`；退场同参数；位移 `translationY: baseOffset→0` | 条目多到要滚动、或要拖拽关闭时再换（`layout/BottomSheetContentLayout.kt:150-168`、`:281`） |
| `OverlayDialog` | 24 处，全是「要输入/要说明」的对话框（卸载确认 `ui/component/uninstalldialog/UninstallDialogMiuix.kt:63`、添加卸载路径 `ui/screen/umountmanager/UmountManagerMiuix.kt:242`、模块快捷方式 `ui/screen/module/ModuleMiuix.kt:586`…），**没有一处当菜单用** | 小屏进场 `spring(dampingRatio=0.88, stiffness=450)`，`scale 0.8→1.0`，`translationY: 容器高→0`；退场 `tween(260ms)` | ❌ 菜单不该用对话框（`layout/DialogContentLayout.kt:145-161`、`:234`、`:239`） |
| 跳新页 | hero 自己就是 | `navigator.push(Route.Install())` | ❌ 你要的是"点一下就在原地选" |

**锚点三种可选（按源码能力给出）**

| 写法 | `alignment` | `popupPositionProvider` | 弹层落点 | 说明 |
|---|---|---|---|---|
| 源码原样 | `Align.TopEnd` | `ListPopupDefaults.MenuPositionProvider`（SukiSU 自写，`ui/component/MenuPositionProvider.kt:11-77`） | 右边缘与锚点右边缘齐平，顶边 = 锚点底边 + 0 | margin 只给了 `start=20dp`，右/下为 0（`MenuPositionProvider.kt:75`） |
| 库里通用下拉（推荐接 hero） | `Align.End` | `ListPopupDefaults.DropdownPositionProvider`（`basic/ListPopup.kt:281`） | 下方放得下就下方，否则上方，再不行居中；垂直留 8dp | 参数 `verticalMargin=8dp, horizontalMargin=0dp`（`ListPopup.kt:236-237`） |
| 源码其它页的边角菜单 | `TopStart/TopEnd/BottomStart/BottomEnd` | 同上 | 四角落 | `ListPopup.kt:284-348` |

**锚点到底是哪个节点（决定你把 `OverlayListPopup` 放哪）**：
`ListPopupLayout` 用一个 `Spacer` 取 `parentLayoutCoordinates` 当锚点（`layout/ListPopupLayout.kt:124-137`），也就是**调用 `OverlayListPopup` 那一层的父布局节点**，不是被点的那个按钮。
- 顶栏菜单的锚点 = `TopAppBar` 的 actions Row（`basic/TopAppBar.kt:119-124`），所以弹层贴在顶栏右下；
- **你要贴着 hero 卡弹，就必须把 `OverlayListPopup` 写进 hero 卡内容里**（`Card` 的 content 是 `ColumnScope`，锚点就变成卡片内框）；写到页面级 Column 里则会锚到整页宽度（`HomeMiuix.kt:127-186` 的 Column），位置会跑偏。

**弹层自动右/左对齐与铺开方向不是配置项**，是运行时算出来的：根据算出的 offset 与锚点中心比较得出 `showBelow/showAbove/isRightAligned`（`basic/ListPopup.kt:491-521`），再据此决定 clip 从哪条边揭开（`ListPopup.kt:657-661`）与 `transformOrigin`（`ListPopup.kt:556-569`）。

---

### 2.2 ② 弹层里的条目（按源码顺序、带条件）

数据源 `getRebootListOption()`：`ui/component/rebootlistpopup/RebootListPopup.kt:22-40`；渲染 `RebootListPopupMiuix.kt:48-62` + `RebootDropdownItem`（`:67-84`）。

| # | 文案（zh-rCN） | 文案（en） | 传下去的 reason | 条件 |
|---|---|---|---|---|
| 1 | 重启 | Reboot | `""` | 恒定 |
| 2 | 用户空间重启 | Userspace reboot | `userspace` | **仅当** `PowerManager.isRebootingUserspaceSupported == true`（Android 11+ 一般成立）`RebootListPopup.kt:24-33` |
| 3 | 软重启 | Soft restart | `soft_reboot` | 恒定 |
| 4 | 重启到 Recovery | Reboot to Recovery | `recovery` | 恒定 |
| 5 | 重启到 Bootloader | Reboot to Bootloader | `bootloader` | 恒定 |
| 6 | 重启到 Download | Reboot to Download | `download` | 恒定 |
| 7 | 重启到 EDL | Reboot to EDL | `edl` | 恒定 |

文案出处：`res/values-zh-rCN/strings.xml:66-73`、`res/values/strings.xml:79-86`。

**条目行规格（七项格式 · 弹层条目 `DropdownItem`）**

1. **结构**：`Row`（垂直居中）→ `Text`。`ui/component/miuix/DropdownItem.kt:31-48`
2. **文本**：`text`，`MiuixTheme.textStyles.body1.fontSize` = **16sp**，**Medium**，色 `dropdownColors.contentColor` = `MiuixTheme.colorScheme.onSurfaceContainer`（`DropdownItem.kt:42-47`；`basic/TextStyles.kt:159-163`；`basic/Dropdown.kt:403-406`）
3. **间距**：水平 **20dp**；垂直：首项上 **20dp**、末项下 **20dp**、其余 **12dp**；行高不写死，由 16sp 行高 + 上下 padding 撑（首/末项约 20+20+~21 ≈ 61dp，中间项约 12+12+21 ≈ 45dp，【推算】`DropdownItem.kt:28-39`）
4. **组件**：SukiSU 自写 `DropdownItem`（不是库 `Dropdown`），底色 `dropdownColors.containerColor` = `MiuixTheme.colorScheme.surfaceContainer`（`DropdownItem.kt:34`；`basic/Dropdown.kt:406`）
5. **交互**：`Modifier.clickable` 整行可点（默认 `LocalIndication` = `MiuixIndication`，按下叠 **onBackground 10%**，进 `folmeSpring(1.0, 0.2)`、退 `folmeSpring(0.95, 0.35)`）→ 先关弹层再执行动作（`DropdownItem.kt:33`；`RebootListPopupMiuix.kt:78-81`；`theme/MiuixTheme.kt:32-36`；`utils/MiuixIndication.kt:27-33`）
6. **状态取值**：源码**没有** checked/selected 态，也没有图标位；7 项全是同款普通行（无分组、无分隔线、无禁用项）`DropdownItem.kt:31-48`
7. **图标**：**未使用**（行内只有文字）

**首项是"重启"这件事的副作用**：`reason == ""` 且当前是越狱模式（`Natives.isLateLoadMode`）时会先弹确认框而不是直接重启（`RebootListPopup.kt:44-58`，文案 `res:jailbreak_reboot_warning`）。你要复用这套骨架做"启动/停止/重启"，可以照这个模式：**首项（破坏性动作）走二次确认，其余直执行**。

---

### 2.3 ③ 进/退场动画（全部数值）

**七项格式 · 弹层容器 `ListPopupContent`**

1. **结构**：`Box`（graphicsLayer 缩放/alpha → clip 揭开 → 背景）→ 你的 `ListPopupColumn`。`basic/ListPopup.kt:607-627`
2. **文本**：无（容器）
3. **间距**：内边距 0（行自带 20/12dp 内边距）；**最小宽度 200dp**，`ListPopupColumn` 宽度 = 最宽项，钳在 **200–288dp**；最小高度 **50dp**；最大高度默认 `窗口高 − 上下 margin`。`ListPopup.kt:221`、`:227`、`:90-102`、`layout/ListPopupLayout.kt:206-215`
4. **组件**：`OverlayListPopup`（`overlay/OverlayListPopup.kt:36-76`）→ `ListPopupLayout`（`layout/ListPopupLayout.kt:69-254`）→ `ListPopupContent`
5. **交互**：点外部区域 / 点遮罩 → dismiss；`Back` 键与系统返回手势 → 交互式收起，弹层的缩放与 alpha 都乘 `(1 - backProgress)`，即**跟手退场**（`ListPopupLayout.kt:198-202`、`:154-177`、`:241-242`）
6. **状态取值**：底色 `MiuixTheme.colorScheme.surfaceContainer`，圆角 **16dp**（squircle 裁切）。`ListPopup.kt:604-605`、`:623`
7. **图标**：**未使用**

**时序与 spring（逐项，全部有出处）**

| 轨道 | 进场 | 退场 | 出处 |
|---|---|---|---|
| 缩放 / clip 揭开进度 `fractionProgress` | `0 → 1`，`spring(dampingRatio = 0.82f, stiffness = 362.5f, visibilityThreshold = 0.0001f)` | `1 → 0`，同一 spring，**但会被 alpha 提前截断** | `basic/ListPopup.kt:200`、`layout/ListPopupLayout.kt:97`、`:104` |
| alpha | `0 → 1`，`tween(200ms)`（线性） | `1 → 0`，`tween(150ms)`，**演完立刻卸载** | `ListPopup.kt:203`、`:206`、`ListPopupLayout.kt:98`、`:109-116` |
| 遮罩 dim | `0 → 1`，`tween(300ms, easing = SinOutEasing)` | `1 → 0`，`tween(150ms, SinOutEasing)` | `ListPopup.kt:209`、`:212` |
| 手势回弹 | — | `spring(0.82, 362.5)`（同 fraction） | `ListPopup.kt:215` |

**位移 / 缩放 / alpha 的起止值**

| 维度 | 从 | 到 | 公式 | 出处 |
|---|---|---|---|---|
| 缩放 | **0.15** | **1.0** | `scale = 0.15f + 0.85f * fraction` | `ListPopup.kt:612` |
| alpha | **0** | **1.0** | `alpha = alphaProgress * (1 - backProgress)` | `ListPopup.kt:615`、`ListPopupLayout.kt:242` |
| XY 位移 | **无**（全程不动，只缩放+clip+alpha） | — | 位置由 `popupPositionProvider` 一次算定 | `ListPopupLayout.kt:219-235` |
| clip 揭开方向 | 下方弹出→**从顶部**向下展开；上方弹出→**从底部**向上；居中→从中间向两侧 | — | `visibleHeight = height * progress`；`clipStart = 0 / h*(1-p) / h*(0.5-0.5p)` | `ListPopup.kt:648-682` |
| 遮罩 | `windowDimming`：亮 `#000000 @30%` / 暗 `#000000 @60%` | — | `dark ? Black 0.6 : Black 0.3` | `theme/Colors.kt:391`、`:501`；`ListPopupLayout.kt:189` |
| 弹层位置 margin | 左 **20dp**（SukiSU 的 `MenuPositionProvider`）；库下拉默认垂直 8dp | — | — | `ui/component/MenuPositionProvider.kt:75`、`ListPopup.kt:236-237` |

**时长换算（人话）**
- 进场：缩放是 spring，**没有固定毫秒**；按 `ζ=0.82, ω=√362.5≈19.0 rad/s` 推算，约 **180–220ms 视觉到位**【推算】，alpha 200ms 与之同时结束。
- 退场：**≈150ms**（alpha 是主时钟，演完 `snapTo(0)` 并卸载；`ListPopupLayout.kt:108-116`）。所以退出比进入快，且缩放看起来是"被掐断"的——这是源码的原样行为，不是 bug。
- 遮罩：**进 300ms / 退 150ms**。

---

### 2.4 ④ 卡片按压反馈：Tilt（不是 Sink）

**七项格式 · 按压反馈**

1. **结构**：`Card(pressFeedbackType = Tilt)` → 内部 `TiltFeedback()`（`IndicationNodeFactory`）经 `Modifier.pressable(interactionSource, indication = pressFeedback, delay = null)` 挂到卡片外层。`ui/screen/home/HomeMiuix.kt:288`、`basic/Card.kt:105-141`
2. **文本**：不适用
3. **间距**：不占布局（`LayoutModifierNode` 只改 layer 的 rotation，不改测量）
4. **组件**：`TiltFeedback`（`utils/PressFeedback.kt:84-171`）
5. **交互**：按下 → 倾斜；抬起/取消 → 回 0；**`delay = null`**（`basic/Card.kt:140`）意味着不走 `TAP_INDICATION_DELAY = 150ms`（`utils/Pressable.kt:145`），**手指落下立即开始倾斜**，滚动时才取消
6. **状态取值**：见下表
7. **图标**：不单独处理（整卡一起转）

| 参数 | 值 | 出处 |
|---|---|---|
| 类型 | `PressFeedbackType.Tilt`（枚举只有 `None / Sink / Tilt`） | `utils/PressFeedback.kt:176-185` |
| 倾斜角上限 `tiltAmount` | **8°**（`rotationX`、`rotationY` 各 ±8） | `PressFeedback.kt:85`、`:145-146`、`:163-164` |
| 弹簧 | `spring(dampingRatio = 0.6f, stiffness = 400f)` | `PressFeedback.kt:86` |
| 时长 | 无固定毫秒；`ζ=0.6, ω=√400=20 rad/s`，约 **250ms 视觉收敛**【推算】 | 同上 |
| 轴心 | 按按下点：X 左半→`pivotFractionX=1`，右半→`0`；Y 同理（即"按哪边，哪边沉下去"） | `PressFeedback.kt:140-143` |
| 3D 距离 | `cameraDistance = 12 × density`（约 12dp） | `PressFeedback.kt:165` |
| 水波（另一条并行轨道） | `showIndication=true` 时叠 `MiuixIndication(color = onBackground)`，按下 **+10% alpha**，进 `folmeSpring(1.0, 0.2)`（≡`spring(1.0, ≈987)`）/ 退 `folmeSpring(0.95, 0.35)`（≡`spring(0.95, ≈322)`） | `basic/Card.kt:114`、`theme/MiuixTheme.kt:32-36`、`utils/MiuixIndication.kt:27-33` |

**Sink 对照（你没选它，但会撞见）**：`sinkAmount = 0.94f`，`spring(0.8f, 600f)`；`WarningCard` 用的就是它（`PressFeedback.kt:31-34`；`ui/component/miuix/WarningCard.kt:38`）。**hero 卡是 Tilt，横幅卡是 Sink，别抄混。**

---

### 2.5 ⑤ 图标在点击/按下态的变化

| 图标 | 位置 | 按下/点击时 | 出处 |
|---|---|---|---|
| 110dp `Icons.Rounded.CheckCircleOutline`（hero 右下） | `HomeMiuix.kt:297-306` | **完全不变**：无旋转、无缩放、tint 不变。视觉上"动"的是整卡 Tilt 带来的透视 | `HomeMiuix.kt:297-306`（`tint` 只依赖 `isDynamicColor`，没有 pressed 分支） |
| 24dp `Icons.Rounded.ErrorOutline`（H2/H3） | `HomeMiuix.kt:388-393`、`:423-429` | 同样不变 | 同上 |
| 顶栏 `MiuixIcons.Close2`（菜单入口） | `RebootListPopupMiuix.kt:35-39` | **有色块叠加并保持**：`holdDownState = showTopPopup.value`，弹层开着期间该按钮一直处于 hold-down，水波恒定 **+10% alpha**（`HOLD_DOWN_ALPHA_DELTA`），关闭后回落 | `RebootListPopupMiuix.kt:30-40`；`utils/MiuixIndication.kt:28`、`:70-71`；`interfaces/HoldDownInteraction.kt:70-90` |

结论：**SukiSU 不做图标动画**。你要让图标转/缩放，是自己加的，源码里没有可抄的数值。

---

### 2.6 ⑥ 长按

| 目标 | 长按行为 | 出处 |
|---|---|---|
| hero 卡 | **无**。`Card` 有 `onLongPress` 参数，但 hero 没传；未传时 `combinedClickable` 的 `onLongClick = null` | `basic/Card.kt:96`、`:125-129`；`HomeMiuix.kt:273-289` |
| 弹层条目 | **无**。只有 `clickable`，没有 `combinedClickable` | `DropdownItem.kt:33` |
| 顶栏 × 按钮 | **无**。`IconButton` 只有 `clickable` | `basic/IconButton.kt:56-66` |

注意：`IconButton` 的 `holdDownState` **不是长按**，它是"外部状态驱动的持续按下外观"（`basic/IconButton.kt:53`、`interfaces/HoldDownInteraction.kt:70-90`）——源码用它让按钮在弹层打开期间保持按下色，**不要**把它当长按实现。

（对比：超级用户列表行确实有长按菜单 `onLongPress`，见 `ui/screen/superuser/SuperUserMiuix.kt:555`，那是另一份文档的事。）

---

### 2.7 ⑦ 触感（Haptic）

| 位置 | 触感 | 出处 |
|---|---|---|
| hero 卡点击 | **无** | `HomeMiuix.kt:282-289`（无 `LocalHapticFeedback`） |
| 弹层打开/关闭/选中 | **无** | `RebootListPopupMiuix.kt:27-63`、`DropdownItem.kt:18-48` |
| miuix `Card` / `pressable` / `SinkFeedback` / `TiltFeedback` | **库内不触发任何触感**（全库 grep：Card/Pressable/PressFeedback 均无 `performHapticFeedback`） | `basic/Card.kt`、`utils/Pressable.kt`、`utils/PressFeedback.kt` |
| home 页唯一触感 | 列表滚到边缘：`scrollEndHaptic()`，默认 `HapticFeedbackType.TextHandleMove` | `HomeMiuix.kt:120`；`utils/ScrollEndHaptic.kt:125` |
| 库里有触感的组件（你自选菜单项时可能用到） | `Switch` → `ToggleOn/ToggleOff`；`Checkbox` → `ToggleOn/ToggleOff/SegmentTick`；`RadioButton` → `ToggleOn/ToggleOff`；`Slider` → `GestureThresholdActivate` + `TextHandleMove` | `basic/Switch.kt:123-124`、`basic/Checkbox.kt:150-154`、`basic/RadioButton.kt:101-102`、`basic/Slider.kt:1063`、`:1084` |
| 全 app 主动触感（仅 Material 版页面，Miuix 版没有） | `HapticFeedbackType.VirtualKey`（点击按钮类）/ `ContextClick`（长按/上下文） | `ui/screen/module/ModuleMaterial.kt:316`、`:332`；`ui/screen/colorpalette/ColorPaletteScreenMaterial.kt:213`（ContextClick） |

结论：**默认没有触感**。你若要让"选中工作模式"有回弹感，抄 Material 版的 `HapticFeedbackType.VirtualKey`（点按）/ `ContextClick`（长按弹菜单）这两个名字，但要知道**SukiSU 的 Miuix 版没有这么做**，属于你自加。

---

## 3. 弹层视觉总览（一次看全）

```
OverlayListPopup（全屏覆盖，renderInRootScaffold=true）
├─ 遮罩 windowDimming（亮 30% 黑 / 暗 60% 黑，tween 300ms 进）
└─ 弹层 Box（scale 0.15→1.0，alpha 0→1，clip 定向揭开）
   └─ ListPopupContent：圆角 16dp、底色 surfaceContainer
      └─ ListPopupColumn：宽 200–288dp（钳最宽项）、可纵向滚动、最小高 50dp
         └─ DropdownItem × N：水平 20dp，首上 20 / 末下 20 / 中间 12，16sp Medium
```

---

## 4. 照抄骨架（≤10 行，参数名齐全）

> 目的：点 hero 卡 → 在卡片下方弹出列表；菜单项换成你的"工作模式 + 控制"。锚点之所以成立，是因为 `OverlayListPopup` **写在 Card 内容里**（§2.1 末尾）。

```kotlin
val show = remember { mutableStateOf(false) }                              // 1
Card(onClick = { show.value = true }, showIndication = true,
     pressFeedbackType = PressFeedbackType.Tilt) {                        // 2 Tilt 8° / spring(0.6,400)
    /* hero 内容原样放这里 */
    OverlayListPopup(                                                     // 3
        show = show.value,
        popupPositionProvider = ListPopupDefaults.DropdownPositionProvider, // 4 垂直 8dp
        alignment = PopupPositionProvider.Align.End,                      // 5 右对齐
        enableWindowDim = true,                                           // 6 遮罩
        onDismissRequest = { show.value = false },
    ) {
        ListPopupColumn {                                                 // 7 宽 200–288dp
            items.forEachIndexed { i, label ->                            // 8 换成你的条目
                DropdownItem(text = label, index = i, optionSize = items.size,
                    onSelectedIndexChange = { show.value = false; onPick(i) }) // 9
            }
        }
    }
}
```

**import 注意（两个同名 `ListPopupDefaults`，别打架）**：`top.yukonga.miuix.kmp.basic.ListPopupDefaults`（库里有 `DropdownPositionProvider`、`MinWidth`、`ContextMenuPositionProvider`，`basic/ListPopup.kt:198-349`）；`com.sukisu.ultra.ui.component.ListPopupDefaults`（SukiSU 只自写了 `MenuPositionProvider` 一个成员，`ui/component/MenuPositionProvider.kt:11-77`）。骨架第 4 行用的是**库**那个；第 3 条改动里用的是 **SukiSU** 那个。

**要改的只有 4 处**（其余全部照抄，别动）：
1. **第 8–9 行的 `items`**：换成你的条目文案列表（如「普通 / ADB / root / 模块 / 启动 / 停止 / 重启」）。要分组就在两组 `DropdownItem` 之间插一条 `Divider()`（`thickness = 0.75dp`，`color = MiuixTheme.colorScheme.dividerLine`，`basic/Divider.kt:66-77`）。
2. **第 9 行的 `onPick(i)`**：模式类条目 → 存模式；控制类条目 → 发命令。想让首项二次确认，抄 `RebootListPopup.kt:44-58`：`rememberConfirmDialog { ... }` + 先 `show.value = false` 再 `showConfirm(...)`。
3. **第 5 行 `alignment`**：想左对齐卡片就换 `Align.Start`；想严格贴右下角并左留 20dp，就换 `popupPositionProvider = ListPopupDefaults.MenuPositionProvider` + `alignment = Align.TopEnd`（后者是 SukiSU 自写的，import 是 `com.sukisu.ultra.ui.component.ListPopupDefaults`，`ui/component/MenuPositionProvider.kt:11`）。
4. **第 6 行 `enableWindowDim`**：不想要遮罩就 `false`（`ListPopupLayout.kt:182-191` 整段不渲染）。

**另有一条硬约束（不是改动项）**：`renderInRootScaffold` 保持默认 `true`。SukiSU 多个页面的 Scaffold 传了**空** `popupHost = { }`（home 就是：`ui/screen/home/HomeMiuix.kt:113`；另有 `ui/screen/settings/SettingsMiuix.kt:103` 等），弹层是靠 `renderInRootScaffold=true` 挂到**最外层** Scaffold 的 `MiuixPopupHost` 才有地方渲染（`basic/Scaffold.kt:98`、`:111`、`:122`；`utils/MiuixPopupUtils.kt:265-266`；最外层 Scaffold 在 `ui/MainActivity.kt:495`，用默认 popupHost）。改成 `false` 就会在自己的页面里「点了没反应」。

**不要改的三件事**：不要给弹层加 `AnimatedVisibility`（进出场由 `ListPopupLayout` 内部驱动，外面再包会导致双动画，`layout/ListPopupLayout.kt:50-56` 的注释明确写了 host 不该自己做动画）；不要把 `OverlayListPopup` 挪到页面级；不要给 hero 卡加 `onLongPress`（除非你确实要长按菜单）。

---

## 5. 参数速查表

| 项 | 值 | 出处 |
|---|---|---|
| 弹层组件 | `OverlayListPopup` | `overlay/OverlayListPopup.kt:37` |
| 默认 `minWidth` | 200dp | `basic/ListPopup.kt:221` |
| 最小高度 | 50dp | `basic/ListPopup.kt:227` |
| 列宽范围 | 200–288dp | `basic/ListPopup.kt:90-91` |
| 圆角 | 16dp | `basic/ListPopup.kt:604` |
| 底色 | `MiuixTheme.colorScheme.surfaceContainer` | `basic/ListPopup.kt:605` |
| 缩放起止 | 0.15 → 1.0 | `basic/ListPopup.kt:612` |
| 缩放 spring | `spring(damping=0.82, stiffness=362.5)` | `basic/ListPopup.kt:200` |
| alpha 进 | `tween(200ms)` | `basic/ListPopup.kt:203` |
| alpha 退 | `tween(150ms)` | `basic/ListPopup.kt:206` |
| 遮罩进 | `tween(300ms, SinOutEasing)` | `basic/ListPopup.kt:209` |
| 遮罩退 | `tween(150ms, SinOutEasing)` | `basic/ListPopup.kt:212` |
| 遮罩色 | 亮 `#000000@30%` / 暗 `#000000@60%` | `theme/Colors.kt:391`、`:501` |
| 退场总时长 | ≈150ms（alpha 收尾即卸载） | `layout/ListPopupLayout.kt:108-116` |
| 初始 alpha | 0 | `layout/ListPopupLayout.kt:83` |
| 弹层 margin（SukiSU 菜单） | 左 20dp，其余 0 | `ui/component/MenuPositionProvider.kt:75` |
| 弹层 margin（库下拉） | 垂直 8dp、水平 0 | `basic/ListPopup.kt:236-237` |
| 条目字体 | body1 = 16sp，Medium | `ui/component/miuix/DropdownItem.kt:44-45`；`basic/TextStyles.kt:159-163` |
| 条目左右内边距 | 20dp | `DropdownItem.kt:35` |
| 条目上下内边距 | 首上 20dp / 末下 20dp / 其余 12dp | `DropdownItem.kt:28-29`、`:36-39` |
| 条目文字色 | `onSurfaceContainer` | `basic/Dropdown.kt:404` |
| 条目底色 | `surfaceContainer` | `basic/Dropdown.kt:406` |
| 条目按下叠色 | `onBackground` @10% | `utils/MiuixIndication.kt:27`、`theme/MiuixTheme.kt:32` |
| 条目按下 spring 进 | `folmeSpring(1.0, 0.2)` ≡ `spring(1.0, ≈987)` | `utils/MiuixIndication.kt:30`；`anim/MiuixEasing.kt:24` |
| 条目按下 spring 退 | `folmeSpring(0.95, 0.35)` ≡ `spring(0.95, ≈322)` | `utils/MiuixIndication.kt:31` |
| hero 按压类型 | `PressFeedbackType.Tilt` | `ui/screen/home/HomeMiuix.kt:288` |
| Tilt 角度上限 | 8°（X、Y 各 ±8） | `utils/PressFeedback.kt:85`、`:145-146` |
| Tilt spring | `spring(damping=0.6, stiffness=400)` | `utils/PressFeedback.kt:86` |
| Tilt cameraDistance | `12 × density` | `utils/PressFeedback.kt:165` |
| 按下判定延迟 | `delay = null`（立即） | `basic/Card.kt:140`；`utils/Pressable.kt:145` |
| Sink 缩放（对照） | 0.94 | `utils/PressFeedback.kt:32` |
| Sink spring（对照） | `spring(0.8, 600)` | `utils/PressFeedback.kt:33` |
| hero 卡圆角/内边距 | 16dp / 0dp | `basic/Card.kt:190`、`:195` |
| 顶栏按钮最小尺寸 | 40×40dp | `basic/IconButton.kt:84-89` |
| 分隔线（若分组） | 0.75dp / `dividerLine` | `basic/Divider.kt:71-76` |
| 长按 | 无 | `basic/Card.kt:96`、`:125-129` |
| 触感 | 无 | `utils/PressFeedback.kt` 全文无 haptic |

---

## 6. 勘误（与上游文档冲突处，以源码为准）

**勘误 1 — `11-主页状态取值.md:70`（H1-e 越狱模式行）**
上游写「**整卡不可点**：showIndication = false、onClick 内部短路；**按下也无 tilt 指示**」。
实际：`showIndication = false` 与 `pressFeedbackType = PressFeedbackType.Tilt` 是**两个独立参数**，后者**无条件**传（`ui/screen/home/HomeMiuix.kt:287-288`）；`Card` 里 `pressFeedback != null` 就挂 `TiltFeedback`（`miuix:basic/Card.kt:105-113`），`showIndication` 只决定是否叠 `LocalIndication` 水波（`Card.kt:114`）。所以**越狱模式下 Tilt 倾斜照常有，只是没有水波、点了没动作**。
影响：你要做"按下看不出来但能点"或"完全不动"两种效果时，得自己按这个区分来实现——Tilt 要关必须显式写 `pressFeedbackType = if (isLateLoadMode) PressFeedbackType.None else PressFeedbackType.Tilt`。

**勘误 2 — `10-主页.md:416`「右上动作（打开重启菜单）」措辞**
该行把 `MiuixIcons.Close2` 描述成「一个 ×，仅当菜单入口」。实际它是重启用图标位（`contentDescription = R.string.reboot`），在 v4.2.1 里这个位置**只**用来开重启菜单，没有第二种含义。数值（24dp / 40×40dp 按钮 / onBackground）无误，仅语义补充。

**未发现颜色口径冲突**：本文用到的 `surfaceContainer` / `onSurfaceContainer` / `onBackground` / `windowDimming` / `dividerLine` 与 `00-总览` §2、§6 一致。

---

## 7. 未确证清单（不编数字）

| 项 | 为什么拿不到 |
|---|---|
| 进场 spring 的"精确到 ms 的时长" | `spring()` 本身没有时长；源码没有额外时钟。文中 180–220ms 是**按 ζ/ω 推算**，已标注【推算】 |
| Tilt 的"精确收敛时间" | 同上，250ms 为【推算】 |
| 条目行高精确 dp | 源码不写死高度，由 16sp 行高（字体度量）+ padding 决定；文中 61/45dp 为【推算】，真机字体缩放会变 |
| `isRebootingUserspaceSupported` 在你机器上的实际值 | 取决于 ROM（Android 11+ 通常 true）；只有真机 `dumpsys` 能定。故第 2 项"用户空间重启"按**条件显示**写 |
| 弹层在超大屏/折叠屏的实际落点 | `ListPopupLayout` 会按 `windowBounds` 钳制（`ListPopupLayout.kt:219-235`），无真机无法给像素 |
| haptic 在真机是否被系统设置关掉 | 系统级开关，源码层看不到 |

---

## 8. 需求书 §6 自查（逐条）

- [x] **每个动画参数都给了数值**：缩放 0.15→1.0；alpha 200/150ms；遮罩 300/150ms；缩放 spring `damping=0.82, stiffness=362.5`；Tilt 8° + `spring(0.6, 400)` + `cameraDistance=12×density`；Sink 0.94 + `spring(0.8,600)`（对照）；水波 10% + `folmeSpring(1.0,0.2)/(0.95,0.35)`；退场总时长 ≈150ms；圆角 16dp；条目 20/12/20dp、16sp。见 §2.3、§2.4、§5。
- [x] **弹层类型点名到具体组件**：`OverlayListPopup`（`overlay/OverlayListPopup.kt:37`）+ `ListPopupLayout` + `ListPopupContent` + `ListPopupColumn` + SukiSU 自写 `DropdownItem`；并与 `OverlayBottomSheet` / `OverlayDialog` / 跳页三种做了取舍对照（§2.1）。
- [x] **每条有 `文件:行号`**：全文 100+ 处，无裸结论（含 miuix 库内 `ListPopup.kt`、`PressFeedback.kt`、`Card.kt`、`Pressable.kt`、`MiuixIndication.kt`、`IconButton.kt`、`TextStyles.kt`、`Divider.kt`、`MiuixEasing.kt`、`HoldDownInteraction.kt`）。
- [x] **没贴大段源码**：单处引用均为行号或 ≤3 行结构/参数；§4 的 10 行是**调用骨架**（你要的那份），不是库实现。
- [x] **上一批已确认过的内容不重复**：hero 三态取值只给指针（`11-主页状态取值` §2）；颜色只给变量名（`00-总览` §2/§6）；Tilt 数值按需求重复了一次（G1 必须），其余不重复。

---

## 9. 一页备忘（最小实现路径）

1. 抄 §4 的 10 行骨架，放进 hero 卡内容里；
2. `items` 换成你的 7 项（模式 4 + 控制 3）；
3. 锚点默认给 `DropdownPositionProvider + Align.End`（贴卡片下方、右对齐、垂直 8dp）；
4. 动画**什么也别写**——`0.15→1.0` 缩放 + 定向揭开 + 200ms alpha 由库内自动完成；
5. 卡片按压就是 `pressFeedbackType = PressFeedbackType.Tilt`（±8°，按下点侧为轴）；
6. 想要触感/图标动效，是**你自己新增**的能力，源码里没有对应数值可抄。
