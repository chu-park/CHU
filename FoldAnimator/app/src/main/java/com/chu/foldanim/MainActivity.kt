@file:OptIn(ExperimentalMaterial3Api::class)

package com.chu.foldanim

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var config by mutableStateOf(FoldConfig())
    private var applied by mutableStateOf(false)
    private var image by mutableStateOf<Bitmap?>(null)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null && WallpaperImage.import(this, uri)) {
                update(config.copy(imageVersion = System.currentTimeMillis()))
                reloadImage()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = FoldSettings.load(this)
        reloadImage()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        config = config,
                        applied = applied,
                        image = image,
                        onConfigChange = ::update,
                        onApply = ::applyWallpaper,
                        onPickImage = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onResetImage = {
                            WallpaperImage.reset(this)
                            update(config.copy(imageVersion = System.currentTimeMillis()))
                            reloadImage()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applied = WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
    }

    private fun reloadImage() {
        image = WallpaperImage.load(this, 720)
    }

    private fun update(newConfig: FoldConfig) {
        config = newConfig
        FoldSettings.save(this, newConfig)
    }

    private fun applyWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DuoWallpaperService::class.java),
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(this, "배경화면 설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 힌지 각도 센서. (센서 있음 여부, 현재 각도) */
@Composable
private fun rememberHingeAngle(): Pair<Boolean, Float?> {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }
    var angle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(sensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                angle = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return (sensor != null) to angle
}

@Composable
private fun SettingsScreen(
    config: FoldConfig,
    applied: Boolean,
    image: Bitmap?,
    onConfigChange: (FoldConfig) -> Unit,
    onApply: () -> Unit,
    onPickImage: () -> Unit,
    onResetImage: () -> Unit,
) {
    val (hasHinge, liveAngle) = rememberHingeAngle()
    var followLive by remember { mutableStateOf(true) }
    var simAngle by remember { mutableFloatStateOf(70f) }
    val previewAngle = if (followLive && liveAngle != null) liveAngle else simAngle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("폴드 애니메이션", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard {
            Text(
                if (applied) "✅ 배경화면으로 적용됨" else "아직 배경화면으로 적용되지 않았어요",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "홈 화면 배경이 힌지 각도에 맞춰 흐려지고·늘어나며 커버 화면 ↔ 메인 화면으로 이어집니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onApply) { Text(if (applied) "배경화면 다시 설정" else "배경화면으로 설정하기") }
            SwitchRow(
                title = "접기/펼치기 애니메이션",
                subtitle = if (config.enabled) "켜짐" else "꺼짐 · 일반 배경화면처럼 보입니다",
                checked = config.enabled,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
            )
            if (!hasHinge) {
                Text(
                    "이 기기에서 힌지 각도 센서를 찾을 수 없어 애니메이션이 동작하지 않습니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard(title = "배경 이미지") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickImage) { Text("사진 선택") }
                OutlinedButton(onClick = onResetImage) { Text("기본 이미지") }
            }
        }

        SectionCard(title = "미리보기") {
            Text(
                "힌지 각도 ${liveAngle?.let { "${it.roundToInt()}°" } ?: "-"}" +
                    (if (!followLive || liveAngle == null) "  (시뮬레이션 ${simAngle.roundToInt()}°)" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ScreenPreview("커버", image, config, previewAngle, inner = false, width = 80.dp, height = 180.dp)
                ScreenPreview("메인", image, config, previewAngle, inner = true, width = 170.dp, height = 180.dp)
            }
            if (hasHinge) {
                SwitchRow(
                    title = "실제 힌지 각도 따라가기",
                    checked = followLive,
                    onCheckedChange = { followLive = it },
                )
            }
            if (!followLive || liveAngle == null) {
                Slider(value = simAngle, onValueChange = { simAngle = it }, valueRange = 0f..180f)
            }
        }

        SectionCard(title = "효과") {
            LabeledSlider("블러 강도", "${config.blurDp}", config.blurDp.toFloat(), 0f..80f) {
                onConfigChange(config.copy(blurDp = it.roundToInt()))
            }
            LabeledSlider("늘어남(확대)", "${config.zoomPct}%", config.zoomPct.toFloat(), 0f..60f) {
                onConfigChange(config.copy(zoomPct = it.roundToInt()))
            }
            LabeledSlider("어두워짐", "${config.dimPct}%", config.dimPct.toFloat(), 0f..100f) {
                onConfigChange(config.copy(dimPct = it.roundToInt()))
            }
            LabeledSlider("부드러움", "${config.smoothMs}ms", config.smoothMs.toFloat(), 0f..500f) {
                onConfigChange(config.copy(smoothMs = it.roundToInt()))
            }
        }

        SectionCard(title = "커버 화면") {
            LabeledSlider(
                "이 각도까지 펼치면 완전히 사라짐",
                "${config.coverAngle}°",
                config.coverAngle.toFloat(),
                10f..120f,
            ) { onConfigChange(config.copy(coverAngle = it.roundToInt())) }
        }

        SectionCard(title = "메인 화면 · 힌지 각도별 화면 표시 %") {
            Text(
                "펼쳐진 각도에 따라 메인 화면을 몇 % 선명하게 보여줄지 정합니다. 0%는 흐리고 어두운 상태, 100%는 원래 화면입니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            CurveChart(
                curve = config.curve,
                markerAngle = previewAngle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FoldSettings.PRESETS.forEach { (name, preset) ->
                    FilterChip(
                        selected = config.curve == preset,
                        onClick = { onConfigChange(config.copy(curve = preset)) },
                        label = { Text(name) },
                    )
                }
            }
            HorizontalDivider()
            FoldSettings.CURVE_ANGLES.forEachIndexed { i, deg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$deg°", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = config.curve[i].toFloat(),
                        onValueChange = { v ->
                            val newCurve = config.curve.toMutableList().also { it[i] = v.roundToInt() }
                            onConfigChange(config.copy(curve = newCurve))
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${config.curve[i]}%",
                        modifier = Modifier.width(48.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SectionCard(title = "문제 해결") {
            SwitchRow(
                title = "배경화면에 디버그 정보 표시",
                subtitle = "홈 화면에 힌지 각도 · 커버/메인 판별 · 표시 % 를 띄웁니다",
                checked = config.debugHud,
                onCheckedChange = { onConfigChange(config.copy(debugHud = it)) },
            )
            Text(
                "커버 화면에도 효과를 보려면 커버 화면 배경화면도 이 앱으로 설정되어 있어야 합니다. " +
                    "(One UI는 커버/메인 배경화면을 따로 설정합니다)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "참고: 배경화면이 보이는 홈 화면에서 동작합니다. 앱 아이콘은 One UI 런처가 그리기 때문에 함께 움직이지는 않고, " +
                "커버↔메인 화면 전환 순간의 짧은 검은 화면은 기기가 처리하는 부분이라 앱에서 없앨 수 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 배경화면과 같은 [EffectFrame] 계산으로 그린 작은 화면 모형 */
@Composable
private fun ScreenPreview(
    label: String,
    image: Bitmap?,
    config: FoldConfig,
    angle: Float,
    inner: Boolean,
    width: Dp,
    height: Dp,
) {
    val p = if (config.enabled) config.revealAt(angle, inner) else 1f
    val fx = EffectFrame.of(p, config, inner)
    // 미리보기는 실제 화면보다 작으니 블러도 비율만큼 줄입니다.
    val blur = (fx.blurDp * 0.35f).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = fx.scaleX
                            scaleY = fx.scaleY
                        }
                        .blur(blur, BlurredEdgeTreatment.Rectangle),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0.35f to Color.Black.copy(alpha = fx.dim * 0.65f),
                            1f to Color.Black.copy(alpha = (fx.dim * 1.2f).coerceAtMost(1f)),
                        )
                    ),
            )
        }
        Text("$label ${(p * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** 각도(가로) - 표시 %(세로) 그래프 */
@Composable
private fun CurveChart(curve: List<Int>, markerAngle: Float, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun x(deg: Float) = pad + w * deg / 180f
        fun y(pct: Float) = pad + h * (1f - pct / 100f)

        for (pct in listOf(0f, 50f, 100f)) {
            drawLine(grid, Offset(pad, y(pct)), Offset(pad + w, y(pct)), strokeWidth = 1.dp.toPx())
        }
        val angles = FoldSettings.CURVE_ANGLES
        val path = Path().apply {
            angles.forEachIndexed { i, deg ->
                val px = x(deg.toFloat())
                val py = y(curve[i].toFloat())
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(path, color = line, style = Stroke(width = 3.dp.toPx()))
        angles.forEachIndexed { i, deg ->
            drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(deg.toFloat()), y(curve[i].toFloat())))
        }
        val mx = x(markerAngle.coerceIn(0f, 180f))
        drawLine(marker, Offset(mx, pad), Offset(mx, pad + h), strokeWidth = 2.dp.toPx())
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0° (접힘)", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("180° (펼침)", style = MaterialTheme.typography.labelSmall)
    }
}
