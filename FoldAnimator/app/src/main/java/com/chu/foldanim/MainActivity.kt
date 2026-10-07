@file:OptIn(ExperimentalMaterial3Api::class)

package com.chu.foldanim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var config by mutableStateOf(FoldConfig())
    private var overlayGranted by mutableStateOf(false)
    private var pendingEnable = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = FoldSettings.load(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        config = config,
                        overlayGranted = overlayGranted,
                        onEnabledChange = ::setEnabled,
                        onConfigChange = ::update,
                        onRequestOverlay = ::openOverlaySettings,
                        onTest = { FoldAnimationService.start(this, FoldAnimationService.ACTION_TEST) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 알림의 "끄기" 버튼 등으로 바뀌었을 수 있으니 다시 읽습니다.
        config = FoldSettings.load(this)
        overlayGranted = Settings.canDrawOverlays(this)
        if (pendingEnable && overlayGranted) {
            pendingEnable = false
            setEnabled(true)
        } else if (config.enabled && overlayGranted) {
            FoldAnimationService.start(this)
        }
    }

    private fun update(newConfig: FoldConfig) {
        config = newConfig
        FoldSettings.save(this, newConfig)
    }

    private fun setEnabled(on: Boolean) {
        if (!on) {
            update(config.copy(enabled = false))
            FoldAnimationService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingEnable = true
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        update(config.copy(enabled = true))
        FoldAnimationService.start(this)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 힌지 각도 센서. 센서가 없으면 null 상태로 남습니다. */
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
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return (sensor != null) to angle
}

@Composable
private fun SettingsScreen(
    config: FoldConfig,
    overlayGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigChange: (FoldConfig) -> Unit,
    onRequestOverlay: () -> Unit,
    onTest: () -> Unit,
) {
    val (hasHinge, liveAngle) = rememberHingeAngle()
    var followLive by remember { mutableStateOf(true) }
    var simAngle by remember { mutableFloatStateOf(90f) }
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

        // ── 켜기/끄기
        SectionCard {
            SwitchRow(
                title = "접기/펼치기 애니메이션",
                subtitle = if (config.enabled) "켜짐 · 백그라운드에서 동작 중" else "꺼짐",
                checked = config.enabled,
                onCheckedChange = onEnabledChange,
            )
            if (!overlayGranted) {
                Text(
                    "‘다른 앱 위에 표시’ 권한이 필요합니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRequestOverlay) { Text("권한 허용하러 가기") }
            }
            if (!hasHinge) {
                Text(
                    "이 기기에서 힌지 각도 센서를 찾을 수 없습니다. 각도 연동 없이 화면 전환 애니메이션만 동작합니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onTest, enabled = config.enabled && overlayGranted) { Text("애니메이션 테스트 재생") }
        }

        // ── 미리보기
        SectionCard(title = "미리보기") {
            Text(
                buildString {
                    append("현재 힌지 각도: ")
                    append(liveAngle?.let { "${it.roundToInt()}°" } ?: "-")
                    append("   →   화면 표시 ")
                    append("${(config.revealAt(previewAngle) * 100).roundToInt()}%")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            MaskPreview(
                config = config,
                angle = previewAngle,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(260.dp)
                    .height(220.dp),
            )
            if (hasHinge) {
                SwitchRow(
                    title = "실제 힌지 각도 따라가기",
                    checked = followLive,
                    onCheckedChange = { followLive = it },
                )
            }
            if (!followLive || liveAngle == null) {
                Text("시뮬레이션 각도: ${simAngle.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
                Slider(value = simAngle, onValueChange = { simAngle = it }, valueRange = 0f..180f)
            }
        }

        // ── 언제 재생할지
        SectionCard(title = "재생 시점") {
            SwitchRow(
                title = "펼칠 때",
                checked = config.onUnfold,
                onCheckedChange = { onConfigChange(config.copy(onUnfold = it)) },
            )
            SwitchRow(
                title = "접을 때",
                checked = config.onFold,
                onCheckedChange = { onConfigChange(config.copy(onFold = it)) },
            )
            SwitchRow(
                title = "접은 뒤 커버 화면 열림 효과",
                subtitle = "커버 화면으로 넘어갈 때 짧게 열리는 효과",
                checked = config.coverReveal,
                enabled = config.onFold,
                onCheckedChange = { onConfigChange(config.copy(coverReveal = it)) },
            )
            SwitchRow(
                title = "빠르게 펼쳐도 처음부터 재생",
                subtitle = "메인 화면이 켜질 때 항상 닫힌 상태(0%)에서 시작",
                checked = config.alwaysFromClosed,
                enabled = config.onUnfold,
                onCheckedChange = { onConfigChange(config.copy(alwaysFromClosed = it)) },
            )
        }

        // ── 모양
        SectionCard(title = "애니메이션 스타일") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimStyle.entries.forEach { style ->
                    FilterChip(
                        selected = config.style == style,
                        onClick = { onConfigChange(config.copy(style = style)) },
                        label = { Text(style.label) },
                    )
                }
            }
            LabeledSlider(
                label = "부드러움(속도)",
                valueText = "${config.durationMs}ms",
                value = config.durationMs.toFloat(),
                range = 150f..1000f,
                onChange = { onConfigChange(config.copy(durationMs = it.roundToInt())) },
            )
            LabeledSlider(
                label = "모서리 둥글기",
                valueText = "${config.cornerDp}dp",
                value = config.cornerDp.toFloat(),
                range = 0f..60f,
                onChange = { onConfigChange(config.copy(cornerDp = it.roundToInt())) },
            )
        }

        // ── 각도별 화면 표시 %
        SectionCard(title = "힌지 각도별 화면 표시 %") {
            Text(
                "펼쳐진 각도에 따라 화면을 몇 % 보여줄지 정합니다. 사이 각도는 자연스럽게 이어집니다.",
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
                FoldSettings.PRESETS.forEach { (name, curve) ->
                    FilterChip(
                        selected = config.curve == curve,
                        onClick = { onConfigChange(config.copy(curve = curve)) },
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

        Text(
            "참고: 일반 앱은 시스템 화면 전환 자체를 바꿀 수 없어서, 화면 위에 마스크를 덮어 애니메이션을 만듭니다. " +
                "애니메이션이 재생되는 짧은 동안에는 터치가 막힐 수 있고, 상태바·잠금화면 위에는 표시되지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
    enabled: Boolean = true,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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

/** 메인 화면 모형 위에 실제 오버레이와 같은 마스크를 그려 보여줍니다. */
@Composable
private fun MaskPreview(config: FoldConfig, angle: Float, modifier: Modifier = Modifier) {
    val renderer = remember { FoldMaskRenderer() }
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val radius = 18.dp.toPx()
        val frame = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        }
        clipPath(frame) {
            // 가짜 화면 내용
            drawRect(Brush.linearGradient(listOf(accent, accent2), Offset.Zero, Offset(size.width, size.height)))
            val tile = size.width / 4f
            for (row in 0 until 3) {
                for (col in 0 until 4) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = tile * 0.22f,
                        center = Offset(tile * (col + 0.5f), tile * 0.6f + row * tile * 0.75f),
                    )
                }
            }
            drawIntoCanvas {
                renderer.draw(
                    canvas = it.nativeCanvas,
                    width = size.width,
                    height = size.height,
                    progress = config.revealAt(angle),
                    style = config.style,
                    cornerPx = config.cornerDp.dp.toPx() * 0.5f,
                    edgePx = 1.dp.toPx(),
                    hingeVertical = true,
                )
            }
        }
        // 힌지 위치 표시
        drawLine(
            color = outline,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
        )
        drawPath(frame, color = outline, style = Stroke(width = 2.dp.toPx()))
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
