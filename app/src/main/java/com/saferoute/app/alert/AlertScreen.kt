package com.saferoute.app.alert

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathEffect
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
private val HAZARD_LABELS = mapOf("earthquake" to "지진", "flood" to "홍수")

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
    onCall119: () -> Unit,
) {
    val rule = rules.firstOrNull { it.hazard == hazard }
    val accent = HAZARD_COLORS[hazard] ?: Color.DarkGray
    // One screen, no navigation: what to do now on top, preparation folded away at the bottom.
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())) {
        // Edge-to-edge: the hazard colour runs under the status bar, the buttons stay below it.
        Column(Modifier.fillMaxWidth().background(accent).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { r ->
                    val selected = r.hazard == hazard
                    Button(
                        onClick = { onHazard(r.hazard) },
                        modifier = Modifier.weight(1f).height(56.dp).testTag("hazard_${r.hazard}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color.White else Color.White.copy(alpha = 0.18f),
                            contentColor = if (selected) accent else Color.White,
                        ),
                    ) { Text(HAZARD_LABELS[r.hazard] ?: r.hazard, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            if (rule != null) {
                Spacer(Modifier.height(10.dp))
                Text(rule.verbKo, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text(rule.verbEn, color = Color.White, fontSize = 14.sp)
                Text("⚠ ${rule.forbidKo}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        when (result) {
            is AlertResult.Ok -> {
                GuidanceView(result.guidance, headingDeg, accent, stepFree)
                ActionRow(accent, onCall119)
                prepared?.let { PreparedPlanCard(it, accent) }
                RouteMap(result.guidance, accent, fix)
                QaSection(qa, onAsk, onMic, accent)
                Text("오프라인 계산 %.1f ms · 네트워크 사용 안 함 · 출처: %s".format(result.guidance.computeMs, rule?.source ?: ""),
                    fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp).testTag("compute_ms"))
            }
            else -> {
                Message(when (result) {
                    AlertResult.OutsideArea -> "여기는 이 앱의 오프라인 지도(서울) 밖이에요. 재난문자와 현지 안내를 따르세요.\nYou are outside this app's offline map (Seoul). Follow official alerts."
                    AlertResult.FarFromRoads -> "주변에 보행로 데이터가 없어요. 넓은 길 쪽으로 이동하세요.\nNo walkable road data nearby."
                    AlertResult.NoStepFreeRoute -> "계단 없이 갈 수 있는 경로가 없어요. 아래 '준비·설정'에서 계단 없는 경로를 끄면 가장 가까운 경로를 안내해요.\nNo step-free route from here."
                    else -> if (fix == null) "위치를 찾는 중… (아래 '준비·설정'에서 데모 위치를 고를 수 있어요)" else "계산 중…"
                })
                ActionRow(accent, onCall119)
            }
        }

        SettingsSection(stepFree, onStepFree, withChild, onWithChild, fix, places, onPlace, prepare, onPrepare,
            learn, onSimulateWeek, onLearnNow)
    }
}

@Composable
private fun ActionRow(accent: Color, onCall119: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCall119, modifier = Modifier.weight(1f).height(52.dp).testTag("call119")) {
            Text("📞 119 신고", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

/** Everyday preparation and personal settings: never needed during an alert, so folded by default. */
@Composable
private fun SettingsSection(
    stepFree: Boolean, onStepFree: (Boolean) -> Unit, withChild: Boolean, onWithChild: (Boolean) -> Unit,
    fix: Fix?, places: List<Fix>, onPlace: (Fix) -> Unit, prepare: PrepareUi, onPrepare: () -> Unit,
    learn: LearnUi, onSimulateWeek: () -> Unit, onLearnNow: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 14.dp).testTag("settings_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("준비 · 설정", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            val on = listOfNotNull("계단 없는 경로".takeIf { stepFree }, "아이와 함께".takeIf { withChild })
            if (on.isNotEmpty()) Text(on.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
            Text(if (open) "  ▲" else "  ▼", fontSize = 15.sp)
        }
        if (open) {
            SettingRow("계단 없는 경로 (Step-free, $STEP_FREE_MPS m/s)", stepFree, onStepFree, "stepfree")
            SettingRow("아이와 함께 (계획에 반영)", withChild, onWithChild, "withchild")
            Spacer(Modifier.height(8.dp))
            PrepareSection(prepare, onPrepare)
            Spacer(Modifier.height(12.dp))
            LearnSection(learn, onSimulateWeek, onLearnNow)
            Spacer(Modifier.height(12.dp))
            Text("데모 위치 · 지금: ${fix?.label ?: "없음"}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                places.forEach { p ->
                    FilterChip(selected = fix == p, onClick = { onPlace(p) }, label = { Text(p.label.substringBefore(" (")) })
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = null, modifier = Modifier.testTag(tag))
        Spacer(Modifier.size(10.dp))
        Text(label)
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
    val focus = LocalFocusManager.current
    // Hide the keyboard on ask, otherwise it covers the answer.
    fun ask() { if (text.isNotBlank()) { focus.clearFocus(); onAsk(text) } }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        // Answer first: it is what the person is looking for after tapping a question.
        ui.answer?.let {
            Column(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.08f)).padding(12.dp)) {
                ui.asked?.let { q -> Text("Q. $q", fontSize = 14.sp, color = Color.Gray) }
                Text(it, fontSize = 19.sp, color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("qa_answer"))
            }
            Spacer(Modifier.height(8.dp))
        }
        Text("궁금한 점 (오프라인)", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.quick.forEach { q -> AssistChip(onClick = { onAsk(q) }, label = { Text(q, fontSize = 15.sp) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("예: 차 타도 돼요?") }, modifier = Modifier.weight(1f).testTag("qa_input"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ask() }))
            Spacer(Modifier.size(8.dp))
            Button(onClick = { ask() }) { Text("묻기") }
            Spacer(Modifier.size(4.dp))
            Button(onClick = onMic, modifier = Modifier.testTag("mic")) { Text(if (ui.listening) "■ 멈춤" else "🎤") }
        }
        ui.voiceStatus?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
    }
}

/** Shown on the alert screen when a plan for this place was prepared earlier (offline read). */
data class PreparedPlanUi(val placeLabel: String, val notesKo: String, val backupName: String?, val meetingName: String?)

data class PrepareUi(val running: Boolean = false, val log: List<String> = emptyList(), val status: String? = null)

@Composable
private fun PreparedPlanCard(p: PreparedPlanUi, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(Color(0xFFF7F2FA)).padding(12.dp)
        .testTag("prepared_plan")) {
        Text("미리 준비한 계획 · ${p.placeLabel}", fontWeight = FontWeight.Bold, color = accent)
        Text(p.notesKo, fontSize = 16.sp)
        p.backupName?.let { Text("예비 대피소: $it", fontSize = 14.sp) }
        p.meetingName?.let { Text("가족 재회 지점: $it", fontSize = 14.sp) }
    }
}

@Composable
private fun PrepareSection(ui: PrepareUi, onPrepare: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(12.dp)) {
        Text("평시 준비 (온라인일 때) · Muse Spark 에이전트", fontWeight = FontWeight.Bold)
        Button(onClick = onPrepare, enabled = !ui.running, modifier = Modifier.testTag("prepare")) {
            Text(if (ui.running) "계획 만드는 중…" else "이 장소 계획 만들기")
        }
        ui.status?.let { Text(it, fontSize = 13.sp) }
        ui.log.forEach { Text("· $it", fontSize = 11.sp, color = Color.DarkGray) }
    }
}

@Composable
private fun GuidanceView(g: Guidance, headingDeg: Float?, accent: Color, stepFree: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // With a compass the arrow is relative to where the phone points; without one it is north-up.
            val arrowDeg = g.bearingDeg - (headingDeg ?: 0f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(112.dp).testTag("arrow")) {
                    rotate(arrowDeg) {
                        val w = size.width
                        val path = Path().apply {
                            moveTo(w / 2, 0f); lineTo(w * 0.88f, w * 0.52f); lineTo(w * 0.62f, w * 0.52f)
                            lineTo(w * 0.62f, w); lineTo(w * 0.38f, w); lineTo(w * 0.38f, w * 0.52f)
                            lineTo(w * 0.12f, w * 0.52f); close()
                        }
                        drawPath(path, accent)
                    }
                }
                Text(if (headingDeg == null) "북쪽 기준" else "휴대폰 방향", fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${g.distanceM} m", fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp,
                    modifier = Modifier.testTag("distance"))
                Text("걸어서 약 ${g.walkMinutes}분" + if (stepFree) " · 계단 없이" else "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(g.shelter.name, fontSize = 22.sp, color = accent, fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                    modifier = Modifier.testTag("shelter_name"))
            }
        }
        Text(g.shelter.source, fontSize = 11.sp, color = Color.Gray)
        if (stepFree) Text("계단 없는 경로 기준", fontSize = 12.sp, color = Color.Gray)
        g.lockWarning?.let { LockWarningCard(it) }
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
    val m = g.map
    val alt = g.lockWarning?.alternative?.shelter
    Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds().background(Color(0xFFF7F5F0)).testTag("route_map")) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val minLat = m?.minLat ?: pts.minOf { it.first }; val maxLat = m?.maxLat ?: pts.maxOf { it.first }
            val minLon = m?.minLon ?: pts.minOf { it.second }; val maxLon = m?.maxLon ?: pts.maxOf { it.second }
            val kx = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanY = (maxLat - minLat).coerceAtLeast(1e-5)
            val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-5)
            val scale = minOf(size.width / spanX, size.height / spanY).toFloat()
            fun p(ll: Pair<Double, Double>) = Offset(
                ((ll.second - minLon) * kx * scale).toFloat() + (size.width - (spanX * scale).toFloat()) / 2,
                size.height - ((ll.first - minLat) * scale).toFloat() - (size.height - (spanY * scale).toFloat()) / 2,
            )
            m?.streets?.forEach { (a, b) -> drawLine(Color(0xFFCFCAC0), p(a), p(b), strokeWidth = 3f) }
            m?.shelters?.forEach { s ->
                if (s.idx != g.shelter.idx && s.idx != alt?.idx)
                    drawCircle(accent.copy(alpha = 0.45f), radius = 9f, center = p(s.lat to s.lon), style = Stroke(width = 4f))
            }
            alt?.let { drawCircle(Color(0xFFE0A100), radius = 16f, center = p(it.lat to it.lon)) }
            val path = Path().apply { pts.forEachIndexed { i, ll -> val o = p(ll); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(path, accent, style = Stroke(width = 9f))
            // The route ends at the street node nearest the shelter; show the last few metres too.
            drawLine(accent, p(pts.last()), p(g.shelter.lat to g.shelter.lon), strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawCircle(Color.White, radius = 17f, center = p(pts.first()))
            drawCircle(Color(0xFF1565C0), radius = 12f, center = p(pts.first()))
            drawCircle(accent, radius = 20f, center = p(g.shelter.lat to g.shelter.lon))
        }
        Text("오프라인 지도 · 북쪽 위   🔵 나  🔴 목적지  ○ 대피소" + if (alt != null) "  🟡 대안" else "",
            fontSize = 11.sp, color = Color.DarkGray,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(text, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
}
