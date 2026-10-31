# 索取（第二批）：SukiSU 的交互与剩余页面规格

> 上一批（00/10/11/90）质量很好，已经吃进代码了。这一批要的是**交互与剩余页面**。
> 交付时间 2026-10-21 ｜ 需求方：Windows 侧 andbnA 面板 App

---

## 0. 三十秒看懂

上一批解决的是"静态长什么样"，这一批要解决**"点下去会发生什么"**：

```
① hero 卡点击后的动画与弹层（最高优先，我要照它做「点一下选工作模式」）
② 缺的 4 份页面规格（超级用户 / 模块 / 设置 / 底部导航）
③ OverlaySpinnerPreference 的用法（人格下拉要用）
④ SukiSU 怎么进二级页、怎么返回
⑤ 角标尺寸、删除按钮位置、悬浮 + 按钮
```

同样：每条带 `文件:行号`，要**结构与数值**，不要整段源码。

---

## 1. 上一批已经落地的部分（不用重复给）

| 已按规格改完 | 依据 |
|---|---|
| `SectionCard`：内边距 16dp、标题 17sp Medium / onBackground | 90 §2.2 |
| `KeyValue`：标签 17sp Medium 左、值 14sp 右、色 onSurfaceVariantActions | 90 §8 |
| `Pill`/`ActionChip`：secondaryContainer + 15sp + 35dp + 18dp 圆角 | 90 §8 |
| `Note`：Notice=tertiaryContainer / Error=#F8E2E2·#310808 + #F72727 | 11 §4.5 |
| `HeroCard` 三态：工作中绿底(#DFFAE4/#1A3825) + 110dp 图标 offset(27,31)；未运行/异常退回 surfaceContainer + 24dp 图标 | 11 §2、10 §4 |

**还没验证**：手机没连上时装的那几版没实测，hero 三态尤其需要眼睛看。这不影响你们这边继续给规格。

---

## 2. 这一批要什么

### G1 ★ hero 卡点击交互与动画（最高优先）

这是我唯一完全没有依据的部分，靠猜做不出来。逐条要：

```
1. 点击 hero 卡之后弹出的是什么？
   - 是 OverlayListPopup / OverlayBottomSheet / OverlayDialog / 还是跳到新页面？
   - 弹层出现的锚点（BottomCenter / TopEnd / 跟随手指）？
2. 弹层里有哪些项？（列出条目文案与顺序，例如「重启」「关机」「进入安全模式」…）
3. 进入/退出动画：
   - 用了什么（AnimatedVisibility / animateFloatAsState / spring / folme）？
   - spring 的 damping 与 stiffness 具体数值？时长多少毫秒？
   - 位移、缩放、alpha 分别从多少到多少？
4. 卡片本身的按压反馈：
   - pressFeedbackType 是 Tilt 还是 Sink？
   - Tilt 的倾斜角度上限、Sink 的缩放比例（0.94？）、弹簧参数？
5. 图标在点击态有没有变化？（旋转 / 缩放 / 换色）
6. 长按有没有独立行为？
7. 点击时是否触发触感（HapticFeedbackType.xxx）？
```

**特别说明**：SukiSU 的 hero 点击是「重启菜单」（`RebootListPopupMiuix`）。我要做的是**同一个交互位置放不同内容**——点击弹出一个列表，里面既能选工作模式（普通/ADB/root/模块），也能做控制（启动/停止/重启）。所以我需要的是**这套交互的骨架与动画参数**，菜单项内容我自己定。

### G2 缺的四份页面规格

按上一次的七项格式（结构/文本/间距/组件/交互/状态取值/图标）：

- `20-超级用户.md`：列表行结构、权限等级控件（`OverlayDropdownPreference`？）、CUSTOM/USER 标签、头像、长按菜单
- `30-模块.md`：模块卡完整结构、**删除按钮的准确位置与样式**、模块标签（META/ZYGISK）、圆形动作按钮、待卸载/待更新态
- `40-设置.md`：三种行的逐项规格（ArrowPreference / SwitchPreference / OverlayDropdownPreference）、分组结构、禁用态
- `50-底部导航.md`：停靠栏完整参数、**角标（Badge）的位置/尺寸/颜色**、选中态、标签字号（11sp 还是 12sp，上次两处说法不一致）

### G3 `OverlaySpinnerPreference` 用法

我要用它做人格下拉。需要：

```
1. 完整函数签名（所有参数名与默认值）
2. 一个最小可用示例（不是整文件，只要调用骨架）
3. items 的数据类型与如何拿到当前选中项
4. 弹层长什么样（Spin 滚轮？列表？）、点确定还是即时生效
5. 它与 OverlayDropdownPreference 的区别（什么时候用哪个）
```

### G4 二级页机制

SukiSU 从列表点进详情页是怎么做的：

```
1. 用的是 miuix-nav 的 NavDisplay 吗？还是自己 state 切换？
2. 二级页顶栏用 SmallTopAppBar 还是 TopAppBar？返回图标是 MiuixIcons.Back 吗？
3. 有没有侧滑返回（swipeDismiss）？用的哪个组件？
4. 进场/退场动画是什么？（滑入方向、时长、曲线）
5. 二级页要不要保留底部导航栏？（我的内容页/Bot 页要进二级菜单看记忆）
```

### G5 三个具体元素

```
1. 底部导航角标：Badge 的位置偏移、尺寸（6dp/16dp？）、文字 9sp？容器色 error？
   数值来源 miuix basic/Badge.kt，SukiSU 的用法在 BottomBarMiuix.kt
2. 「删除/卸载」按钮在模块卡里的准确位置：是靠右单独放，还是和别的动作按钮同一行？
   尺寸、颜色、圆角、内边距各是多少
3. 悬浮「+」按钮（FAB）：模块页/超级用户页有没有？尺寸、位置、阴影、点击动画
```

---

## 3. 交付格式

沿用上次的目录结构，新增三个文件：

```
sukisu-ui-spec/
├── 15-hero交互与动画.md        ★ G1
├── 20-超级用户.md               G2
├── 30-模块.md                   G2
├── 40-设置.md                   G2
├── 50-底部导航.md               G2
├── 60-spinner偏好.md            G3
├── 70-二级页与导航.md           G4
└── 80-角标与按钮.md             G5
```

如果时间紧，**优先 G1**，其次 G2 的 `50-底部导航` 与 `30-模块`。

---

## 4. 许可证

同上次：SukiSU 是 GPL-3.0。要**结构与数值**，不要整段源码。动画参数这类写成"spring(damping = X, stiffness = Y)"即可，不用贴实现。

---

## 5. 我这边的对接点

| 要改的地方 | 文件 |
|---|---|
| hero 点击 + 弹层 + 动画 | `ui/Components.kt`（HeroCard）、`ui/LocalModeScreen.kt`（调用处） |
| 顶栏标题随页变、左右滑动、底部导航角标 | `ui/App.kt` |
| Bot 卡（对应模块页） | `ui/BotsPage.kt` |
| 设置页三种行 | `ui/SettingsPage.kt` |
| 记忆页（内容页重做） | `ui/LocalTextsContent.kt` → 待重做 |
| 二级页 | 目前没有，全是一级 tab；要按 G4 引入 |

---

## 6. 验收标准

拿 G1 举例：我只看 `15-hero交互与动画.md`，不回来问任何人，就能把「点击 hero 弹出一个列表、带进场动画、卡片按压有反馈」做出来——那就是够。

自查：

- [ ] 每个动画参数都给了数值（damping / stiffness / 时长 ms / 缩放比例 / 角度）
- [ ] 弹层类型点名到具体组件
- [ ] 每条有 `文件:行号`
- [ ] 没贴大段源码
- [ ] 上一批已确认过的内容不要重复写

---

## 附：我现在的界面与 SukiSU 的差异（更新版）

上一批之后已经对齐的就不列了，剩下的：

| 项 | 我现在的做法 | 是否已知 SukiSU 做法 |
|---|---|---|
| 图标语汇 | 全用 `MiuixIcons` | **已知**：SukiSU 主体是 `Icons.Rounded.*`（62 处 / 46 个）。我在等体积方案决定，这一项不用再给 |
| 底部导航角标 | 自己画的 Text | **未知**，要 G5 |
| 删除/卸载按钮位置 | 和选人格、改密钥挤一行 | **未知**，要 G5 |
| hero 点击 | 不可点 | **未知**，要 G1 |
| 二级页 | 没有 | **未知**，要 G4 |
| 人格下拉 | 一排 Pill | **未知**，要 G3 |
| 设置页三种行 | 自定义行 | **未知**，要 G2 的 40 |
