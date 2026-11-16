package com.example.liquidglass

import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// 1. 玻璃素材：把任意 Composable 的像素录成一张可被采样的纹理
// ─────────────────────────────────────────────────────────────────────────────

/** 一张可被 [Modifier.glass] 采样的画布。 */
@Stable
class BackdropSource {
    internal var layer: GraphicsLayer? = null
    internal var coordinates: LayoutCoordinates? = null
    internal var placement by mutableIntStateOf(0)
}

/** 建一个采样源，通常放在页面的导航栏外层。 */
@Composable
fun rememberBackdropSource(): BackdropSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropSource().also { it.layer = layer } }
}

/** 把内容录进纹理，并且照常显示（用于页面主体）。 */
fun Modifier.backdropSource(source: BackdropSource): Modifier = this
    .onGloballyPositioned {
        source.coordinates = it
        source.placement++
    }
    .drawWithContent {
        val layer = source.layer
        if (layer == null) {
            drawContent()
        } else {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }

/** 把内容录进纹理，但不绘制（用于「本该出现在玻璃里」的隐藏层）。 */
fun Modifier.hiddenBackdropSource(source: BackdropSource): Modifier = this
    .onGloballyPositioned {
        source.coordinates = it
        source.placement++
    }
    .drawWithContent {
        source.layer?.record { this@drawWithContent.drawContent() }
    }

// ─────────────────────────────────────────────────────────────────────────────
// 2. 玻璃样式与 Modifier.glass
// ─────────────────────────────────────────────────────────────────────────────

@Stable
data class GlassStyle(
    val cornerRadius: Dp,
    val blur: Dp,
    val tint: Color,
    val saturation: Float = 1.5f,
    /** 边缘折射（需要 API 33）。 */
    val lens: Boolean = false,
    val refractionHeight: Dp = 24.dp,
    val refractionAmount: Dp = 24.dp,
    /** 折射处拆色，边缘泛出彩边。 */
    val dispersion: Boolean = false,
    val highlight: Color = Color.Transparent,
    val highlightWidth: Dp = 0.7.dp,
    val shadow: Dp = 0.dp,
    /** 采不到纹理时的兜底底色。 */
    val fallback: Color = tint,
)

/**
 * 玻璃面。先画采样后的模糊+提饱和纹理，再叠 tint、锐利层、边缘高光，最后画自己的内容。
 *
 * @param source 背后要采样的内容
 * @param sharpSources 额外采样的「锐利层」：不模糊，直接贴在玻璃上（导航栏用它贴选中态文字）
 * @param lensProgress 0..1，折射强度。0 = 纯平面玻璃，1 = 凸起的液滴
 */
fun Modifier.glass(
    source: BackdropSource?,
    style: GlassStyle,
    sharpSources: List<BackdropSource> = emptyList(),
    lensProgress: () -> Float = { 1f },
): Modifier = composed {
    val effectLayer = rememberGraphicsLayer()
    val sharpLayer = rememberGraphicsLayer()
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    @Suppress("UNUSED_VARIABLE")
    var placement by remember { mutableIntStateOf(0) }
    val shaders = remember(style.lens, style.dispersion, style.highlight.alpha > 0f) {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { GlassShaders(style.lens, style.dispersion, style.highlight.alpha > 0f) }
                .onFailure { Log.w("LiquidGlass", "AGSL 不可用，降级为纯模糊", it) }
                .getOrNull()
        } else null
    }
    val shape = RoundedCornerShape(style.cornerRadius)
    this
        .then(
            if (style.shadow > 0.dp) {
                Modifier.shadow(
                    style.shadow, shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = .35f),
                    spotColor = Color.Black.copy(alpha = .35f),
                )
            } else Modifier
        )
        .clip(shape)
        .onGloballyPositioned {
            coordinates[0] = it
            placement++
        }
        .drawWithContent {
            placement
            source?.placement
            sharpSources.forEach { it.placement }
            val progress = lensProgress().coerceIn(0f, 1.5f)
            val radius = style.cornerRadius.toPx().coerceIn(0f, size.minDimension / 2f)
            val full = Rect(0f, 0f, size.width, size.height)
            val sampled = drawSources(
                effectLayer, source, emptyList(), coordinates[0],
                style, progress, shaders, full, 1f, radius,
            )
            if (sampled) drawRect(style.tint) else drawRect(style.fallback)
            if (sharpSources.isNotEmpty()) {
                drawSources(
                    sharpLayer, null, sharpSources, coordinates[0],
                    style.copy(blur = 0.dp, saturation = 1f), progress, shaders, full, 1f, radius,
                )
            }
            val highlight = if (Build.VERSION.SDK_INT >= 33) shaders?.highlight else null
            if (highlight != null && style.highlight.alpha > 0f && progress > 0.01f) {
                val c = style.highlight
                val a = (c.alpha * progress).coerceIn(0f, 1f)
                val ready = runCatching {
                    highlight.setFloatUniform("size", size.width, size.height)
                    highlight.setFloatUniform("radius", radius)
                    highlight.setFloatUniform("lightDir", GlassLight.x, GlassLight.y)
                    highlight.setFloatUniform("falloff", 1f)
                    highlight.setFloatUniform("color", c.red * a, c.green * a, c.blue * a, a)
                }.isSuccess
                if (ready) {
                    drawRoundRect(
                        brush = ShaderBrush(highlight),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(style.highlightWidth.toPx() * 2f),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
            drawContent()
        }
}

private class SampledLayer(val layer: GraphicsLayer, val origin: Offset, val scaleX: Float, val scaleY: Float)

private fun DrawScope.drawSources(
    effectLayer: GraphicsLayer,
    source: BackdropSource?,
    extraSources: List<BackdropSource>,
    own: LayoutCoordinates?,
    style: GlassStyle,
    progress: Float,
    shaders: GlassShaders?,
    region: Rect,
    blurScale: Float,
    radius: Float,
): Boolean {
    if (own == null || !own.isAttached) return false
    if (Build.VERSION.SDK_INT < 31) return false
    val layers = (listOfNotNull(source) + extraSources).mapNotNull { candidate ->
        val layer = candidate.layer ?: return@mapNotNull null
        val coordinates = candidate.coordinates ?: return@mapNotNull null
        if (!coordinates.isAttached) return@mapNotNull null
        val origin = coordinates.localPositionOf(own, Offset.Zero)
        val scaleX = (coordinates.localPositionOf(own, Offset(1f, 0f)).x - origin.x).takeIf { it > 0.01f } ?: 1f
        val scaleY = (coordinates.localPositionOf(own, Offset(0f, 1f)).y - origin.y).takeIf { it > 0.01f } ?: 1f
        SampledLayer(layer, origin, scaleX, scaleY)
    }
    if (layers.isEmpty()) return false

    val blurPx = style.blur.toPx() * blurScale
    val padding = ceil(maxOf(blurPx * 2f, 2f)).toInt()
    val left = region.left.toInt()
    val top = region.top.toInt()
    val width = ceil(region.width).toInt().coerceAtLeast(1)
    val height = ceil(region.height).toInt().coerceAtLeast(1)
    val lensActive = Build.VERSION.SDK_INT >= 33 && style.lens && shaders?.lens != null && progress > 0.01f
    // 大半径模糊时降采样再放大，省一大截 GPU
    val down = when {
        lensActive -> 1f
        blurPx >= 24f -> .25f
        blurPx >= 10f -> .5f
        else -> 1f
    }
    if (down < 1f) {
        val scaledWidth = ceil((width + padding * 2) * down).toInt().coerceAtLeast(1)
        val scaledHeight = ceil((height + padding * 2) * down).toInt().coerceAtLeast(1)
        effectLayer.renderEffect = cachedEffect(effectLayer, style, blurPx * down)
        effectLayer.topLeft = IntOffset.Zero
        effectLayer.record(size = IntSize(scaledWidth, scaledHeight)) {
            scale(down, down, pivot = Offset.Zero) {
                layers.forEach { sampled ->
                    translate((padding - left).toFloat(), (padding - top).toFloat()) {
                        scale(1f / sampled.scaleX, 1f / sampled.scaleY, pivot = Offset.Zero) {
                            translate(-sampled.origin.x, -sampled.origin.y) { drawLayer(sampled.layer) }
                        }
                    }
                }
            }
        }
        translate((left - padding).toFloat(), (top - padding).toFloat()) {
            scale(1f / down, 1f / down, pivot = Offset.Zero) { drawLayer(effectLayer) }
        }
        return true
    }
    effectLayer.renderEffect = if (lensActive) {
        glassEffect(style, blurPx, left - padding, top - padding, shaders, progress, radius)
    } else {
        cachedEffect(effectLayer, style, blurPx)
    }
    effectLayer.topLeft = IntOffset(left - padding, top - padding)
    effectLayer.record(size = IntSize(width + padding * 2, height + padding * 2)) {
        layers.forEach { sampled ->
            translate((padding - left).toFloat(), (padding - top).toFloat()) {
                scale(1f / sampled.scaleX, 1f / sampled.scaleY, pivot = Offset.Zero) {
                    translate(-sampled.origin.x, -sampled.origin.y) { drawLayer(sampled.layer) }
                }
            }
        }
    }
    drawLayer(effectLayer)
    return true
}

private val effectCache = java.util.WeakHashMap<GraphicsLayer, CachedEffect>()

private class CachedEffect(val blur: Float, val saturation: Float, val effect: androidx.compose.ui.graphics.RenderEffect)

@RequiresApi(31)
private fun cachedEffect(layer: GraphicsLayer, style: GlassStyle, blurPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val rounded = round(blurPx * 4f) / 4f
    effectCache[layer]?.let { if (it.blur == rounded && it.saturation == style.saturation) return it.effect }
    var effect: android.graphics.RenderEffect =
        android.graphics.RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(saturationMatrix(style.saturation)),
        )
    if (rounded >= 1f) {
        effect = android.graphics.RenderEffect.createBlurEffect(rounded, rounded, effect, Shader.TileMode.CLAMP)
    }
    return effect.asComposeRenderEffect().also { effectCache[layer] = CachedEffect(rounded, style.saturation, it) }
}

@RequiresApi(31)
private fun DrawScope.glassEffect(
    style: GlassStyle,
    blurPx: Float,
    offsetX: Int,
    offsetY: Int,
    shaders: GlassShaders?,
    progress: Float,
    radius: Float,
): androidx.compose.ui.graphics.RenderEffect {
    var effect: android.graphics.RenderEffect =
        android.graphics.RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(saturationMatrix(style.saturation)),
        )
    if (blurPx >= 1f) {
        effect = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, effect, Shader.TileMode.CLAMP)
    }
    val lens = if (Build.VERSION.SDK_INT >= 33) shaders?.lens else null
    if (style.lens && lens != null && Build.VERSION.SDK_INT >= 33 && progress > 0.01f) {
        val lensEffect = runCatching {
            lens.setFloatUniform("size", size.width, size.height)
            lens.setFloatUniform("offset", offsetX.toFloat(), offsetY.toFloat())
            lens.setFloatUniform("radius", radius)
            lens.setFloatUniform(
                "refractionHeight",
                (style.refractionHeight.toPx() * progress).coerceIn(1f, size.minDimension / 2f),
            )
            lens.setFloatUniform("refractionAmount", -style.refractionAmount.toPx() * progress)
            lens.setFloatUniform("depthEffect", 0f)
            if (style.dispersion) lens.setFloatUniform("chromaticAberration", 1f)
            android.graphics.RenderEffect.createRuntimeShaderEffect(lens, "content")
        }.getOrNull()
        if (lensEffect != null) effect = android.graphics.RenderEffect.createChainEffect(lensEffect, effect)
    }
    return effect.asComposeRenderEffect()
}

private fun saturationMatrix(saturation: Float): FloatArray {
    val inv = 1f - saturation
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    return floatArrayOf(
        r + saturation, g, b, 0f, 0f,
        r, g + saturation, b, 0f, 0f,
        r, g, b + saturation, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. AGSL：边缘折射 + 沿光向的细高光
// ─────────────────────────────────────────────────────────────────────────────

private object GlassLight {
    val x = cos(Math.toRadians(45.0)).toFloat()
    val y = sin(Math.toRadians(45.0)).toFloat()
}

@RequiresApi(33)
private class GlassShaders(lens: Boolean, dispersion: Boolean, highlight: Boolean) {
    val lens: RuntimeShader? = if (lens) RuntimeShader(if (dispersion) LENS_DISPERSION_AGSL else LENS_AGSL) else null
    val highlight: RuntimeShader? = if (highlight) RuntimeShader(HIGHLIGHT_AGSL) else null
}

private const val ROUNDED_RECT_SDF = """
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}
"""

private const val LENS_UNIFORMS = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float chromaticAberration;
uniform float depthEffect;
"""

private const val LENS_AGSL = LENS_UNIFORMS + ROUNDED_RECT_SDF + """
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    return content.eval(coord + d * grad);
}
"""

private const val LENS_DISPERSION_AGSL = LENS_UNIFORMS + ROUNDED_RECT_SDF + """
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration *
        ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    return color;
}
"""

private const val HIGHLIGHT_AGSL = """
uniform float2 size;
uniform float radius;
uniform float2 lightDir;
uniform float falloff;
uniform half4 color;

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float d = dot(grad, lightDir);
    float intensity = pow(abs(d), falloff);
    return color * half(intensity);
}
"""

// ─────────────────────────────────────────────────────────────────────────────
// 4. 导航栏本体
// ─────────────────────────────────────────────────────────────────────────────

data class DockItem(
    val label: String,
    /** 未选中：线性图标。 */
    val icon: ImageVector,
    /** 选中：实心图标。 */
    val selectedIcon: ImageVector,
)

private val DockHeight = 62.dp
private val DockInset = 5.dp
private val DockTabMin = 64.dp
private val DockTabMax = 92.dp
private val DockSideMargin = 13.dp

/** 每格宽度：屏宽减去两侧留白与内距后均分，再夹在 64~92dp。 */
@Composable
fun dockTabWidth(count: Int): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val available = (screenWidth - DockSideMargin * 2 - DockInset * 2) / count
    return available.coerceIn(DockTabMin, DockTabMax)
}

/**
 * 液态玻璃药丸导航栏。
 *
 * @param source 页面主体的采样源，必须由 [Modifier.backdropSource] 提供，否则玻璃是纯色板
 * @param selectedIndex 当前选中项，越界会被夹回合法区间
 * @param liquid 是否开启折射 / 拆色 / 边缘高光（关掉只剩毛玻璃）
 */
@Composable
fun LiquidGlassDock(
    items: List<DockItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    source: BackdropSource?,
    modifier: Modifier = Modifier,
    liquid: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    inactive: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val tabWidth = dockTabWidth(items.size)
    val tabWidthPx = with(density) { tabWidth.toPx() }
    val paddingPx = with(density) { DockInset.toPx() }
    val maxX = tabWidthPx * (items.size - 1)
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)

    val indicatorX = remember { Animatable(safeIndex * tabWidthPx) }
    val press = remember { Animatable(0f) }
    var velocity by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    val currentSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(safeIndex, tabWidthPx) {
        if (!dragging) indicatorX.animateTo(safeIndex * tabWidthPx, spring(dampingRatio = .72f, stiffness = 380f))
    }

    val accentSource = rememberBackdropSource()
    val barStyle = if (liquid) {
        GlassStyle(
            cornerRadius = DockHeight / 2,
            blur = 5.dp,
            tint = if (dark) Color.Black.copy(alpha = .24f) else Color.White.copy(alpha = .34f),
            saturation = 1.5f,
            lens = true,
            refractionHeight = 24.dp,
            refractionAmount = 24.dp,
            dispersion = true,
            highlight = Color.White.copy(alpha = if (dark) .55f else .8f),
            shadow = 14.dp,
            fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    } else {
        GlassStyle(
            cornerRadius = DockHeight / 2,
            blur = 26.dp,
            tint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) .70f else .66f),
            saturation = 1.7f,
            highlight = Color.White.copy(alpha = if (dark) .14f else .45f),
            shadow = 14.dp,
            fallback = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    val indicatorStyle = barStyle.copy(
        cornerRadius = (DockHeight - DockInset * 2) / 2,
        lens = liquid,
        refractionHeight = 12.dp,
        refractionAmount = 16.dp,
        dispersion = liquid,
        highlight = if (liquid) Color.White.copy(alpha = .85f) else Color.Transparent,
        shadow = 0.dp,
    )

    Box(
        modifier
            .width(tabWidth * items.size + DockInset * 2)
            .height(DockHeight)
            .glass(source, barStyle)
            .pointerInput(items.size, tabWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = indicatorX.value
                    val fingerX = down.position.x - paddingPx - tabWidthPx / 2f
                    val onIndicator = abs(fingerX - start) < tabWidthPx / 2f
                    val origin = if (onIndicator) start else fingerX.coerceIn(0f, maxX)
                    dragging = true
                    scope.launch { press.animateTo(1f, spring(dampingRatio = .85f, stiffness = 600f)) }
                    if (!onIndicator) {
                        scope.launch { indicatorX.animateTo(origin, spring(dampingRatio = .8f, stiffness = 600f)) }
                    }
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var moved = false
                    var lastIndex = round(origin / tabWidthPx).toInt()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange =
                            event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - down.position.x
                        if (!moved && abs(dx) > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            val x = rubberbandClamp(origin + dx, 0f, maxX, dimension = tabWidthPx * .4f)
                            scope.launch { indicatorX.snapTo(x) }
                            velocity = tracker.calculateVelocity().x
                            val index = round(x / tabWidthPx).toInt().coerceIn(0, currentItems.lastIndex)
                            if (index != lastIndex) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastIndex = index
                            }
                            change.consume()
                        }
                    }
                    val fling = tracker.calculateVelocity().x
                    val finalIndex = if (moved) {
                        round((indicatorX.value + projectFling(fling, .99f)) / tabWidthPx).toInt()
                    } else {
                        ((down.position.x - paddingPx) / tabWidthPx).toInt()
                    }.coerceIn(0, currentItems.lastIndex)
                    dragging = false
                    velocity = 0f
                    scope.launch {
                        indicatorX.animateTo(
                            finalIndex * tabWidthPx,
                            spring(dampingRatio = .86f, stiffness = 420f),
                            initialVelocity = if (moved) fling else 0f,
                        )
                    }
                    scope.launch { press.animateTo(0f, spring(dampingRatio = .82f, stiffness = 300f)) }
                    if (!moved || finalIndex != lastIndex) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    currentSelect(finalIndex)
                }
            }
            .padding(DockInset),
    ) {
        // 隐藏层：只录纹理，用来把「选中态」贴到玻璃上
        DockRow(
            items = items, accent = true, tabWidth = tabWidth, accentColor = accent, inactiveColor = inactive,
            modifier = Modifier.matchParentSize().hiddenBackdropSource(accentSource),
        )
        // 可见层：全部灰态
        DockRow(
            items = items, accent = false, tabWidth = tabWidth, accentColor = accent, inactiveColor = inactive,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .width(tabWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = indicatorX.value
                    val p = press.value
                    val stretch = (abs(velocity) / 7000f).coerceAtMost(.16f) * p
                    scaleX = 1f + .08f * p + stretch
                    scaleY = 1f + .08f * p - stretch * .5f
                }
                .glass(
                    source = source,
                    style = indicatorStyle,
                    sharpSources = listOf(accentSource),
                    lensProgress = { press.value },
                )
                .drawBehind {
                    drawRect(accent.copy(alpha = .13f * (1f - press.value * .6f)))
                },
        )
    }
}

@Composable
private fun DockRow(
    items: List<DockItem>,
    accent: Boolean,
    tabWidth: Dp,
    accentColor: Color,
    inactiveColor: Color,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Column(
                Modifier.width(tabWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (accent) item.selectedIcon else item.icon,
                    contentDescription = if (accent) null else item.label,
                    modifier = Modifier.size(22.dp),
                    tint = if (accent) accentColor else inactiveColor,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    fontSize = 11.sp,
                    fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (accent) accentColor else inactiveColor,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. 动效小工具
// ─────────────────────────────────────────────────────────────────────────────

/** 松手后还要滑多远。 */
private fun projectFling(velocityPerSecond: Float, decelerationRate: Float = .998f): Float =
    velocityPerSecond / 1000f * decelerationRate / (1f - decelerationRate)

/** 拖过边界后的橡皮筋衰减。 */
private fun rubberbandClamp(value: Float, min: Float, max: Float, dimension: Float): Float {
    val constant = .55f
    fun rubber(overshoot: Float) = overshoot * dimension * constant / (dimension + constant * abs(overshoot))
    return when {
        value < min -> min + rubber(value - min)
        value > max -> max + rubber(value - max)
        else -> value
    }
}
