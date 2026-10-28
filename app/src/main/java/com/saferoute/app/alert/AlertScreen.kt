package com.saferoute.app.alert

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saferoute.app.data.HazardRule
import com.saferoute.app.location.Fix
import kotlin.math.cos

private val HAZARD_COLORS = mapOf("earthquake" to Color(0xFFB3261E), "flood" to Color(0xFF0B57D0))
private val HAZARD_LABELS = mapOf("earthquake" to "지진 Earthquake", "flood" to "홍수 Flood")

@Composable
fun AlertScreen(
    rules: List<HazardRule>,
    hazard: String,
    onHazard: (String) -> Unit,
    stepFree: Boolean,
    onStepFree: (Boolean) -> Unit,
    fix: Fix?,
    places: List<Fix>,
    onPlace: (Fix) -> Unit,
    result: AlertResult?,
    headingDeg: Float?,
    prepared: PreparedPlanUi?,
    prepare: PrepareUi,
    onPrepare: () -> Unit,
    withChild: Boolean,
    onWithChild: (Boolean) -> Unit,
    qa: QaUi,
    onAsk: (String) -> Unit,
    onMic: () -> Unit,
    learn: LearnUi,
    onSimulateWeek: () -> Unit,
    onLearnNow: () -> Unit,
) {
    val rule = rules.firstOrNull { it.hazard == hazard }
    val accent = HAZARD_COLORS[hazard] ?: Color.DarkGray
    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.fillMaxWidth().background(accent).padding(16.dp)) {
            Text("SafeRoute · 오프라인 대피 안내", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { r ->
                    FilterChip(
                        selected = r.hazard == hazard,
                        onClick = { onHazard(r.hazard) },
                        label = { Text(HAZARD_LABELS[r.hazard] ?: r.hazard, fontSize = 18.sp) },
                        modifier = Modifier.testTag("hazard_${r.hazard}"),
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            selectedContainerColor = Color.White,
                            selectedLabelColor = accent,
                        ),
                    )
                }
            }
            if (rule != null) {
                Spacer(Modifier.height(12.dp))
                Text(rule.verbKo, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(rule.verbEn, color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("⚠ ${rule.forbidKo}", color = Color.White, fontSize = 16.sp)
            }
        }

        when (result) {
            is AlertResult.Ok -> {
                GuidanceView(result.guidance, headingDeg, accent, fix, stepFree)
                prepared?.let { PreparedPlanCard(it, accent) }
                QaSection(qa, onAsk, onMic, accent)
            }
            AlertResult.FarFromRoads -> Message("주변에 보행로 데이터가 없어요. 넓은 길 쪽으로 이동하세요.\nNo walkable road data nearby.")
            AlertResult.NoStepFreeRoute -> Message("계단 없이 갈 수 있는 경로가 없어요. 계단 없는 경로를 끄면 가장 가까운 경로를 안내해요.\nNo step-free route from here.")
            null -> Message(if (fix == null) "위치를 찾는 중… 아래에서 데모 위치를 고를 수 있어요." else "계산 중…")
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = stepFree, onCheckedChange = onStepFree, modifier = Modifier.testTag("stepfree"))
                Spacer(Modifier.size(8.dp))
                Text("계단 없는 경로 (Step-free, ${STEP_FREE_MPS} m/s)")
            }
            Spacer(Modifier.height(8.dp))
            Text("위치: ${fix?.label ?: "없음"}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                places.forEach { p ->
                    FilterChip(selected = fix == p, onClick = { onPlace(p) }, label = { Text(p.label.substringBefore(" (")) })
                }
            }
            if (rule != null) {
                Spacer(Modifier.height(8.dp))
                Text("행동요령 출처: ${rule.source}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(Modifier.height(16.dp))
            PrepareSection(prepare, onPrepare, withChild, onWithChild)
            Spacer(Modifier.height(12.dp))
            LearnSection(learn, onSimulateWeek, onLearnNow)
        }
    }
}

data class LearnedPlaceUi(val label: String, val days: Int, val planned: List<Pair<String, Boolean>>)

data class LearnUi(val visits: Int = 0, val places: List<LearnedPlaceUi> = emptyList(), val running: Boolean = false, val status: String? = null)

/** Regular places learned on the device and whether each hazard already has a prepared plan. */
@Composable
private fun LearnSection(ui: LearnUi, onSimulateWeek: () -> Unit, onLearnNow: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(12.dp).testTag("learn")) {
        Text("자주 가는 곳 (기기에서만 학습) · 위치 기록 ${ui.visits}건", fontWeight = FontWeight.Bold)
        if (ui.places.isEmpty()) Text("아직 3일 이상 머문 곳이 없어요.", fontSize = 13.sp)
        ui.places.forEach { p ->
            val marks = p.planned.joinToString("  ") { (h, ok) -> "${HAZARD_LABELS[h]?.substringBefore(" ") ?: h} ${if (ok) "✓" else "–"}" }
            Text("${p.label} · ${p.days}일 · $marks", fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSimulateWeek, enabled = !ui.running) { Text("일주일 이동 (데모)") }
            Button(onClick = onLearnNow, enabled = !ui.running, modifier = Modifier.testTag("learn_now")) {
                Text(if (ui.running) "계획 만드는 중…" else "지금 계획 갱신")
            }
        }
        ui.status?.let { Text(it, fontSize = 13.sp) }
    }
}

data class QaUi(
    val quick: List<String> = emptyList(),
    val asked: String? = null,
    val answer: String? = null,
    val listening: Boolean = false,
    val voiceStatus: String? = null,
)

/** Offline questions: matched to pre-generated, checked answers (no model runs on the phone). */
@Composable
private fun QaSection(ui: QaUi, onAsk: (String) -> Unit, onMic: () -> Unit, accent: Color) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("궁금한 점 (오프라인)", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.quick.forEach { q -> AssistChip(onClick = { onAsk(q) }, label = { Text(q, fontSize = 13.sp) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("예: 차 타도 돼요?") }, modifier = Modifier.weight(1f).testTag("qa_input"))
            Spacer(Modifier.size(8.dp))
            Button(onClick = { if (text.isNotBlank()) onAsk(text) }) { Text("묻기") }
            Spacer(Modifier.size(4.dp))
            Button(onClick = onMic, modifier = Modifier.testTag("mic")) { Text(if (ui.listening) "■ 멈춤" else "🎤") }
        }
        ui.voiceStatus?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
        ui.asked?.let { Text("Q. $it", fontSize = 14.sp, color = Color.Gray) }
        ui.answer?.let { Text(it, fontSize = 18.sp, color = accent, modifier = Modifier.testTag("qa_answer")) }
    }
}

/** Shown on the alert screen when a plan for this place was prepared earlier (offline read). */
data class PreparedPlanUi(val placeLabel: String, val notesKo: String, val backupName: String?, val meetingName: String?)

data class PrepareUi(val running: Boolean = false, val log: List<String> = emptyList(), val status: String? = null)

@Composable
private fun PreparedPlanCard(p: PreparedPlanUi, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color(0xFFF7F2FA)).padding(12.dp)
        .testTag("prepared_plan")) {
        Text("미리 준비한 계획 · ${p.placeLabel}", fontWeight = FontWeight.Bold, color = accent)
        Text(p.notesKo, fontSize = 16.sp)
        p.backupName?.let { Text("예비 대피소: $it", fontSize = 14.sp) }
        p.meetingName?.let { Text("가족 재회 지점: $it", fontSize = 14.sp) }
    }
}

@Composable
private fun PrepareSection(ui: PrepareUi, onPrepare: () -> Unit, withChild: Boolean, onWithChild: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(12.dp)) {
        Text("평시 준비 (온라인일 때) · Muse Spark 에이전트", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = withChild, onCheckedChange = onWithChild)
            Spacer(Modifier.size(8.dp))
            Text("아이와 함께")
        }
        Button(onClick = onPrepare, enabled = !ui.running, modifier = Modifier.testTag("prepare")) {
            Text(if (ui.running) "계획 만드는 중…" else "이 장소 계획 만들기")
        }
        ui.status?.let { Text(it, fontSize = 13.sp) }
        ui.log.forEach { Text("· $it", fontSize = 11.sp, color = Color.DarkGray) }
    }
}

@Composable
private fun GuidanceView(g: Guidance, headingDeg: Float?, accent: Color, fix: Fix?, stepFree: Boolean) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // With a compass the arrow is relative to where the phone points; without one it is north-up.
        val arrowDeg = g.bearingDeg - (headingDeg ?: 0f)
        Canvas(Modifier.size(140.dp).testTag("arrow")) {
            rotate(arrowDeg) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w / 2, 0f); lineTo(w * 0.85f, w * 0.55f); lineTo(w * 0.6f, w * 0.55f)
                    lineTo(w * 0.6f, w); lineTo(w * 0.4f, w); lineTo(w * 0.4f, w * 0.55f)
                    lineTo(w * 0.15f, w * 0.55f); close()
                }
                drawPath(path, accent)
            }
        }
        Text(if (headingDeg == null) "북쪽 기준 (나침반 없음)" else "휴대폰 방향 기준", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text("${g.distanceM} m · 도보 약 ${g.walkMinutes}분", fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("distance"))
        Text(g.shelter.name, fontSize = 22.sp, modifier = Modifier.testTag("shelter_name"))
        Text(g.shelter.source, fontSize = 12.sp, color = Color.Gray)
        g.lockWarning?.let { LockWarningCard(it) }
        Spacer(Modifier.height(12.dp))
        RouteMap(g, accent, fix)
        Text("오프라인 계산 %.1f ms · 네트워크 사용 안 함".format(g.computeMs), fontSize = 12.sp, color = Color.Gray,
            modifier = Modifier.testTag("compute_ms"))
        if (stepFree) Text("계단 없는 경로 기준", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun LockWarningCard(w: LockWarning) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(0xFFFFF3CD)).padding(12.dp).testTag("lock_warning"),
    ) {
        Text("⚠ 학교 운동장은 밤·주말에 문이 잠겨 있을 수 있어요", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("School gates may be locked at night and on weekends.", fontSize = 12.sp, color = Color.DarkGray)
        val alt = w.alternative
        if (alt == null) Text("주변에 학교가 아닌 대피 장소가 없어요. 잠겨 있으면 주변 넓은 공터로 가세요.", fontSize = 14.sp)
        else Text("잠겨 있으면 → ${alt.shelter.name} · ${alt.distanceM} m · 도보 약 ${alt.walkMinutes}분",
            fontSize = 15.sp, modifier = Modifier.testTag("lock_alternative"))
    }
}

@Composable
private fun RouteMap(g: Guidance, accent: Color, fix: Fix?) {
    val pts = buildList { fix?.let { add(it.lat to it.lon) }; addAll(g.routeLatLon) }
    Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFFF2F2F2))) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
            val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
            val kx = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanY = (maxLat - minLat).coerceAtLeast(1e-5)
            val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-5)
            val scale = minOf(size.width / spanX, size.height / spanY).toFloat()
            fun p(ll: Pair<Double, Double>) = Offset(
                ((ll.second - minLon) * kx * scale).toFloat() + (size.width - (spanX * scale).toFloat()) / 2,
                size.height - ((ll.first - minLat) * scale).toFloat() - (size.height - (spanY * scale).toFloat()) / 2,
            )
            val path = Path().apply { pts.forEachIndexed { i, ll -> val o = p(ll); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(path, accent, style = Stroke(width = 8f))
            drawCircle(Color.Black, radius = 14f, center = p(pts.first()))
            drawCircle(accent, radius = 20f, center = p(pts.last()))
        }
        Text("경로 (북쪽 위)", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(text, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
}
