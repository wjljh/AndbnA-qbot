# SukiSU Ultra v4.2.1 · Miuix UI 换皮规格书（→ andbna-panel）

> 目标：让另一个工程师照着本文，就能把 andbna 的 Compose 界面改成 SukiSU v4.2.1 的观感。本文只描述**可复制的取值、层级、参数**，不写业务代码。
>
> ## 出处约定（先读这段）
> - `ui/...:123` —— 指 `SukiSU-v421/manager/app/src/main/java/com/sukisu/ultra/` 下的文件与行号（SukiSU 源码 v4.2.1，**第一优先证据**）。
> - `miuix-ui-0.9.4/Xxx.kt:123` —— 指官方库 `top.yukonga.miuix.kmp:miuix-ui:0.9.4` 的 **sources jar**（`https://repo1.maven.org/maven2/top/yukonga/miuix/kmp/miuix-ui/0.9.4/miuix-ui-0.9.4-sources.jar`）内 `commonMain/top/yukonga/miuix/kmp/basic/Xxx.kt`。库内部常量（底栏 64dp、顶栏 52dp 这类）只能在这里确证。
> - **【推算】** = 源码没有显式常量、由代码结构推出；**【未确证】** = 没跑模拟器/没读到定值，别当铁律。
> - 结论冲突时以 SukiSU 源码为准；本文顺手纠正了 `交付说明.md` 里两条与 0.9.4 源码不符的说法（§7.3）。

---

## 1. 主题与色板

### 1.1 主题是怎么搭起来的

SukiSU 有**两套 UI 实现**，靠 `UiMode`（Miuix / Material）二选一，入口 `KernelSUTheme` 按 mode 分派：`ui/theme/Theme.kt:110-120`。本次只关心 Miuix 分支。

1. `ColorMode` 是 7 档枚举 `SYSTEM/LIGHT/DARK/MONET_SYSTEM/MONET_LIGHT/MONET_DARK/DARK_AMOLED`，并派生 `isDark/isAmoled/isMonet`：`ui/theme/Theme.kt:14-30`。它把「是否用系统动态色」与「亮/暗」拆成两个正交维度。
2. `isInDarkTheme()` 是自建 Composable（不是 `isSystemInDarkTheme()` 单值）：1/4 → false，2/5/6 → true，其余跟随系统：`ui/theme/Theme.kt:123-131`；状态放在 `LocalColorMode`：`ui/theme/Theme.kt:133`。
3. `MiuixKernelSUTheme` 组装 **miuix 自己的 ThemeController** 后 `MiuixTheme(controller = ...)`：
   - `ColorMode` → miuix `ColorSchemeMode`：`ui/theme/MiuixTheme.kt:58-66`
   - `ThemePaletteStyle` / `ThemeColorSpec` 按名字映射，失败回落 `TonalSpot` / `Spec2021`：`ui/theme/MiuixTheme.kt:34-44`
   - `keyColor` 三级回落：设置 keyColor → 系统动态色 primary(S+) → `FallbackSeedColor` → null：`ui/theme/MiuixTheme.kt:46-56`；构造在 `:67-71`
4. `MiuixTheme` 内部再补两件事：状态栏图标亮暗、**`LocalContentColor provides MiuixTheme.colorScheme.onBackground`**：`ui/theme/MiuixTheme.kt:76-88`。
5. 与 Material 的桥接**不在 Miuix 侧**：Material 是另一棵树（`ui/theme/MaterialTheme.kt`），两侧不互相派生。**这是与 andbna 最大的结构差异**（andbna 在 MiuixTheme 里再包一层 MaterialTheme 并从 Miuix 色板派生，`ui/Theme.kt:53-88`）。要 100% 贴合，andbna 的桥接层应逐步退场，页面直接读 `MiuixTheme.colorScheme`。

### 1.2 亮/暗 surface / card / onSurface / primary 的取值与派生公式

**SukiSU 没有硬编码这些值**，全部来自 miuix 用 keyColor 的 monet 派生：

- `ThemeController(keyColor, dark, paletteStyle, colorSpec)`，keyColor 非空走 `colorsFromSeed(seed, colorSpec, paletteStyle, dark)`；为 null 回落库内静态亮/暗色板：`miuix-ui-0.9.4/ThemeController.kt:217-260`。
- monet role → miuix 语义名的映射（唯一能写进规格的「公式」）：`miuix-ui-0.9.4/MonetMapping.kt`
  - `surface = roles.surface`（:136）、`onSurface = roles.onSurface`（:137）
  - `onSurfaceVariantSummary = roles.onSurfaceVariant`（:140，次要文字色）
  - `surfaceContainer = roles.surfaceContainer`（:143）→ **卡片底色**；`surfaceContainerHigh/Highest`（:146/:148）
  - `dividerLine = roles.outlineVariant`（:151）；`primary = roles.primary`（:103）；`secondaryContainer = roles.secondaryContainer`（:126）

结论：**页面底 = `surface`、卡片底 = `surfaceContainer`（miuix `Card` 默认色，`miuix-ui-0.9.4/Card.kt:201-203`）、主文字 = `onSurface`、次要文字 = `onSurfaceVariantSummary`、强调 = `primary`。** 具体 hex **【未确证】**（运行时由 seed 生成；要定值只能先固定 seed，比如 andbna 的 `Tokens.Pink`）。

- AMOLED 只对 **Material** 分支生效（surface 系列全置黑）：`ui/theme/ThemeExt.kt:16-27`；Miuix 分支是把 ColorMode 降级成 MONET_DARK：`ui/theme/MiuixTheme.kt:65`。
- Material 侧形状/字号（`ui/theme/Shape.kt:8-14`：8/12/18/24/32dp；`ui/theme/Type.kt:9-17`：bodyLarge 16sp/24sp/0.5sp）**与 Miuix 分支无关**，别抄进 Miuix 换皮。

### 1.3 hero 卡工作中（绿）与空闲态的色值来源

hero 卡 = 首页「Working」大卡，函数 `StatusCard`（`ui/screen/home/HomeMiuix.kt:245`）。底色三分支，**优先级 动态色 > 暗 > 亮**（`ui/screen/home/HomeMiuix.kt:275-281`）：

| 条件 | 底色 | 出处 |
|---|---|---|
| `MiuixTheme.isDynamicColor` | `colorScheme.secondaryContainer` | `ui/screen/home/HomeMiuix.kt:277` |
| 暗色 | `Color(0xFF1A3825)` 深绿 | `ui/screen/home/HomeMiuix.kt:278` |
| 亮色 | `Color(0xFFDFFAE4)` 浅绿 | `ui/screen/home/HomeMiuix.kt:279` |

配套：

- 大对勾 tint：动态色 → `primary.copy(alpha = 0.8f)`，否则 `Color(0xFF36D167)`：`ui/screen/home/HomeMiuix.kt:300-304`
- 「Custom LKM」徽标（`StatusTag`）：内容色 动态 `onTertiaryContainer` / 暗 `0xFFB8E8C5` / 亮 `0xFF164A29`；背景 动态 `tertiaryContainer` / 暗 `0xFF315D3E` / 亮 `0xFFB8E8C5`：`ui/screen/home/HomeMiuix.kt:346-363`
- **空闲/未安装态没有专属底色**：走 `Card` 默认（= `surfaceContainer`），连 `colors` 都没传：`ui/screen/home/HomeMiuix.kt:410-431`。所以「工作 = 绿卡、空闲 = 中性卡」是唯一语义对比，**不要**给空闲态染色。

---
## 2. 首页骨架

### 2.1 页面外边距与卡片间距

- 滚动容器 `LazyColumn`，**水平 12dp 直接加在 LazyColumn 上**（不是每张卡各加）：`ui/screen/home/HomeMiuix.kt:117-123`
- `contentPadding = innerPadding`（Scaffold 给的，含顶栏高度）：`ui/screen/home/HomeMiuix.kt:124`
- 唯一 item 内 `Column(padding(top = 12dp), verticalArrangement = Arrangement.spacedBy(12dp))`：`ui/screen/home/HomeMiuix.kt:128-132`
  → **页左右 12dp / 页首额外 12dp / 卡间距 12dp**，三处都是 12dp，这是 SukiSU 的节奏基线。
- 列表尾部不是 padding 而是 **Spacer**，高 = Scaffold 底部 innerPadding（非全功能构建再补导航栏 inset）：`ui/screen/home/HomeMiuix.kt:179-184`

### 2.2 hero 卡（StatusCard）

照抄要点（`ui/screen/home/HomeMiuix.kt:267-369`）：

- 外层 `Row(fillMaxWidth().height(IntrinsicSize.Min))`，内层 `Card(Modifier.fillMaxWidth(), ...)`：`:267-274`
- **卡片样式参数一个都没覆盖**：只传 `colors`、`onClick`、`showIndication = !isLateLoadMode`、`pressFeedbackType = PressFeedbackType.Tilt`：`:275-288`
  → 圆角 **16dp**（`miuix-ui-0.9.4/Card.kt:190`）、内沿 **0dp**（`miuix-ui-0.9.4/Card.kt:195` `InsideMargin = PaddingValues(0.dp)`）。
- **高度无显式值**：靠内容撑。撑高关键在于一个 **110dp** 对勾图标，放在 `fillMaxSize().offset(27.dp, 31.dp)` 的 Box、`Alignment.BottomEnd`：`:290-307`
  → **【推算】卡高 ≈ 110dp**（`offset` 不影响测量，只影响摆放）。这是本文最需要实测复核的数字。
- 卡内三块内容都用 `Box(fillMaxSize().padding(...))` + 对齐：
  - 工作模式文本（`"Built-in"`/`"LKM"`）：`padding(16.dp, 10.dp)`、BottomStart、**16sp Medium**：`:308-320`；`"Built-in"` 是字符串字面量 `:260-264`
  - 主标题：`padding(16.dp, 14.dp)`、TopStart、**22sp SemiBold**：`:322-333`
  - 主标题下 1dp 空隙 + 版本行 **15sp**：`:334-343`
- **「Built-in」徽标不是 Badge**，就是 16sp Medium 文本放卡左下；真徽标用 `StatusTag`（圆角 6dp、内边距 h4/v2、9sp、`FontWeight(750)`）：`ui/component/statustag/StatusTagMiuix.kt:22-37`

### 2.3 信息行（InfoCard 内的 InfoText）

`InfoCard` 私有 `InfoText`（`ui/screen/home/HomeMiuix.kt:480-516`），**没有分隔线**：

- 行容器 `Row(fillMaxWidth().padding(bottom = bottomPadding), CenterVertically)`，**默认 `bottomPadding = 24.dp`，最后一行传 `0.dp`**：`:485-491`、`:594`、`:609`
  → 行距来源是 24dp 下填充，不是 divider。**【推算】** 单行内容高 ≈ 17sp*1.2 + 2dp + 14sp*1.2 ≈ 39dp，行节距 ≈ 63dp（未实测）。
- 图标 `24.dp`、右间距 `12.dp`、tint = `onSurface`：`:493-499`
- 标题字号取 `MiuixTheme.textStyles.headline1.fontSize`（= **17sp**，`miuix-ui-0.9.4/TextStyles.kt:189-192`），Medium，色 `onSurface`：`:502-507`
- 值字号取 `body2.fontSize`（= **14sp**，`miuix-ui-0.9.4/TextStyles.kt:165-168`），色 `onSurfaceVariantSummary`，上间距 2dp：`:508-513`
- 卡片内沿 `Card { Column(Modifier.padding(16.dp)) }` → **四边 16dp**：`:544-545`；分组卡之间 12dp：`:540-543`

> 「分隔线内缩量」：**SukiSU 首页信息行不用分隔线**（改用 24dp 下间距）。库内分隔线的实际用法在别处：`HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = outline.copy(alpha = 0.5f))`（`ui/screen/module/ModuleMiuix.kt:916-919`、`ui/screen/kpm/KpmMiuix.kt:491-494`），弹层里是 `padding(horizontal = 20.dp, vertical = 4.dp)` + `1.5.dp`（`ui/screen/superuser/SuperUserMiuix.kt:175-178`）。andbna 的 `RowDivider`（`ui/StatusPage.kt:290-298`：`start = 36.dp`、0.6dp、`onSurfaceVariantSummary@15%`）是它自己的发明。

### 2.4 分组卡 / SectionCard

SukiSU **没有自定义 SectionCard**：就是裸 `Card { Column(padding(16.dp)) { ... } }`（`ui/screen/home/HomeMiuix.kt:544-545`、`:598-599`），组间靠外层 `spacedBy(12.dp)`（`:542`）。行间「gap」完全由行自身 `padding(bottom=...)` 决定（§2.3）。andbna 的 `SectionCard(metrics, gap=...)`（`ui/Components.kt:117-160`）要模拟这层就应把 `gap` 传 0 或用行内 padding，否则每行多出 10dp。

带箭头的跳转卡用 `Card { ArrowPreference(...) }`（`ui/screen/home/HomeMiuix.kt:444-471`，import `:86`）——依赖 `miuix-preference`，andbna 目前没有。

---

## 3. 顶栏（大标题滚动折叠）

SukiSU 顶栏 = `BlurredBar { TopAppBar(...) }`：

- `scrollBehavior` 在页面顶层创建一次：**`val scrollBehavior = MiuixScrollBehavior()`**，`ui/screen/home/HomeMiuix.kt:100`
  - 签名 `MiuixScrollBehavior(state = rememberTopAppBarState(), canScroll = { true }, snapAnimationSpec = spring(stiffness = 2500f), flingAnimationSpec = rememberSplineBasedDecay())`，返回 `ExitUntilCollapsedScrollBehavior`：`miuix-ui-0.9.4/TopAppBar.kt:250-258`、`:474`
  - **它是 `@Composable`，不能塞进 `remember{}`**（andbna 已有此注释：`ui/App.kt:132-133`）
- 顶栏本体只传 4 个参数：`TopAppBar(color = barColor, title = ..., actions = { RebootListPopupMiuix() }, scrollBehavior = scrollBehavior)`：`ui/screen/home/HomeMiuix.kt:233-240`
  - **没有传 `largeTitle`** → 大标题能出现是因为库签名 `largeTitle: String = title`：`miuix-ui-0.9.4/TopAppBar.kt:105`
  - 即：**「大标题折叠」= 给了 `scrollBehavior` 就自动有**。andbna 显式传 `largeTitle`（`ui/App.kt:146`）等价。
- 折叠几何与阈值：
  - 收起后高 **52dp**：`TopAppBarDefaults.CollapsedHeight`（`miuix-ui-0.9.4/TopAppBar.kt:409`）
  - 大标题水平内沿 **26dp**、action 图标内沿 16dp：`TitlePadding` / `ActionIconPadding`（`miuix-ui-0.9.4/TopAppBar.kt:400-406`）
  - 大标题下留白 4dp（无 subtitle）/ 8dp（有 subtitle）：`miuix-ui-0.9.4/TopAppBar.kt:415`、`:418`
  - 触发逻辑 `ExitUntilCollapsedScrollBehavior`：内容一上滚就收、滚回顶才展（实现在 `miuix-ui-0.9.4/TopAppBar.kt:474` 起的 private 类；大标题透明度由 `largeTitleAlpha` 驱动 `:647`、`:750`）
- 顶栏底色：**无毛玻璃 → `surface`，有毛玻璃 → `Transparent`**：`ui/screen/home/HomeMiuix.kt:104`
- 把滚动事件接到顶栏：列表上的 `.nestedScroll(scrollBehavior.nestedScrollConnection)`：`ui/screen/home/HomeMiuix.kt:122`
- 二级页改 **`SmallTopAppBar`**（无大标题）+ 返回键，且**标题色/栏底色随滚动进度插值**：`titleColor = onSurface.copy(alpha = ((scrollProgress - 0.35f)/0.65f).coerceIn(0f,1f))`、`barColor` 透明↔`surface`：`ui/screen/about/AboutMiuix.kt:113-128`；返回键 `MiuixIcons.Back` 且 RTL 时 `scaleX = -1f`：`ui/screen/about/AboutMiuix.kt:130-142`

---

## 4. 底栏（`ui/component/bottombar/BottomBarMiuix.kt`）

### 4.1 停靠式 NavigationBar 分支 —— 完整参数（本次实施的就是这个）

外层判断 `if (!enableFloatingBottomBar) { BlurredBar(blurBackdrop) { NavigationBar(...) } }`：`ui/component/bottombar/BottomBarMiuix.kt:63-83`

`NavigationBar` 实际传入的命名参数**只有 3 个**：

| 参数 | 传值 | 出处 |
|---|---|---|
| `modifier` | 外部传入的 `modifier`（`MainActivity` 里是 `Modifier.align(Alignment.BottomCenter)`，`ui/MainActivity.kt:478-483`） | `ui/component/bottombar/BottomBarMiuix.kt:66` |
| `color` | 毛玻璃可用 → `Color.Transparent`，否则 `MiuixTheme.colorScheme.surface` | `ui/component/bottombar/BottomBarMiuix.kt:67` |
| `content` | `items.forEachIndexed { ... NavigationBarItem(...) }` | `ui/component/bottombar/BottomBarMiuix.kt:68-81` |

未传而取库默认：`showDivider = true`（栏顶一条 `HorizontalDivider`）、`defaultWindowInsetsPadding = true`（自己补导航栏 inset 空白）、`mode = NavigationBarDisplayMode.IconAndText`：`miuix-ui-0.9.4/NavigationBar.kt:74-80`、`:95-97`、`:109-127`。

`NavigationBarItem` 的每个命名参数（`ui/component/bottombar/BottomBarMiuix.kt:70-79`）：

| 参数 | 传值 |
|---|---|
| `modifier` | `Modifier.weight(1f)`（冗余：库内部已 `.weight(1f)`，`miuix-ui-0.9.4/NavigationBar.kt:166`） |
| `icon` | `item.icon`（`ImageVector`，来自 `BottomBarDestination` 枚举 `:135-143`） |
| `label` | `item.label`（`stringResource`） |
| `selected` | `mainState.selectedPage == index` |
| `onClick` | `mainState.animateToPage(index)` |
| `badge` | `navigationBadgeFor(index, navigationBadge)`（可为 null） |
| 未传 | `enabled = true`、`colors = NavigationBarDefaults.navigationBarItemColors()` |

库侧几何（要写死就抄这些）：item 高 **64dp**、图标 **26dp**、label **12sp**、图标上内边距 **8dp**、label 下内边距 **8dp**：`miuix-ui-0.9.4/NavigationBar.kt:440-454`。

### 4.2 「选中态胶囊高亮」由什么产生 —— **结论：miuix 0.9.4 的停靠栏没有胶囊**

- 0.9.4 的 `NavigationBarItem` 选中态只做两件事：**颜色**（选中 = `onSurfaceContainer` 原样，未选中 = `onSurfaceContainer.copy(alpha * 0.4f)`，`miuix-ui-0.9.4/NavigationBar.kt:463`、`:496-506`）与**字重 Bold**（`:160`）。整段 `Column{ NavigationItemIcon(...); Text(...) }` **没有任何 background / clip / indicator**：`miuix-ui-0.9.4/NavigationBar.kt:163-200`。
- 参数层也没有开关：签名只有 `selected/onClick/icon/label/modifier/enabled/colors/badge`：`miuix-ui-0.9.4/NavigationBar.kt:145-154`。SukiSU 也确认没传任何额外东西（§4.1）。
- **要胶囊只能自制**：SukiSU 唯一带胶囊的是液态玻璃分支、自己画的 —— `clip(CircleShape).background(accentColor.copy(alpha = 0.15f), pillShape)`，`pillShape = CircleShape`，`accentColor` 默认 `colorScheme.primary`：`ui/component/FloatingBottomBar.kt:219`、`:490`、`:213`。
- ⚠️ 因此「SukiSU 停靠栏选中项有胶囊」不成立（`交付说明.md:56` 的说法源自液态玻璃分支）。andbna 若要胶囊感 = 新增自制项，不在本次抄写范围。

### 4.3 badge 写法

- 停靠栏：把 `badge = {...}` 作为 `NavigationBarItem` 参数传入（`ui/component/bottombar/BottomBarMiuix.kt:78`），返回类型显式声明 `(@Composable () -> Unit)?`：`ui/component/bottombar/BottomBarMiuix.kt:145-170`
- 内容就是 miuix `Badge`：`Badge { Text(count.toString()) }`；要强调色显式传色：`Badge(containerColor = if (floating) primaryContainer else primary, contentColor = if (floating) onPrimaryContainer else onPrimary) { Text(count.toString()) }`：`ui/component/bottombar/BottomBarMiuix.kt:160-169`（`Badge` 的 `containerColor/contentColor` 见 `miuix-ui-0.9.4/Badge.kt:133-136`）
- 浮动分支要多包 `BadgedBox(badge = { badge() }) { icon() }`：`ui/component/bottombar/BottomBarMiuix.kt:116-120`
- 语义分档（产品逻辑）：超管数量 → Accent；模块可更新 → Alert；模块已启用 → Accent：`ui/component/bottombar/BottomBar.kt:94-105`

### 4.4 FloatingBottomBar（液态玻璃）分支的启用条件 —— 本次**不实施**

- 开关来源：设置项 → `LocalEnableFloatingBottomBar`：`ui/MainActivity.kt:183`；CompositionLocal 默认 `false`：`ui/theme/Theme.kt:137`；读取 `ui/component/bottombar/BottomBarMiuix.kt:54`、判断 `:63`、`:84`
- 另两个条件：毛玻璃开关 `LocalEnableFloatingBottomBarBlur`（`ui/theme/Theme.kt:139`、`ui/component/bottombar/BottomBarMiuix.kt:55`）；**只有全功能构建才显示底栏**（`BottomBarMiuix.kt:50-51`）；屏宽够时被侧边 NavigationRail 取代（`ui/component/bottombar/BottomBar.kt:108-110`、`ui/MainActivity.kt:305`、`:439`）
- 几何（仅记录）：外框 `height(64.dp).padding(4.dp)`（`ui/component/FloatingBottomBar.kt:388-389`）；胶囊条 `height(56.dp)`（`:424`、`:478`、`:491`）；容器色 `surfaceContainer.copy(alpha = 0.4f)`（毛玻璃开）`:220`；左右外边距 `padding(start = 28.dp, end = 28.dp, bottom = 8dp+inset 或 28dp)`：`ui/component/bottombar/BottomBarMiuix.kt:85-92`
- 一句话：它是官方 `FloatingNavigationBar` 的「带文字」替身 —— 官方 0.9.4 的 `FloatingNavigationBarItem` 虽收 `label`，但只当 `contentDescription`、**不渲染文字**：`miuix-ui-0.9.4/NavigationBar.kt:378-386`、`:412-423`。所以 SukiSU 自己抄了一份（文件头注明 `Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar)`，`ui/component/FloatingBottomBar.kt:1`）。

---
## 5. 页面容器与二级页

### 5.1 滚动容器惯例

首页/二级页统一用 `LazyColumn` + 四个固定修饰符 + `overscrollEffect = null`：

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxHeight()
        .scrollEndHaptic()
        .overScrollVertical()
        .nestedScroll(scrollBehavior.nestedScrollConnection)
        .padding(horizontal = 12.dp),
    contentPadding = innerPadding,
    overscrollEffect = null,
)
```

出处：`ui/screen/home/HomeMiuix.kt:117-126`（import `:91-92`）。二级页用 `fillMaxSize()` + `contentPadding` 只给 top/start/end：`ui/screen/about/AboutMiuix.kt:368-380`。

### 5.2 Scaffold 与 bottomBar 的高度关系 —— **核对结果：miuix 的 Scaffold 会把 bottomBar 高度算进内容 padding**

- SukiSU 用法：`Scaffold(bottomBar = bottomBar) { innerPadding -> pagerContent(innerPadding.calculateBottomPadding()) }`：`ui/MainActivity.kt:495-497`（Material 分支同形 `:488-493`）。这个 `Dp` 一路传给各页面（`ui/screen/home/HomeScreen.kt:36-39`、`:88-92`），首页把它做成**列表末尾 Spacer**：`ui/screen/home/HomeMiuix.kt:179-184`。
- 库实现（确证）：写内边距时 `bottom = if (isBottomBarEmpty) insets.calculateBottomPadding() else bottomBarPlaceable.height.toDp()`：`miuix-ui-0.9.4/Scaffold.kt:325-340`。**即 bottomBar 存在时，content padding 的 bottom 就是 bottomBar 实测高度（含它自己补的导航栏 inset 空白，`miuix-ui-0.9.4/NavigationBar.kt:109-127`）。**
- 所以 **「Miuix Scaffold 不把 bottomBar 算进内容 padding」是错的**（`交付说明.md:87` 与 `ui/Components.kt:106-108` 那句注释）。andbna 现在是**双重留白**：`ui/App.kt:172-176` 已 `.padding(pad)`，`ui/Components.kt:108` 又加 `bottom = 96.dp` → 底部多出约 96dp 空白。**换皮第一步就删掉这个 96dp**，只保留 Scaffold 的 pad。
- 顶栏侧同理：`contentPadding = innerPadding` 已含顶栏高度，首页再额外 `top = 12.dp`（`ui/screen/home/HomeMiuix.kt:129`）→ 「顶栏下方视觉间距 = 12dp」。
- SukiSU 还给 Scaffold 显式收窄横向 insets：`contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)`（`ui/screen/home/HomeMiuix.kt:114`），并关掉默认 popup host（`:113`）。andbna 没传 `contentWindowInsets`（`ui/App.kt:141-172`），需要补这一行。

### 5.3 二级页：进入 / 返回样式

- 页面栈是 miuix nav3：`NavDisplay(backStack = navigator.backStack, effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()), onBack = { navigator.pop() }) { entry<Route.X>(swipeDismiss = ...) { ... } }`：`ui/MainActivity.kt:211-228`
  - **圆角裁剪半径取系统值**：`NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius())`：`ui/MainActivity.kt:214`
  - **侧滑返回**：`swipeDismiss = NavSwipeDirection.LeftToRight / RightToLeft（RTL 翻转）/ None`，由设置 `enableSwipeDismiss` 控制：`ui/MainActivity.kt:192-200`；个别页可动态禁用（刷机页 `flashBackEnabled`）：`:238-245`
- 二级页骨架：`Scaffold(topBar = { SmallTopAppBar(...) }, contentWindowInsets = 横向 only)`：`ui/screen/about/AboutMiuix.kt:119-149`；内容底部再补 `innerPadding.calculateBottomPadding() + 12.dp`：`ui/screen/about/AboutMiuix.kt:403-408`
- andbna 现状：二级页是 `if (showModePicker) { ModePickScreen(...); return }` 的**分支替换**（`ui/App.kt:84-97`、`:100-103`），没有栈、没有侧滑、没有圆角裁剪。要对齐需引入 `miuix-nav`（§7.1）。

---

## 6. 令牌对照表

> 左列 = SukiSU 取值/来源（带出处）；右列 = 建议 andbna 写法（**现有 `Metrics` 字段**或**新增字段**）。`Metrics` 定义在 `ui/Components.kt:57-88`，现有字段：`compact/wide/pageGap/cardGap/innerGap/radiusCard/titleSize/contentMax`。

| # | SukiSU 令牌 / 取值（出处） | 建议 andbna 写法 |
|---|---|---|
| 1 | **卡片圆角 16dp**：SukiSU 的 Card **全部**没传 `cornerRadius`，一律吃 `CardDefaults.CornerRadius`（`ui/screen/home/HomeMiuix.kt:273`、`:444`、`:544`、`:598` + `miuix-ui-0.9.4/Card.kt:190`） | `metrics.radiusCard` 由 22/26dp **改成 16dp**（一处覆盖全站卡片，hero 与分组卡同一个值）；如想保留大小差异再拆 `radiusHero`，但没有 SukiSU 依据 |
| 2 | **hero 卡高 ≈110dp【推算】**：无显式高度，由 110dp 图标撑起（`ui/screen/home/HomeMiuix.kt:298`） | 新增 `heroHeight = 110.dp`；`Card(Modifier.heightIn(min = metrics.heroHeight))`（现由文字撑，约 90dp） |
| 3 | **hero 内沿 0dp**（`miuix-ui-0.9.4/Card.kt:195`），内文自己给 `padding(16,14)` / `(16,10)`（`ui/screen/home/HomeMiuix.kt:312`、`:325`） | `insideMargin = PaddingValues(0.dp)` + 内容 `Box.padding(16,14)`；现为 16/20dp（`ui/StatusPage.kt:207-210`） |
| 4 | **hero 底色（工作中）** 亮 `#DFFAE4` / 暗 `#1A3825` / 动态 `secondaryContainer`（`ui/screen/home/HomeMiuix.kt:275-281`），图标 tint `#36D167`（`:303`） | 新增 `Tokens.OkSurfaceLight/OkSurfaceDark/OkIcon`；改成「实色整卡染色」，不是现在的 `tint.copy(alpha = 0.16f)`（`ui/StatusPage.kt:212`，层次偏淡） |
| 5 | **hero 主标题 22sp SemiBold**（`ui/screen/home/HomeMiuix.kt:329-333`）、版本行 **15sp**（`:342`） | 复用 `metrics.titleSize`（常态 22sp）+ 新增 `heroValueSize = 15.sp`（现 12sp/11sp） |
| 6 | **hero 徽标（Built-in）= 16sp Medium 文本**于卡左下 `padding(16,10)`（`ui/screen/home/HomeMiuix.kt:308-320`）；真徽标 `StatusTag`：圆角 6dp、内边距 h4/v2、9sp、weight 750（`ui/component/statustag/StatusTagMiuix.kt:22-37`） | 新增 `tagRadius = 6.dp`、`tagFontSize = 9.sp`；andbna 的 `Pill` 是 `RoundedCornerShape(999.dp)`+13sp（`ui/Components.kt:220-231`），语义不同别混用 |
| 7 | **信息行图标 24dp + 右间距 12dp**（`ui/screen/home/HomeMiuix.kt:493-499`） | 新增 `rowIcon = 24.dp`（现 22dp，`ui/StatusPage.kt:268`） |
| 8 | **行高/行内距：无分隔线，行下 `padding(bottom = 24.dp)`，末行 0dp**（`ui/screen/home/HomeMiuix.kt:485-491`、`:594`） | 新增 `rowGap = 24.dp`，`InfoRow` 用 `padding(bottom = metrics.rowGap)`；若保留 `RowDivider`（`ui/StatusPage.kt:290-298`）则 `rowGap` 降到 ~2dp，二者不要叠加 |
| 9 | **行标题 17sp Medium / 值 14sp**，色 `onSurface` / `onSurfaceVariantSummary`，值上间距 2dp（`ui/screen/home/HomeMiuix.kt:504`、`:508-513`；字号来自 `miuix-ui-0.9.4/TextStyles.kt:189-192`、`:165-168`） | 新增 `rowTitleSize = 17.sp`、`rowValueSize = 14.sp`（现 15sp/12sp） |
| 10 | **正文/次要文字色**：`onSurface` / `onSurfaceVariantSummary`（= `roles.onSurfaceVariant`，`miuix-ui-0.9.4/MonetMapping.kt:137`、`:140`） | `KeyValue`/`InfoRow` 直接读 `MiuixTheme.colorScheme.*`，逐步去掉 `MaterialTheme.colorScheme.onSurfaceVariant`（现两套混用：`ui/Components.kt:184`、`:193`） |
| 11 | **卡片间距 12dp**（`ui/screen/home/HomeMiuix.kt:131`、`:542`） | `metrics.cardGap` 已是 12dp（`ui/Components.kt:81`）→ **不动** |
| 12 | **页左右边距 12dp，页首额外 12dp**（`ui/screen/home/HomeMiuix.kt:123`、`:129`） | `metrics.pageGap` 现为 12/16/24（`ui/Components.kt:80`）→ 常态改 12dp；`PageColumn` 的 `padding(top = metrics.cardGap)` 保留 |
| 13 | **列表尾部 = Scaffold innerPadding 的 bottom**（`ui/screen/home/HomeMiuix.kt:179-184`） | 删掉 `PageColumn` 的 `bottom = 96.dp`（`ui/Components.kt:108`），改用 `pad.calculateBottomPadding()` 一处 |
| 14 | **底栏高度**：item 64dp + 导航栏 inset（`miuix-ui-0.9.4/NavigationBar.kt:442`、`:109-127`）；栏色 `surface`，栏顶默认有分隔线（`ui/component/bottombar/BottomBarMiuix.kt:67` + `miuix-ui-0.9.4/NavigationBar.kt:95-97`） | 不需新字段（高度由库给）；`NavigationBar(color = MiuixTheme.colorScheme.surface)` 显式传，别用默认 |
| 15 | **选中胶囊**：miuix 0.9.4 **不存在**（`miuix-ui-0.9.4/NavigationBar.kt:145-200`）；选中 = `onSurfaceContainer` + Bold，未选中 alpha 0.4（`:463`、`:496-506`）；唯一胶囊在液态玻璃分支，`CircleShape` + `primary@15%`（`ui/component/FloatingBottomBar.kt:219`、`:490`） | **本次不实施**；若要做：新增 `indicatorShape = RoundedCornerShape(999.dp)`、`indicatorColor = primary.copy(alpha = 0.15f)` 并自制（风险见 §7.2） |
| 16 | **顶栏高度/折叠**：收起后 52dp（`miuix-ui-0.9.4/TopAppBar.kt:409`）、大标题内沿 26dp（`:400`）、大标题下留白 4dp（`:415`）/subtitle 8dp（`:418`）；行为 `ExitUntilCollapsedScrollBehavior`（`:474`）、snap `spring(stiffness=2500f)`（`:250-258`） | 不需新字段；`val scrollBehavior = MiuixScrollBehavior()`（`ui/App.kt:133` 已有）+ `.nestedScroll(...)`（`ui/App.kt:178` 已有） |
| 17 | **顶栏配色随滚动**：无毛玻璃 `surface` / 有毛玻璃 `Transparent`（`ui/screen/home/HomeMiuix.kt:104`）；二级页按 `scrollProgress` 插值（`ui/screen/about/AboutMiuix.kt:113-128`） | 新增 `fun barColor(scrolledFraction: Float)` 小工具（库不给这个开关，属自制） |
| 18 | **Scaffold 横向 insets**：`systemBars + displayCutout` 只取 Horizontal（`ui/screen/home/HomeMiuix.kt:114`） | 在 `ui/App.kt:141` 的 `Scaffold(...)` 补 `contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)` |

---

## 7. 依赖与风险

### 7.1 需要新增哪些 miuix 模块（只按需）

判断依据 = SukiSU 的 import 分布（`grep -rho 'top\.yukonga\.miuix\.kmp\.[a-z]*'`）：`basic` 314、`theme` 78、`icon` 69、`blur` 62、`utils` 46、`preference` 36、`overlay` 25、`nav` 9、`window` 3、`shader` 2。andbna 已接入 `top.yukonga.miuix.kmp:*:0.9.4`（`交付说明.md:24`）。

| 模块 | 本文 §1–§5 是否用到 | 结论 |
|---|---|---|
| `miuix-ui`（`basic`+`theme`+`utils`+`overlay`） | 是（Card/TopAppBar/Scaffold/NavigationBar/Badge/MiuixTheme/MiuixScrollBehavior） | **必需**（已具备） |
| `miuix-icons`（`MiuixIcons` + `.extended.*`） | 是（`MiuixIcons.Back`，`ui/screen/about/AboutMiuix.kt:138`） | **必需**（已具备） |
| `miuix-preference` | 仅「带箭头跳转行」用（`ui/screen/home/HomeMiuix.kt:445-470`） | **可跳过**：andbna 用自制 `SectionCard` + `KeyValue` |
| `miuix-blur` | 仅毛玻璃/液态玻璃（`ui/screen/home/HomeMiuix.kt:102`、`ui/component/bottombar/BottomBarMiuix.kt:64`） | **本次不加**（§4.4 不实施） |
| `miuix-nav` | 仅二级页栈 + 侧滑（`ui/MainActivity.kt:211-228`） | **可暂缓**；要把 `ModePickScreen` 改成真二级页（系统圆角裁剪 + 侧滑返回）时必须加 |

> 说明：`andbna-panel/app` 的构建脚本**不在工作副本里**（该目录只有 `src/` 与 `proguard-rules.pro`），「已接入哪些模块」只能依 `交付说明.md:24` 与源码 import 反推，无法直接核对 `build.gradle.kts`。

### 7.2 miuix 0.9.4 做不到、需要自制的部分

1. **停靠栏选中胶囊** —— 库无参数无实现（`miuix-ui-0.9.4/NavigationBar.kt:145-200`、`:463`）。自制会破坏库内 8dp/8dp 的图标-文字节奏。
2. **带文字的悬浮底栏** —— `FloatingNavigationBarItem` 收 `label` 但只作无障碍描述（`miuix-ui-0.9.4/NavigationBar.kt:378-386`、`:412-423`），SukiSU 整份自绘（`ui/component/FloatingBottomBar.kt:1`）。
3. **顶栏滚动进度驱动的栏底色/标题色** —— 库只给 `scrollBehavior.state`，插值自己写（SukiSU 也是自己算：`ui/screen/about/AboutMiuix.kt:113-128`）。
4. **hero 卡「整卡实色染色 + 超大对角图标」** —— `Card` 只给 `colors.color`（`miuix-ui-0.9.4/Card.kt:201-203`），图标偏移/尺寸全自己写（`ui/screen/home/HomeMiuix.kt:290-307`）。
5. **页尾底部留白** —— 库已给（§5.2）；若 andbna 继续「不用 Scaffold 的 bottomBar 槽、自己在 Box 里叠底栏」，就得自己算。**推荐改成 SukiSU 做法：bottomBar 走 Scaffold 槽。**

### 7.3 顺手纠正的两条既有说法（有证据）

- `交付说明.md:87`「Miuix 的 Scaffold 不把 bottomBar 高度算进内容 padding」→ **错**：`miuix-ui-0.9.4/Scaffold.kt:333-337` 明确 `bottom = bottomBarPlaceable.height.toDp()`。andbna 的 `96.dp`（`ui/Components.kt:108`）因此是**多余留白**。
- `交付说明.md:56`「SukiSU 是悬浮胶囊 + 选中项带胶囊高亮；Miuix 的 FloatingNavigationBar 官方只支持图标」→ **一半对**：官方确实不渲染 label（`miuix-ui-0.9.4/NavigationBar.kt:412-423`），但「胶囊高亮」只存在于 SukiSU 的液态玻璃分支（`ui/component/FloatingBottomBar.kt:490`），**停靠式 NavigationBar 选中态没有胶囊**（`miuix-ui-0.9.4/NavigationBar.kt:163-200`）。

### 7.4 未确证清单（不许当铁律）

1. **hero 卡实际渲染高度**：仅知「无显式高度 + 110dp 图标撑高」（`ui/screen/home/HomeMiuix.kt:298`）。§6 #2 的 110dp 是 **【推算】**，没跑模拟器量过。
2. **信息行实际行高/节距**（§2.3 的 ≈39dp/≈63dp）：由 17sp+2dp+14sp+24dp 推得，字体真实 line-height 未核（`miuix-ui-0.9.4/TextStyles.kt:189-192` 只给 fontSize）。
3. **亮/暗色板具体 hex（surface / surfaceContainer / onSurface / primary）**：运行时由 keyColor 经 monet 生成（`miuix-ui-0.9.4/ThemeController.kt:217-260`），源码里**没有静态值**。要定值只能先固定 seed，再用 `colorsFromSeed` 在目标机取值。
4. **SukiSU 停靠栏是否有意省略胶囊**：只能确证「源码没传任何能产生胶囊的参数、库也不提供」，无法确证是设计决定还是版本回退。
5. **miuix-nav 过渡的具体时长/缓动**：只核对了调用侧（`ui/MainActivity.kt:211-228`）与 `NavDisplayEffects.cornerClipRadius` 默认 0dp（`miuix-nav-0.9.4/NavDisplayEffects.kt:65`），`NavTransitions.kt` 预设数值未逐行核对，故不给数字。
6. **`pressFeedbackType = PressFeedbackType.Tilt` 的实际视觉**（`ui/screen/home/HomeMiuix.kt:288`）：只知调用了，未确证 tilt 角度/时长。
7. **`NavigationBar` 默认分隔线（`showDivider = true`）在毛玻璃开启时的观感**：SukiSU 只把 `color` 换成 `Transparent`（`ui/component/bottombar/BottomBarMiuix.kt:67`），分隔线仍在，是否与 `BlurredBar` 冲突未确证。

---

## 附：换皮执行顺序建议（照抄优先级）

1. 删掉 `PageColumn` 的 `bottom = 96.dp`，`Scaffold` 补 `contentWindowInsets`（§5.2、§6 #13/#18）—— 先止血。
2. `Metrics` 增补 `radiusHero / heroHeight / rowIcon / rowGap / rowTitleSize / rowValueSize / heroValueSize / tagRadius`（§6）。
3. `HeroCard` 改「实色整卡染色 + 22sp 标题 + 左下 16sp 模式文本」（§1.3、§2.2）。
4. `InfoRow` 去 divider、行距 24dp、图标 24dp、字号 17/14（§2.3）。
5. 顶栏确认 `MiuixScrollBehavior()` + `nestedScroll` 已接（§3，现状已满足）。
6. 底栏保持停靠式、无胶囊，只在参数层补齐 `color = surface` 显式传值（§4.1、§6 #14）。