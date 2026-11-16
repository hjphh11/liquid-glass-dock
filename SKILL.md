---
name: liquid-glass-dock
description: 复刻液态玻璃（Liquid Glass）风格的悬浮药丸底部导航栏——半透明毛玻璃底、可拖动带惯性的胶囊指示器、选中项从玻璃里"透"出来、按压时边缘折射凸起。当用户说"这个导航栏怎么做的""照着做一个一样的""要这种玻璃底部栏""可滑动的底部导航"，或需要在 Jetpack Compose（AGSL）或网页（backdrop-filter）里实现同款底部导航时使用。也用于只解释其分层原理的场合。
agent_created: true
---

# 液态玻璃药丸导航栏

一套已经过编译与浏览器验证的实现，包含视觉规格、可拖动交互、AGSL 折射着色器与降级路径。目标是**复刻结果一致**，而不是重新设计。

唯一权威来源是 `assets/` 下两份可直接落地的代码，`references/` 里是规格与接入细节。不要凭印象重写，先复制再改。

## 一、先判断落到哪个平台

| 目标 | 用哪份资产 | 保真度 |
|---|---|---|
| Android / Jetpack Compose 项目 | `assets/compose/GlassDock.kt` | 1:1（AGSL 真折射） |
| 网页 / H5 / 小程序 / 静态站 | `assets/web/glass-dock.html` | 视觉近似（无真折射） |
| 用户只是问"怎么做的" | 读 `references/spec.md`，按分层讲，不写代码 | — |

## 二、Compose 落地步骤

1. 把 `assets/compose/GlassDock.kt` 复制到目标项目的 `ui/` 目录，**改掉首行包名**。
2. 依赖要求：Compose 1.7+（`rememberGraphicsLayer` / `GraphicsLayer.renderEffect`）、Material3；minSdk 21 可编译，玻璃效果需 API 31，折射需 33。
3. 在页面顶层建采样源，并让页面主体走 `backdropSource`：

```kotlin
val backdrop = rememberBackdropSource()
Box(Modifier.fillMaxSize()) {
    MainContent(Modifier.fillMaxSize().backdropSource(backdrop))
    LiquidGlassDock(
        items = listOf(
            DockItem("首页", Icons.Outlined.Home, Icons.Rounded.Home),
            DockItem("搜索", Icons.Outlined.Search, Icons.Rounded.Search),
            DockItem("排行榜", Icons.Outlined.TrendingUp, Icons.Rounded.TrendingUp),
            DockItem("我的", Icons.Outlined.Person, Icons.Rounded.Person),
        ),
        selectedIndex = selected,
        onSelect = { selected = it },
        source = backdrop,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
    )
}
```

4. 跑三个检查：
   - **是纯色板** → `source` 没接上，或页面主体没走 `Modifier.backdropSource()`。
   - **像毛玻璃但没有折射和彩边** → 设备 API < 33，或 `liquid = false`。属预期降级。
   - **指示器存在但选中项没高亮** → `accent` 传错，应与主题 primary 一致。

## 三、Web 落地步骤

1. 直接打开 `assets/web/glass-dock.html` 看效果；拖动指示器、按住看折射。
2. 嵌入页面时保留三层结构：`dock`（blur + 内描边）→ `ind`（清晰副本 + 高光 + 可选 `filter: url(#lens)`）→ `ind-track`（跟随指示器反向位移的选中态内容）。
3. **对齐是唯一致命点**：指示器里的背景副本必须用与页面完全相同的背景图，并加 `background-attachment: fixed` + 视口单位尺寸（`100vw 100vh`）。尺寸一用百分比，副本就会和真背景错开。
4. 真实折射需要 WebGL；CSS 版用 `backdrop-filter` + 边缘高光 + 按下时的 `feDisplacementMap` 近似。要求像素级一致时改用 Compose 版或 WebGL 移植。

## 四、参数不能乱改

这些数字是配平过的，改一个就要连带改另外两个（完整推导见 `references/spec.md`）：

| 项 | 值 |
|---|---|
| 条高 / 内距 | 62dp / 5dp |
| 每格宽 | `clamp(64dp, (屏宽-36)/格数, 92dp)` |
| 外圆角 | 条高 ÷ 2 = 31dp |
| 指示器圆角 | (高 − 2×内距) ÷ 2 = 26dp |
| 外玻璃 | blur 5dp、saturate 1.5、tint 深色 #000 24%／浅色 #fff 34%、折射 24dp、开拆色、白高光 .55/.8、阴影 14dp |
| 指示器玻璃 | 折射 12dp / 16dp、白高光 .85、叠 13% 主题色、无阴影 |
| 位移弹簧 | damping .72 / stiffness 380 |
| 松手弹簧 | damping .86 / stiffness 420，带初速 |
| 按压 | 1 → 1.08 缩放，横向按速度拉伸 ≤16% |

三条容易踩反的规律：

1. **药丸圆角恒等于高度的一半**，指示器圆角恒等于 `(高 − 2×内距) ÷ 2`。这两个值不独立。
2. **模糊要小（5dp）**。加到 20dp 以上就成毛玻璃板，透不出背后的封面色；浓度靠 tint 的 alpha 撑，通透感靠折射撑。
3. **指示器的 `lensProgress` 绑按压进度，不是常数**。静止时它是平面玻璃，只负责把选中态贴出来；按住才开始凸起折射。写成 `{ 1f }` 会让整条导航永远糊一层扭曲。

## 五、参考文件

- `references/spec.md` — 完整视觉规格、动效曲线、分平台降级表、自检清单
- `references/adapt.md` — 改成 3/5 个入口、接入 Navigation-Compose、换主题色、方形变体、性能与常见坑
- `assets/compose/GlassDock.kt` — Compose 实现（AGSL 折射 + 双绘 + 拖动惯性）
- `assets/web/glass-dock.html` — 单文件网页实现（打开即用）
