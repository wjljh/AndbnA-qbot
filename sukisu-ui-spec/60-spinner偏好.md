# 60 · `OverlaySpinnerPreference` 用法（需求书 §2 G3）

> 对象：Miuix **0.9.4** 官方 sources jar（本轮新解包到 `[L]`）
> **先说前提**：SukiSU v4.2.1 **全仓 0 处**使用 `SpinnerPreference`（`grep -rn "SpinnerPreference"` 在 manager 模块 329 个 .kt 里无命中）。所以本篇**没有"抄 SukiSU 实测外观"这条路**，所有结论直接来自库源码（这也是最准的口径：SukiSU 用的 Dropdown 行与 Spinner 行是同一套内核）。
> 引用缩写同 40-设置 §0：`[L]` = `/home/wjljh/toolchain/miuix-src/src/commonMain/top/yukonga/miuix/kmp/`。

---

## 0. 六问六答（三十秒版）

1. **名字**：0.9.4 里就叫 `OverlaySpinnerPreference`，**共 6 个重载**（3 个"弹层版" + 3 个"对话框版"），签名见 §1。
2. **items 类型**：`List<DropdownItem>`，**不是** `List<String>`；但每一项最小只需要一个 `text`，所以写起来只比字符串多一层 `.map { DropdownItem(text = it) }`（§3）。
3. **当前选中**：一个 `selectedIndex: Int` 就够，库内部拿它去覆盖每项的 `selected`，你不用自己维护（§3.2）。
4. **何时生效**：**点即生效**，点完自动收起，**没有"确定"按钮**（§3.3）。对话框版的按钮是"关闭"不是"确定"，拿不到确认回调——要"确认后生效"得自己包 `OverlayDialog`。
5. **弹层长相**：**不是滚轮（Spin）**，是**锚定列表弹层**（行下方 8dp、右对齐、宽 200–288dp、圆角 16dp、可滚动，§4.1）；对话框版是"标题 + 列表 + 一个通栏按钮"（§4.2）。
6. **与 `OverlayDropdownPreference` 的关系**：同一套弹层内核，只差 items 类型 / 省略号 / 有无对话框重载（§5）。**13 档人格下拉我的推荐是 `OverlayDropdownPreference`**，理由在 §5.2。

---

## 1. 完整函数签名（所有参数 + 类型 + 默认值）

### 1.1 弹层版（popup mode，3 个重载）

**A. `items: List<DropdownItem>` 版（你最可能用这个）** — `[L]preference/OverlaySpinnerPreference.kt:60-78`

| 参数 | 类型 | 默认值 |
|---|---|---|
| `items` | `List<DropdownItem>` | **必填** |
| `selectedIndex` | `Int` | **必填** |
| `title` | `String` | **必填** |
| `modifier` | `Modifier` | `Modifier` |
| `titleColor` | `BasicComponentColors` | `BasicComponentDefaults.titleColor()` |
| `summary` | `String?` | `null` |
| `summaryColor` | `BasicComponentColors` | `BasicComponentDefaults.summaryColor()` |
| `spinnerColors` | `DropdownColors` | `DropdownDefaults.dropdownColors()` |
| `startAction` | `(@Composable () -> Unit)?` | `null` |
| `bottomAction` | `(@Composable () -> Unit)?` | `null` |
| `insideMargin` | `PaddingValues` | `BasicComponentDefaults.InsideMargin`（= 16dp 四边） |
| `maxHeight` | `Dp?` | `null`（→ 窗口高 − 边距） |
| `enabled` | `Boolean` | `true` |
| `showValue` | `Boolean` | `true`（右侧显示当前值） |
| `renderInRootScaffold` | `Boolean` | `true` |
| `onExpandedChange` | `((Boolean) -> Unit)?` | `null` |
| `onSelectedIndexChange` | `((Int) -> Unit)?` | `null` |

**B. `entry: DropdownEntry` 版** — `:115-132`（把单组包成 `listOf(entry)`，`:133-134`）
**C. `entries: List<DropdownEntry>` 版** — `:155-172`（多组之间自动插分隔线；`collapseOnSelection` 默认 `entries.size <= 1`）

### 1.2 对话框版（dialog mode，3 个重载）

**A. `items` 版** — `:288-308`；**B. `entry` 版** — `:344-362`；**C. `entries` 版** — `:386-404`

与弹层版相比的差异（**只列不同**）：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `dialogButtonString` | `String` | **必填（新增）** | 底部按钮文案，点击 = 关闭 |
| `popupModifier` | `Modifier` | `Modifier` | 新增，没用 `maxHeight` |
| `spinnerColors` | `DropdownColors` | `DropdownDefaults.dialogDropdownColors()` | **默认值换成了 dialog 配色**（选中底 `tertiaryContainer`） |
| `maxHeight` | — | **不存在** | 对话框模式没有这个参数 |
| `collapseOnSelection` | `Boolean` | `true` | 多一个可关掉的参数（`:305`、`:402`） |

### 1.3 三个必填参数的顺序（顺序调用时别搞错）

- 弹层版：`(items, selectedIndex, title, …)`（`:61-63`）
- 对话框版：`(items, selectedIndex, title, dialogButtonString, …)`（`:289-292`）
- 建议**一律用具名参数**，因为同名的 6 个重载只靠参数种类/个数区分。

---

## 2. 最小可用示例（10 行，可直接抄）

```kotlin
val 人格 = listOf("默认", "严谨", "活泼", …)                 // 共 13 档；只要文案
var 选中 by rememberSaveable { mutableIntStateOf(0) }        // ① 当前选中项从这来

Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {          // 行必须包在 Card 里（40-设置 §2.2）
    OverlaySpinnerPreference(
        title = "人格",
        summary = "决定回复语气",
        items = 人格.map { DropdownItem(text = it) },         // ② items 放什么：一项 = 弹层里一行
        selectedIndex = 选中,                                  // ③ 传索引即可，不用管 item.selected
        onSelectedIndexChange = { i -> 选中 = i; 应用人格(i) },  // ④ 点即回调，随后弹层自动收起
    )
}
```

> 四个"接哪"的位置就是上面 ①②③④：
> ① 状态放你自己的 `rememberSaveable`（库不持有选择状态）；
> ② `items` 是数据源，文案就是 `DropdownItem.text`；
> ③ 当前选中是**受控**的，必须由你回传 `selectedIndex`，否则高亮不会动；
> ④ 回调里做业务（切人格），库不管你怎么存。

---

## 3. items 的数据类型 / 当前选中 / 何时生效

### 3.1 `items` 是模型，不是字符串

`DropdownItem(text, enabled, selected, onClick, icon, summary, children)` — `[L]basic/Dropdown.kt:339-360`

| 字段 | 默认 | 用途 |
|---|---|---|
| `text: String` | 必填 | 行文案（**唯一的必填字段**） |
| `enabled: Boolean` | `true` | 单项禁用 → 该项变灰且点不动（`[L]basic/Dropdown.kt:120-124`） |
| `selected: Boolean` | `false` | **传进来会被 `selectedIndex` 覆盖**，你填了也没用 |
| `onClick: (() -> Unit)?` | `null` | 每项自己的追加回调（在 `onSelectedIndexChange` 之后执行，`:85-88`） |
| `icon` / `summary` | `null` | 可选行首图标 / 副标题（14sp） |
| `children` | `null` | 子菜单（级联用，本件不用） |

便捷构造：`DropdownItem(title = "严谨", summary = "少废话")` —— 有一个兼容 `SpinnerEntry` 的次构造函数（`icon, title, summary`）在 `[L]basic/Dropdown.kt:351-359`。

### 3.2 当前选中：只传一个 `Int`

库把 `items` 逐项 `copy(selected = index == selectedIndex)`（`[L]preference/OverlaySpinnerPreference.kt:79-93`），所以你**不要**自己维护每项的 `selected`；把 `selectedIndex` 挂到你的状态上即可（受控组件）。
越界不崩：用 `indexOf(...).coerceAtLeast(0)` 是 SukiSU 的做法（`[S]ui/screen/settings/SettingsMiuix.kt:192-193` 语言行）。

### 3.3 何时生效：**点即生效**，没有确认

- 点某项 → `onSelectedIndexChange?.invoke(index)` → `item.onClick?.invoke()` → 若 `collapseOnSelection == true`（默认）立刻 `onDismiss()`（`[L]preference/OverlaySpinnerPreference.kt:85-88`、`:109`）。
- 同时给一次 `HapticFeedbackType.Confirm` 触感（`[L]popup/OverlayDropdownPopup.kt:81`）。
- **对话框版的按钮也只是"关闭"**：`TextButton(text = dialogButtonString, onClick = onDismiss)`（`[L]popup/OverlayDropdownPopup.kt:195-202`）——它没有把你选中的值带回来，因为选中值早就在点击时回调过了。
- 所以："点确定才生效"**库不提供**。真要"确认后生效"，两条路：① 在 `onSelectedIndexChange` 里先写 `pending`，等你自己弹的确认框再 apply；② 关掉自动收起（只有 `entries` 重载暴露 `collapseOnSelection`，`:170`）让用户能连续点但不会自动关——仍不是确认语义。

---

## 4. 弹层长相（和"滚轮"完全不是一回事）

### 4.1 弹层版：锚定列表（`OverlayListPopup`）

| 项 | 值 | 出处 |
|---|---|---|
| 组件 | `OverlayListPopup(alignment = PopupPositionProvider.Align.End)` → `ListPopupColumn` → 逐项 `DropdownImpl` | `[L]popup/OverlayDropdownPopup.kt:90-105`、`[L]basic/ListPopup.kt:79-84` |
| 形态 | **锚定在行上的浮层**（不是 Dialog、不是 BottomSheet、不是滚轮） | 同上 |
| 锚点 | 行下方 **8dp**、**右对齐**；下方放不下就翻到上方 | `[L]basic/ListPopup.kt:236-255` |
| 宽度 | 由最长项决定，夹在 **200dp – 288dp** | `[L]basic/ListPopup.kt:90-102`、`:221` |
| 最大高度 | `maxHeight = null` → 窗口高 − 弹层上下边距；下限 **50dp** | `[L]layout/ListPopupLayout.kt:209-212`、`[L]basic/ListPopup.kt:227` |
| 滚动 | **支持垂直滚动**（13 档一屏放不下也没关系） | `[L]basic/ListPopup.kt:153` |
| 容器 | 圆角 **16dp**、底 `surfaceContainer` **#FFFFFF**、无边框 | `[L]basic/ListPopup.kt:604-605` |
| 每项字号 | 主文字 **16sp / Medium**（`body1`），副标题 **14sp** | `[L]basic/Dropdown.kt:171-182`、`[L]theme/TextStyles.kt:159-163` |
| 每项内边距 | 左右 **20dp**；首项上 / 末项下 **20dp**；中间项上下 **12dp** | `[L]basic/Dropdown.kt:94-105`、`:385-394` |
| 选中态 | 文字 + 对勾 = `primary` **#3482FF**；**底色不变**（`selectedContainerColor` 默认等于 `surfaceContainer`） | `[L]basic/Dropdown.kt:107-118`、`:407-410` |
| 对勾 | `MiuixIcons.Basic.Check` **20dp**，右端 | `[L]basic/Dropdown.kt:370`、`:196-205` |
| 多组之间 | 1.5dp 分隔线，左右 20dp / 上下 4dp | `[L]popup/DropdownEntriesContent.kt:48-54` |

**动画（数值可直接抄）** — 全在 `[L]basic/ListPopup.kt:200-215`：

| 轨道 | 参数 | 出处 |
|---|---|---|
| 缩放 + 揭示比例 | `spring(dampingRatio = 0.82, stiffness = 362.5, visibilityThreshold = 0.0001)` | `:200` |
| 缩放范围 | `scale = 0.15 + 0.85 * fraction`（**0.15 → 1.0**），围绕锚点角 | `:612-614` |
| 内容 alpha 进 / 出 | `tween(200ms)` / `tween(150ms)` | `:203`、`:206` |
| 背景遮罩 dim 进 / 出 | `tween(300ms, SinOutEasing)` / `tween(150ms, SinOutEasing)` | `:209`、`:212` |
| 遮罩色 | `windowDimming` = **#000000 @ 30%** | `[L]theme/Colors.kt:391` |
| 揭示方式 | 从锚点侧沿方向"拉开"（下方弹出→从顶边拉开），形状随圆角 16dp | `[L]basic/ListPopup.kt:623`、`:641-670` |
| 触感 | 展开 `ContextClick`、选中 `Confirm` | `[L]preference/OverlaySpinnerPreference.kt:204`、`[L]popup/OverlayDropdownPopup.kt:81` |

### 4.2 对话框版：标题 + 列表 + 一个通栏按钮

| 项 | 值 | 出处 |
|---|---|---|
| 组件 | `OverlayDialog(title, insideMargin = DpSize(0.dp, 24.dp))` → `LazyColumn{ 逐项 }` + `TextButton` | `[L]popup/OverlayDropdownPopup.kt:177-221` |
| 弹层几何 | 圆角 **32dp**、最宽 **420dp**、外边距 **12dp**、内容内边距 左右 0 / 上下 **24dp** | `[L]layout/DialogContentLayout.kt:451`、`:457`、`:462`、`:467`；`[L]popup/OverlayDropdownPopup.kt:183` |
| 标题 | **18sp / Medium / 居中**，下 12dp（色 `DialogDefaults.titleColor()`） | `[L]layout/DialogContentLayout.kt:391-399`、`[L]theme/TextStyles.kt:226-230` |
| 每项 | 行高 ≥ **56dp**、左右 **28dp**、上下 **12dp**（对话框模式不区分首末） | `[L]basic/Dropdown.kt:364`、`:388`、`:393-394`、`:132-141` |
| 选中态（dialog 配色） | 底 `tertiaryContainer` **#EAF2FF**、文字+对勾 `onTertiaryContainer` **#3482FF** | `[L]basic/Dropdown.kt:421-438`、`[L]theme/Colors.kt:369-370` |
| 按钮 | 通栏、`minHeight = 50dp`、内边距 `start 24 / top 12 / end 24`、底 `secondaryVariant` **#F0F0F0**、字 `onSecondaryVariant` **#303030**、圆角 **16dp** | `[L]popup/OverlayDropdownPopup.kt:195-202`、`[L]basic/Button.kt:216-220`、`:169` |
| 滚动 | `LazyColumn`，超出即滚动 | `[L]popup/OverlayDropdownPopup.kt:188` |

---

## 5. 与 `OverlayDropdownPreference` 的区别 + 13 档人格的选型

### 5.1 逐项对比

| 维度 | `OverlayDropdownPreference` | `OverlaySpinnerPreference` |
|---|---|---|
| `items` 类型 | `List<String>`（`[L]preference/OverlayDropdownPreference.kt:59`） | `List<DropdownItem>`（`[L]preference/OverlaySpinnerPreference.kt:61`） |
| 弹层内核 | `OverlayDropdownPopup`（`[L]preference/OverlayDropdownPreference.kt:196-205`） | **同一个** `OverlayDropdownPopup`（`[L]preference/OverlaySpinnerPreference.kt:244-253`） |
| 右侧值文字 | 14sp `body2`、`TextAlign.End`、右距 8dp（`:180-190`） | 同上 **+ `TextOverflow.Ellipsis`**（`:229-238`） |
| 每项可带 icon / summary / 单独禁用 | ✗（只有字符串） | ✓ |
| 对话框模式 | ✗ | ✓（多一个必填 `dialogButtonString`） |
| 收起策略 | 固定 `collapseOnSelection = true`（`:107`） | 弹层版固定 true；`entries` 重载可改（`:170`） |
| 触感 / 动画 / 几何 | 完全一致 | 完全一致 |
| **是不是滚轮** | ✗ | ✗（0.9.4 无任何 Spin 滚轮实现，两个都是列表） |

一句话：**`OverlaySpinnerPreference` = `OverlayDropdownPreference` + DropdownItem 模型 + 省略号 + 一组对话框重载**，弹层长相一模一样。

### 5.2 你要做「人格下拉」13 档 —— 明确推荐

> **用 `OverlayDropdownPreference(items = 13 个 String, selectedIndex, onSelectedIndexChange)`。**

理由（三条，都影响你现在这版工作量）：
1. **数据形状最省事**：人格本来就是一个字符串数组，`List<String>` 直接传；用 Spinner 得写 `map { DropdownItem(text = it) }`，而 `DropdownItem` 的 icon/summary/单条禁用你一个都用不上。
2. **13 档不会溢出**：弹层宽 200–288dp、高上限 = 窗口高，内部**可滚动**（`[L]basic/ListPopup.kt:153`），不需要换组件。按每项约 43dp（16sp 文字 + 12上下）估，13 项约 560dp，会在弹层里滚动，不会顶出屏幕。
3. **交互语义正好对**：点即生效、自动收起、有 `Confirm` 触感。人格切换不像"危险操作"，不需要二次确认。

**什么时候改用 `OverlaySpinnerPreference` 的对话框版**：① 你要给每档人格配一行说明（`summary`）；② 你要在窄屏上把 13 档做成居中大弹层（`OverlayDialog`，宽 ≤420dp）；③ 你以后要禁用其中某几档（`DropdownItem.enabled = false`）。
**什么时候两个都不够**：你要"点确定才切换"——库的两个件都做不到（§3.3），得自己包一层 `OverlayDialog`。

---

## 6. 若**不依赖 miuix-preference** 自己实现一个同款：最小要素清单

（弹层版为准；数值均来自源码，可直接用）

| # | 要素 | 具体值 |
|---|---|---|
| 1 | **弹层类型** | 锚定 `Popup`（相对行定位），锚点 = 行下方 **8dp**、水平 **右对齐**；下方空间不足翻到上方。**不要**做成 Dialog / BottomSheet |
| 2 | **弹层几何** | 宽 **200–288dp**（按最长项测 intrinsic 后夹紧）、圆角 **16dp**、底 `surfaceContainer`、最高 = 窗口高 − 上下边距、内部可垂直滚动 |
| 3 | **条目样式** | 行高 = 文字 + 上下内边距；主文字 **16sp Medium**、副标题 14sp；左右 **20dp**，首项上 / 末项下 **20dp**、中间项 **12dp** |
| 4 | **选中态** | 文字 + 右侧 **20dp** 对勾 = `primary`，**底色不变**；未选中对勾占位（透明）保证不跳动 |
| 5 | **进场动画** | 缩放 **0.15 → 1.0**（`spring(damping=0.82, stiffness=362.5)`）+ 内容 alpha **0 → 1 / 200ms** + 从锚点侧沿方向"拉开"（clip-reveal，圆角 16dp） |
| 6 | **退场动画** | 缩放 1.0 → 0.15（同 spring）、alpha **1 → 0 / 150ms**（alpha 是主时钟，走完立即卸载） |
| 7 | **遮罩** | 全屏 `#000000 30%`，进 **300ms SinOut** / 出 **150ms**；点遮罩任意位置关闭 |
| 8 | **交互** | 点行展开并发 `ContextClick` 触感；点项回传 index → 发 `Confirm` 触感 → 自动关闭；返回键关闭；行在展开期间保持"按住"外观（`holdDownState = true`，即行本身也带一层 10% 叠层） |
| 9 | **与行的耦合** | 行右侧显示当前值：**14sp**、`TextAlign.End`、右距 8dp、色 `onSurfaceVariantActions`；值后面跟 **10×16dp** 上下箭头 `ArrowUpDown` |
| 10 | **受控状态** | 只暴露 `(items, selectedIndex, onSelectedIndexChange)`；展开态内部持有（Spinner 弹层版用 `rememberSaveable` 存展开态，`[L]preference/OverlaySpinnerPreference.kt:174`） |

---

## 7. 未确证 / 期望落差

1. **不是滚轮**：需求书 G3 问的是"Spin 滚轮？列表？"——0.9.4 的答案是**列表**，库内没有任何 wheel/picker 实现（`preference/` 下 10 个文件里没有 Spinner 样式的滚轮）。你若真想要 MIUI 那种滚轮手感，得自己写（或用 `NumberPicker`，那是另一套轴）。
2. **真机视觉**：本机无 SukiSU 运行环境，本篇的"长相"全部由 `ListPopupLayout` / `DropdownImpl` 的测量与绘制代码推导，**未截图核**。
3. **对话框版的 SukiSU 先例**：0 个（SukiSU 没接过 Spinner），所以"和系统设置页配色一致"这类判断没有实物可对。
4. **13 项约 560dp**：按 16sp 文字自然行高 + 24dp 内边距估算，非实测。

---

## 8. 自查（需求书 §6 五条，逐条过）

- [x] **每个动画参数都给了数值**：缩放 0.15→1.0、`spring(0.82, 362.5)`、alpha 200ms/150ms、dim 300ms/150ms、遮罩 30%（§4.1 表）；退场同表。
- [x] **弹层类型点名到具体组件**：弹层版 `OverlayListPopup` + `ListPopupColumn`；对话框版 `OverlayDialog` + `LazyColumn`（§4.1/§4.2）。
- [x] **每条有 `文件:行号`**：全文 **80 处**（`grep -oE ":[0-9]{2,4}"` 命中 80）。
- [x] **没贴大段源码**：最长引用 2 行（示例骨架 10 行是**调用**不是库实现，且不含任何库内函数体）。
- [x] **上一批已确认的不重复写**：不重复 90 §7 的行级尺寸，只在 §4 给"弹层"这一层（90 没写）；行本身的规格指回 40-设置 §3。
