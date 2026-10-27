package app.fastsend.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private val AuroraCyan = Color(0xFF00F2FE)
private val AuroraBlue = Color(0xFF4FACFE)
private val SoftBackground = Color(0xFF080D18)
private val PhoneSurface = Color(0xFF151E30)
private val PhoneBorder = Color(0xFF52647F)

/**
 * Visual-only NFC pairing screen.
 *
 * IMPORTANT: nfcDetected must be driven by a real NFC callback from the host Activity.
 * This composable does not scan NFC itself and does not create a Nearby connection.
 */
@Composable
fun NfcPairingAnimationScreen(
    nfcDetected: Boolean,
    pairingStatus: String = "将两台手机顶部靠近以开始配对",
    onBack: (() -> Unit)? = null,
    onAnimationFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var playedForCurrentEvent by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }

    LaunchedEffect(nfcDetected) {
        if (!nfcDetected) {
            playedForCurrentEvent = false
            reveal.snapTo(0f)
            pulse.snapTo(0f)
        } else if (!playedForCurrentEvent) {
            playedForCurrentEvent = true
            reveal.snapTo(0f)
            pulse.snapTo(0f)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1050,
                    easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
                )
            )
            pulse.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 400f
                )
            )
            onAnimationFinished?.invoke()
        }
    }

    val revealValue = reveal.value
    val pulseValue = pulse.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SoftBackground)
    ) {
        // The top-down AGSL distortion is used only on Android 13+.
        // On older Android versions, the same one-shot reveal remains visible without shader distortion.
        if (nfcDetected && Build.VERSION.SDK_INT >= 33) {
            TopDownShaderDistortion(
                progress = revealValue,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Surface(
                        onClick = onBack,
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.padding(12.dp).size(20.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(44.dp))
                }
                Text(
                    text = "NFC 快速配对",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.size(44.dp))
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = if (nfcDetected) "已检测到 NFC 触碰" else "让两台手机轻轻碰一下",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = pairingStatus,
                color = Color(0xFF9CAAC0),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Edge light is entirely inactive before a real NFC event.
                if (nfcDetected) {
                    EdgeFlowLight(
                        progress = revealValue,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                PhonePairIllustration(
                    nfcDetected = nfcDetected,
                    revealProgress = revealValue,
                    pulse = pulseValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )

                if (nfcDetected) {
                    TopDownRevealBand(
                        progress = revealValue,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = if (nfcDetected) AuroraCyan else Color(0xFF9CAAC0),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (nfcDetected) "正在显示配对触碰效果" else "等待真实 NFC 事件",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun PhonePairIllustration(
    nfcDetected: Boolean,
    revealProgress: Float,
    pulse: Float,
    modifier: Modifier = Modifier
) {
    val idleTransition = rememberInfiniteTransition(label = "phone-idle")
    val idleApproach by idleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f))
        ),
        label = "phone-approach"
    )
    val approach = if (nfcDetected) 1f else (0.5f - 0.5f * kotlin.math.cos(idleApproach * Math.PI)).toFloat()
    val leftX by animateFloatAsState(
        targetValue = if (nfcDetected) -0.10f else -0.17f + 0.07f * approach,
        animationSpec = if (nfcDetected) spring(dampingRatio = 0.6f, stiffness = 400f) else tween(400),
        label = "left-phone-x"
    )
    val rightX by animateFloatAsState(
        targetValue = if (nfcDetected) 0.10f else 0.17f - 0.07f * approach,
        animationSpec = if (nfcDetected) spring(dampingRatio = 0.6f, stiffness = 400f) else tween(400),
        label = "right-phone-x"
    )

    BoxWithConstraintsCompat(modifier = modifier, contentAlignment = Alignment.Center) {
        val availableWidth = maxWidth
        val phoneWidth = availableWidth * 0.39f
        val phoneGap = availableWidth * 0.02f
        val phoneHeight = if (availableWidth * 0.73f < 310.dp) availableWidth * 0.73f else 310.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhoneCard(
                modifier = Modifier
                    .width(phoneWidth)
                    .height(phoneHeight)
                    .graphicsLayer {
                        translationX = phoneWidth.toPx() * leftX
                        rotationZ = -7f + 7f * approach
                        scaleX = 1f + 0.025f * pulse
                        scaleY = 1f + 0.025f * pulse
                    },
                accent = AuroraCyan,
                screenLabel = "FAST",
                revealProgress = revealProgress
            )
            Spacer(Modifier.width(phoneGap))
            PhoneCard(
                modifier = Modifier
                    .width(phoneWidth)
                    .height(phoneHeight)
                    .graphicsLayer {
                        translationX = phoneWidth.toPx() * rightX
                        rotationZ = 7f - 7f * approach
                        scaleX = 1f + 0.025f * pulse
                        scaleY = 1f + 0.025f * pulse
                    },
                accent = AuroraBlue,
                screenLabel = "SEND",
                revealProgress = revealProgress
            )
        }

        if (nfcDetected) {
            Canvas(Modifier.fillMaxSize()) {
                val alpha = (1f - revealProgress).coerceIn(0f, 1f)
                val center = Offset(size.width / 2f, size.height * 0.24f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraCyan.copy(alpha = 0.32f * alpha), Color.Transparent),
                        center = center,
                        radius = size.width * (0.10f + revealProgress * 0.75f)
                    ),
                    radius = size.width * (0.10f + revealProgress * 0.75f),
                    center = center
                )
            }
        }
    }
}

/**
 * A tiny local BoxWithConstraints wrapper keeps the layout code readable.
 */
@Composable
private fun BoxWithConstraintsCompat(
    modifier: Modifier,
    contentAlignment: Alignment,
    content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.() -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
private fun PhoneCard(
    modifier: Modifier,
    accent: Color,
    screenLabel: String,
    revealProgress: Float
) {
    Box(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(PhoneSurface)
            .border(1.dp, PhoneBorder.copy(alpha = 0.9f), RoundedCornerShape(28.dp))
            .padding(7.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1C2A41), Color(0xFF0C1423))
                    )
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.75f))
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = screenLabel,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.54f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.15f),
                                accent.copy(alpha = 0.8f * revealProgress.coerceIn(0f, 1f)),
                                accent.copy(alpha = 0.15f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun TopDownRevealBand(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val p = progress.coerceIn(0f, 1f)
        val y = size.height * p
        val bandHeight = size.height * 0.12f
        if (p > 0f && p < 1f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AuroraCyan.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.08f),
                        AuroraBlue.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    startY = max(0f, y - bandHeight),
                    endY = min(size.height, y + bandHeight)
                ),
                topLeft = Offset(0f, max(0f, y - bandHeight)),
                size = Size(size.width, min(size.height, y + bandHeight) - max(0f, y - bandHeight))
            )
        }
    }
}

@Composable
private fun EdgeFlowLight(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.graphicsLayer { clip = true }) {
        val p = progress.coerceIn(0f, 1f)
        val strokeWidth = 30.dp.toPx()
        val perimeter = 2f * (size.width + size.height)
        val segmentLength = perimeter * 0.22f
        val offset = perimeter * p
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        // A stroked perimeter with a moving, multicolor brush creates the flowing edge-light.
        drawPath(
            path = path,
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.18f to AuroraCyan.copy(alpha = 0.05f),
                    0.34f to AuroraCyan.copy(alpha = 0.95f),
                    0.52f to AuroraBlue.copy(alpha = 0.9f),
                    0.70f to Color(0xFFFF8D9B).copy(alpha = 0.65f),
                    0.86f to Color.Transparent,
                    1.00f to Color.Transparent
                ),
                center = Offset(size.width / 2f, size.height / 2f)
            ),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        // A soft travelling highlight overlays the perimeter; alpha is tied to the one-shot reveal.
        val highlightAlpha = (1f - kotlin.math.abs(0.5f - p) * 1.4f).coerceIn(0f, 1f)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    AuroraCyan.copy(alpha = 0.45f * highlightAlpha),
                    AuroraBlue.copy(alpha = 0.38f * highlightAlpha),
                    Color.Transparent
                ),
                start = Offset(size.width * p, 0f),
                end = Offset(size.width * (p + 0.55f), size.height)
            ),
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
private fun TopDownShaderDistortion(
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= 33) {
        TopDownShaderLayer(progress = progress, modifier = modifier)
    }
}

@RequiresApi(33)
@Composable
private fun TopDownShaderLayer(
    progress: Float,
    modifier: Modifier
) {
    val shader = remember {
        RuntimeShader(
            """
            uniform shader content;
            uniform float2 size;
            uniform float progress;
            half4 main(float2 coord) {
                float2 uv = coord / size;
                float front = progress * 1.15;
                float distance = uv.y - front;
                float envelope = exp(-abs(distance) * 42.0) * (1.0 - progress * 0.55);
                float ripple = sin(distance * 95.0) * envelope * 10.0;
                float2 displaced = float2(coord.x + ripple, coord.y);
                half4 pixel = content.eval(displaced);
                half glow = half(envelope * 0.16);
                pixel.rgb += half3(0.0, glow, glow);
                return pixel;
            }
            """.trimIndent()
        )
    }
    // Apply the AGSL RuntimeShader to this layer. The shader's input is the layer content,
    // so it distorts the top-down highlight rather than pretending to distort the entire page.
    shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
    Box(
        modifier = modifier
            .graphicsLayer {
                renderEffect = RenderEffect
                    .createRuntimeShaderEffect(shader, "content")
                    .asComposeRenderEffect()
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            shader.setFloatUniform("size", size.width, size.height)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AuroraCyan.copy(alpha = 0.10f * (1f - progress)),
                        Color.White.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    startY = size.height * progress.coerceIn(0f, 1f) - size.height * 0.08f,
                    endY = size.height * progress.coerceIn(0f, 1f) + size.height * 0.08f
                )
            )
        }
    }
}