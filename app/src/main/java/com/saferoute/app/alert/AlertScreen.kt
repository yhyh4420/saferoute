package com.saferoute.app.alert

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
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
private val ME_COLOR = Color(0xFF212121)
private val ALT_COLOR = Color(0xFFE0A100)
private val HAZARD_LABELS = mapOf("earthquake" to "지진", "flood" to "홍수")
/**
 * The demo-week button wipes the learned history before inserting fake visits: only on emulators,
 * never on a phone that is learning real places.
 */
private val IS_EMULATOR = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
    android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.PRODUCT.contains("sdk")
private val HAZARD_LABELS_EN = mapOf("earthquake" to "Earthquake", "flood" to "Flood")
/** English for labels the learner produces (learn/PlaceLearner.kt). */
private val PLACE_LABELS_EN = mapOf("집 (추정)" to "Home (likely)", "직장·학교 (추정)" to "Work/School (likely)",
    "자주 가는 곳" to "Frequent place", "지나간 곳" to "Passed by", "이 근처" to "Near here")
fun placeLabelBi(ko: String) = PLACE_LABELS_EN[ko]?.let { "$ko · $it" } ?: ko

/** Korean with English underneath (every Korean text on this screen is shown in both languages). */
@Composable
private fun Bi(ko: String, en: String, size: TextUnit = 14.sp, weight: FontWeight? = null, color: Color = Color.Unspecified,
               modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(ko, fontSize = size, fontWeight = weight, color = color, lineHeight = size * 1.25f)
        Text(en, fontSize = size * 0.78f, color = if (color == Color.Unspecified) Color.DarkGray else color.copy(alpha = 0.85f),
            lineHeight = size * 0.98f)
    }
}

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
    /** Learned regular places: a one-tap start point while GPS is still searching. */
    knownPlaces: List<Fix> = emptyList(),
    /** Location is off system-wide (or not permitted): searching would never end. */
    locationOff: Boolean = false,
    onTurnOnLocation: () -> Unit = {},
    fieldLog: Boolean = false,
    fieldLogLines: Int = 0,
    onFieldLog: (Boolean) -> Unit = {},
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
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(HAZARD_LABELS[r.hazard] ?: r.hazard, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                            Text(HAZARD_LABELS_EN[r.hazard] ?: r.hazard, fontSize = 12.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
            if (rule != null) {
                Spacer(Modifier.height(10.dp))
                Text(rule.verbKo, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text(rule.verbEn, color = Color.White, fontSize = 14.sp)
                Text("⚠ ${rule.forbidKo}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("⚠ ${rule.forbidEn}", color = Color.White, fontSize = 13.sp)
            }
        }

        when (result) {
            is AlertResult.Ok -> {
                GuidanceView(result.guidance, headingDeg, accent, stepFree)
                ActionRow(accent, onCall119)
                prepared?.let { PreparedPlanCard(it, accent) }
                RouteMap(result.guidance, accent, fix)
                QaSection(qa, onAsk, onMic, accent)
                Text("오프라인 계산 Offline compute %.1f ms · 네트워크 사용 안 함 No network · 대피소 Shelters: %s · 행동요령 Guidance: %s".format(
                    result.guidance.computeMs, result.guidance.shelter.source, rule?.source ?: ""),
                    fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp).testTag("compute_ms"))
            }
            else -> {
                val (ko, en) = when (result) {
                    AlertResult.OutsideArea -> "여기는 이 앱의 오프라인 지도(서울) 밖이에요. 재난문자와 현지 안내를 따르세요." to
                        "You are outside this app's offline map (Seoul). Follow official alerts and local guidance."
                    AlertResult.FarFromRoads -> "주변에 보행로 데이터가 없어요. 넓은 길 쪽으로 이동하세요." to
                        "No walkable road data nearby. Move towards a wider street."
                    AlertResult.NoStepFreeRoute -> "계단 없이 갈 수 있는 경로가 없어요. 아래 '준비·설정'에서 계단 없는 경로를 끄면 가장 가까운 경로를 안내해요." to
                        "No step-free route from here. Turn off step-free in 'Prepare · Settings' below for the nearest route."
                    else -> when {
                        fix != null -> "계산 중…" to "Calculating…"
                        locationOff -> "위치가 꺼져 있어서 현재 위치를 알 수 없어요." to "Location is off, so your position is unknown."
                        else -> "위치를 찾는 중…" to "Finding your location…"
                    }
                }
                Message(ko, en)
                if (fix == null && locationOff) {
                    Button(onClick = onTurnOnLocation, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)) {
                        Text("위치 켜기 · Turn on location", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (fix == null && knownPlaces.isNotEmpty()) {
                    Bi("GPS를 기다리지 않고 바로 안내받기 — 지금 여기예요:", "Don't wait for GPS — I'm here now:", 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        knownPlaces.forEach { p ->
                            Button(onClick = { onPlace(p) }, modifier = Modifier.height(52.dp)) { Text(placeLabelBi(p.label), fontSize = 16.sp) }
                        }
                    }
                }
                ActionRow(accent, onCall119)
            }
        }

        SettingsSection(stepFree, onStepFree, withChild, onWithChild, fix, places, onPlace, prepare, onPrepare,
            learn, onSimulateWeek, onLearnNow, fieldLog, fieldLogLines, onFieldLog)
    }
}

@Composable
private fun ActionRow(accent: Color, onCall119: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCall119, modifier = Modifier.weight(1f).height(52.dp).testTag("call119")) {
            Text("📞 119 신고 · Call 119", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

/** Everyday preparation and personal settings: never needed during an alert, so folded by default. */
@Composable
private fun SettingsSection(
    stepFree: Boolean, onStepFree: (Boolean) -> Unit, withChild: Boolean, onWithChild: (Boolean) -> Unit,
    fix: Fix?, places: List<Fix>, onPlace: (Fix) -> Unit, prepare: PrepareUi, onPrepare: () -> Unit,
    learn: LearnUi, onSimulateWeek: () -> Unit, onLearnNow: () -> Unit,
    fieldLog: Boolean, fieldLogLines: Int, onFieldLog: (Boolean) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 14.dp).testTag("settings_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Bi("준비 · 설정", "Prepare · Settings", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
            val on = listOfNotNull("계단 없음 Step-free".takeIf { stepFree }, "아이와 함께 With child".takeIf { withChild })
            if (on.isNotEmpty()) Text(on.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
            Text(if (open) "  ▲" else "  ▼", fontSize = 15.sp)
        }
        if (open) {
            SettingRow("계단 없는 경로", "Step-free route ($STEP_FREE_MPS m/s)", stepFree, onStepFree, "stepfree")
            SettingRow("아이와 함께 (계획에 반영)", "With a child (used in plans)", withChild, onWithChild, "withchild")
            SettingRow("현장 테스트 기록 (폰에만 저장 · ${fieldLogLines}줄)", "Field-test log (on this phone only · $fieldLogLines lines)",
                fieldLog, onFieldLog, "fieldlog")
            Spacer(Modifier.height(8.dp))
            PrepareSection(prepare, onPrepare)
            Spacer(Modifier.height(12.dp))
            LearnSection(learn, onSimulateWeek, onLearnNow)
            Spacer(Modifier.height(12.dp))
            Text("데모 위치 Demo location · 지금 Now: ${fix?.label ?: "없음 none"}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                places.forEach { p ->
                    FilterChip(selected = fix == p, onClick = { onPlace(p) }, label = { Text(p.label.substringBefore(" (")) })
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, labelEn: String, value: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = null, modifier = Modifier.testTag(tag))
        Spacer(Modifier.size(10.dp))
        Bi(label, labelEn, 15.sp)
    }
}

data class LearnedPlaceUi(val label: String, val days: Int, val planned: List<Pair<String, Boolean>>)

data class LearnUi(val visits: Int = 0, val places: List<LearnedPlaceUi> = emptyList(), val running: Boolean = false, val status: String? = null,
                   val total: Int = 0)

/** Regular places learned on the device and whether each hazard already has a prepared plan. */
@Composable
private fun LearnSection(ui: LearnUi, onSimulateWeek: () -> Unit, onLearnNow: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(12.dp).testTag("learn")) {
        Bi("자주 가는 곳 (기기에서만 학습) · 위치 기록 ${ui.visits}건", "Learned places (on this phone only) · ${ui.visits} samples",
            15.sp, FontWeight.Bold)
        if (ui.places.isEmpty()) Bi("아직 기록된 장소가 없어요.", "No places recorded yet.", 13.sp)
        else Bi("학습한 장소 ${ui.total}곳 (자주 가는 곳 먼저, 그다음 최근 지나간 곳)",
            "${ui.total} places (regular places first, then recently passed)", 13.sp)
        ui.places.forEach { p ->
            val marks = p.planned.joinToString("  ") { (h, ok) -> "${HAZARD_LABELS[h] ?: h} ${HAZARD_LABELS_EN[h] ?: ""} ${if (ok) "✓" else "–"}" }
            Text("${placeLabelBi(p.label)} · ${p.days}일 days · $marks", fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (IS_EMULATOR) Button(onClick = onSimulateWeek, enabled = !ui.running) { Text("일주일 이동 (데모)\nDemo week", fontSize = 13.sp) }
            Button(onClick = onLearnNow, enabled = !ui.running, modifier = Modifier.testTag("learn_now")) {
                Text(if (ui.running) "계획 만드는 중…\nPlanning…" else "지금 계획 갱신\nUpdate plans now", fontSize = 13.sp)
            }
        }
        ui.status?.let { Text(it, fontSize = 13.sp) }
    }
}

data class QaUi(
    /** Quick questions as (Korean, English). */
    val quick: List<Pair<String, String>> = emptyList(),
    val asked: String? = null,
    val answer: String? = null,
    /** The same pre-checked answer in the other language. */
    val answerOther: String? = null,
    val askedOther: String? = null,
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
                ui.asked?.let { q -> Text("Q. $q" + (ui.askedOther?.let { " · $it" } ?: ""), fontSize = 14.sp, color = Color.Gray) }
                Text(it, fontSize = 19.sp, color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("qa_answer"))
                ui.answerOther?.let { o -> Text(o, fontSize = 15.sp, color = accent.copy(alpha = 0.85f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Bi("궁금한 점 (오프라인)", "Questions (offline)", 16.sp, FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.quick.forEach { (q, en) ->
                AssistChip(onClick = { onAsk(q) }, label = { Bi(q, en, 15.sp, modifier = Modifier.padding(vertical = 4.dp)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("예: 차 타도 돼요? / Can I drive?") }, modifier = Modifier.weight(1f).testTag("qa_input"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ask() }))
            Spacer(Modifier.size(8.dp))
            Button(onClick = { ask() }) { Text("묻기\nAsk", fontSize = 13.sp, lineHeight = 15.sp) }
            Spacer(Modifier.size(4.dp))
            Button(onClick = onMic, modifier = Modifier.testTag("mic")) { Text(if (ui.listening) "■ 멈춤" else "🎤") }
        }
        ui.voiceStatus?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
    }
}

/** Shown on the alert screen when a plan for this place was prepared earlier (offline read). */
data class PreparedPlanUi(val placeLabel: String, val notesKo: String, val backupName: String?, val meetingName: String?,
                          val notesEn: String? = null)

data class PrepareUi(val running: Boolean = false, val log: List<String> = emptyList(), val status: String? = null)

@Composable
private fun PreparedPlanCard(p: PreparedPlanUi, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(Color(0xFFF7F2FA)).padding(12.dp)
        .testTag("prepared_plan")) {
        Bi("미리 준비한 계획 · ${placeLabelBi(p.placeLabel)}", "Prepared plan", 15.sp, FontWeight.Bold, accent)
        Text(p.notesKo, fontSize = 16.sp)
        p.notesEn?.let { Text(it, fontSize = 13.sp, color = Color.DarkGray) }
        p.backupName?.let { Text("예비 대피소 Backup: $it", fontSize = 14.sp) }
        p.meetingName?.let { Text("가족 재회 지점 Family meeting point: $it", fontSize = 14.sp) }
    }
}

@Composable
private fun PrepareSection(ui: PrepareUi, onPrepare: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(12.dp)) {
        Bi("평시 준비 (온라인일 때) · Muse Spark 에이전트", "Prepare ahead (online) · Muse Spark agent", 15.sp, FontWeight.Bold)
        Button(onClick = onPrepare, enabled = !ui.running, modifier = Modifier.testTag("prepare")) {
            Text(if (ui.running) "계획 만드는 중… · Planning…" else "이 장소 계획 만들기 · Plan for this place")
        }
        ui.status?.let { Text(it, fontSize = 13.sp) }
        ui.log.forEach { Text("· $it", fontSize = 11.sp, color = Color.DarkGray) }
    }
}

@Composable
private fun GuidanceView(g: Guidance, headingDeg: Float?, accent: Color, stepFree: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (g.outsideCity) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(12.dp).testTag("outside_city")) {
                Bi("⚠ 서울 밖이에요. 더 가까운 현지 대피소가 있을 수 있어요.", "⚠ Outside Seoul. A closer local shelter may exist.",
                    15.sp, FontWeight.Bold)
                Bi("재난문자와 현지 안내를 먼저 따르세요. 아래는 가장 가까운 서울 대피소(참고)예요.",
                    "Follow official alerts and local guidance first. Below is the nearest Seoul shelter (reference).", 14.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // With a compass the arrow is relative to where the phone points; without one it is north-up.
            val arrowDeg = g.bearingDeg - (headingDeg ?: 0f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val spoken = "목적지 방향: ${clockWords(arrowDeg)}, ${g.distanceM} m. Destination: ${clockWordsEn(arrowDeg)}, ${g.distanceM} m"
                Canvas(Modifier.size(112.dp).testTag("arrow").semantics { contentDescription = spoken }) {
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
                Text(if (headingDeg == null) "북쪽 기준\nNorth-up" else "휴대폰 방향\nPhone heading", fontSize = 11.sp,
                    color = Color.Gray, lineHeight = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${g.distanceM} m", fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp,
                    modifier = Modifier.testTag("distance"))
                Bi("걸어서 약 ${g.walkMinutes}분" + if (stepFree) " · 계단 없이" else "",
                    "About ${g.walkMinutes} min on foot" + if (stepFree) " · step-free" else "", 18.sp, FontWeight.Bold)
                Text(g.shelter.name, fontSize = 22.sp, color = accent, fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                    modifier = Modifier.testTag("shelter_name"))
                if (g.shelter.nameEn.isNotBlank()) Text(g.shelter.nameEn, fontSize = 15.sp, color = accent.copy(alpha = 0.85f),
                    lineHeight = 18.sp, modifier = Modifier.testTag("shelter_name_en"))
            }
        }
        // Full dataset names are in the footer; here only whether the site is officially designated.
        val official = !g.shelter.source.startsWith("OpenStreetMap")
        if (official) Bi("✓ 공식 지정 대피소", "✓ Officially designated shelter", 13.sp, color = Color(0xFF2E7D32))
        else Bi("공식 지정 아님 · 공식 데이터가 없는 지역의 공원", "Not officially designated · park in an area without official data",
            13.sp, color = Color(0xFF8A5A00))
        if (stepFree) Text("계단 없는 경로 기준 · Step-free route", fontSize = 12.sp, color = Color.Gray)
        g.lockWarning?.let { LockWarningCard(it) }
    }
}

/** Relative direction in words for screen readers (the arrow itself is only visual). */
private fun clockWords(deg: Float): String {
    val d = ((deg % 360) + 360) % 360
    return when {
        d < 22.5 || d >= 337.5 -> "앞쪽"
        d < 67.5 -> "오른쪽 앞"
        d < 112.5 -> "오른쪽"
        d < 157.5 -> "오른쪽 뒤"
        d < 202.5 -> "뒤쪽"
        d < 247.5 -> "왼쪽 뒤"
        d < 292.5 -> "왼쪽"
        else -> "왼쪽 앞"
    }
}

private fun clockWordsEn(deg: Float): String {
    val d = ((deg % 360) + 360) % 360
    return listOf("ahead", "ahead right", "right", "behind right", "behind", "behind left", "left", "ahead left")[
        (((d + 22.5f) % 360) / 45f).toInt().coerceIn(0, 7)]
}

@Composable
private fun LockWarningCard(w: LockWarning) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(0xFFFFF3CD)).padding(12.dp).testTag("lock_warning"),
    ) {
        Text("⚠ 학교 운동장은 밤·주말에 문이 잠겨 있을 수 있어요", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("School gates may be locked at night and on weekends.", fontSize = 12.sp, color = Color.DarkGray)
        val alt = w.alternative
        if (alt == null) Bi("주변에 학교가 아닌 대피 장소가 없어요. 잠겨 있으면 주변 넓은 공터로 가세요.",
            "No non-school shelter nearby. If locked, go to the nearest open space.", 14.sp)
        else {
            Text("잠겨 있으면 → ${alt.shelter.name} · ${alt.distanceM} m · 도보 약 ${alt.walkMinutes}분",
                fontSize = 15.sp, modifier = Modifier.testTag("lock_alternative"))
            Text("If locked → ${alt.shelter.nameEn.ifBlank { alt.shelter.name }} · ${alt.distanceM} m · about ${alt.walkMinutes} min",
                fontSize = 12.sp, color = Color.DarkGray)
        }
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
            alt?.let { drawCircle(ALT_COLOR, radius = 16f, center = p(it.lat to it.lon)) }
            val path = Path().apply { pts.forEachIndexed { i, ll -> val o = p(ll); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(path, accent, style = Stroke(width = 9f))
            // The route ends at the street node nearest the shelter; show the last few metres too.
            drawLine(accent, p(pts.last()), p(g.shelter.lat to g.shelter.lon), strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawCircle(Color.White, radius = 17f, center = p(pts.first()))
            drawCircle(ME_COLOR, radius = 12f, center = p(pts.first()))
            drawCircle(accent, radius = 20f, center = p(g.shelter.lat to g.shelter.lon))
        }
        // Legend dots use the exact colours drawn on the map (the destination takes the hazard colour).
        val legend = buildAnnotatedString {
            append("오프라인 지도 Offline map · 북쪽 위 North up  ")
            fun dot(c: Color, label: String) { withStyle(SpanStyle(color = c)) { append("●") }; append(" $label  ") }
            dot(ME_COLOR, "나 You"); dot(accent, "목적지 Destination")
            withStyle(SpanStyle(color = accent.copy(alpha = 0.6f))) { append("○") }; append(" 대피소 Shelters  ")
            if (alt != null) dot(ALT_COLOR, "잠김 시 대안 If locked")
        }
        Text(legend, fontSize = 11.sp, color = Color.DarkGray,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Message(ko: String, en: String) {
    Bi(ko, en, 18.sp, modifier = Modifier.padding(24.dp))
}
