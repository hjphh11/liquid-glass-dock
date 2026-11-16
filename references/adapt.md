# 接入与改造

## 改入口数量

`LiquidGlassDock` 的宽度由格数推出，3~5 格都能直接用，不需要改任何内部坐标。

```kotlin
val items = listOf(      // 3 格
    DockItem("首页", Icons.Outlined.Home, Icons.Rounded.Home),
    DockItem("搜索", Icons.Outlined.Search, Icons.Rounded.Search),
    DockItem("我的", Icons.Outlined.Person, Icons.Rounded.Person),
)
```

- 5 格时每格会被 `clamp` 压到 64dp 下限，屏宽 < 396dp 会超出屏幕。此时要么减格，要么把 `DockTabMin` 调到 58dp 并接受文字压到 10sp。
- 超过 5 格不要用它，改成"4 格 + 更多"结构。

## 接入 Navigation-Compose

`selectedIndex` 是纯状态，映射关系自己维护即可：

```kotlin
val tabs = listOf(Route.Home, Route.Search, Route.Charts, Route.Mine)
val current = navController.currentBackStackEntryAsState().value
val index = tabs.indexOrNull(current?.destination)
LiquidGlassDock(items, selectedIndex = index, onSelect = { i ->
    navController.navigate(tabs[i]) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}, source = backdrop)
```

子页面（设置、本地音乐、播放页）不是 tab 时，要有别名映射，否则指示器会跳回第 0 格：

```kotlin
fun indexOrNull(dest: NavDestination?): Int = when (dest?.route) {
    Route.Settings, Route.Library, Route.NowPlaying -> 3   // 停在「我的」
    else -> tabs.indexOfFirst { it.route == dest?.route }.coerceAtLeast(0)
}
```

## 换主题色

`accent` 只影响选中文字/图标与那层 13% 薄膜，改一个参数即可：

```kotlin
LiquidGlassDock(..., accent = Color(0xFFFFFFFF))   // 截图的白色高亮
LiquidGlassDock(..., accent = MaterialTheme.colorScheme.primary)
```

深色背景上纯白最稳；彩色 accent 要注意与背后封面撞色，建议先压一层浅色 tint 再试。

## 亮色主题

不要只把 tint 反过来。亮色下玻璃容易脏，按这套改：

| 项 | 深色 | 亮色 |
|---|---|---|
| tint | `#000` 24% | `#fff` 34%（可到 42%） |
| highlight | .55 | .8 |
| 指示器膜 | 白 13% | 主题色 10% + 白 4% |
| shadow | 14dp / 黑 35% | 10dp / 黑 18% |
| inactive | onSurfaceVariant | onSurfaceVariant 再降 10% 透明度 |

浅色主题下如果背景是纯白页面，玻璃会完全看不出——这不是 bug，是缺采样源。要么给页面加彩色氛围层（色斑、封面墙），要么退回 `liquid = false` 的毛玻璃。

## 变体

**方形圆角条**：把 `DockHeight / 2` 改成 `20.dp`，指示器圆角改成 `(DockHeight - DockInset*2) / 2 - 6.dp`，其余不动。气质会从 iOS 变成 Windows 11。

**更薄的条**：62 → 52dp 时，同步把图标 22→20dp、字号 11→10.5sp、内距 5→4dp，否则内容会顶着边。

**给指示器加"跟随手指倾斜"**：在 `graphicsLayer` 里补 `rotationZ = (velocity / 8000f).coerceIn(-8f, 8f) * press`，只在拖动时有感，静止为 0。

## 性能

- 大半径模糊先降采样再放大（代码里 `down` 分支），24dp 以上能省一大截 GPU。
- `RenderEffect` 用 `WeakHashMap` 缓存，避免每帧重建（重建会在低端机上抖）。
- AGSL 着色器只在 `style.lens / dispersion / highlight` 变化时 `remember` 重建，不要放进 `drawWithContent` 里新建。
- `drawWithContent` 里读 `source.placement` 是刻意的：它让 Compose 知道"采样源变了要重绘"，去掉会画面冻结。
- 页面里同时存在多个玻璃（迷你播放器、导航栏、弹窗）时，每个都会把页面录一遍纹理；超过 3 个要考虑合并采样源。

## 常见坑

1. **编译过但玻璃是灰板**：`source` 传了 null，或页面主体没套 `Modifier.backdropSource(...)`。
2. **内容被模糊了**：把内容放在了 `glass()` 之前（比如给 `Box` 的父级加 glass）。玻璃只该包住"条"，不该包住"内容"。
3. **指示器静止时糊成一团**：`lensProgress` 写成了常数 1。
4. **指示器永远不折射**：设备 API < 33；或 `RuntimeShader` 构造失败被 `runCatching` 吞了（看 Logcat 的 `LiquidGlass` 标签）。
5. **拖不动**：外层有横向 `HorizontalPager` / `LazyRow` 抢先消费了事件。给导航栏加 `Modifier.pointerInput` 时用 `awaitEachGesture` 并 `change.consume()`；仍冲突则在按下阶段就用 `awaitFirstDown(requireUnconsumed = false)`。
6. **Web 版指示器里的画面和背景错位**：`background-size` 用了百分比，改成 `100vw 100vh`，并确保 `background-attachment: fixed`，且祖先元素上没有 `transform` / `filter`（会让 fixed 退化成相对祖先）。
7. **Web 版看不到模糊**：背后是纯色或平滑渐变时 `backdrop-filter` 等于没效果。必须有高频细节（图片、封面、文字）才看得出。
