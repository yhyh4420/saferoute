package com.saferoute.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.saferoute.app.agent.Profile
import com.saferoute.app.alert.LearnUi
import com.saferoute.app.alert.LearnedPlaceUi
import com.saferoute.app.alert.PrepareUi
import com.saferoute.app.learn.LearnWorker
import com.saferoute.app.learn.PlaceLearner
import com.saferoute.app.learn.Visit
import com.saferoute.app.alert.QaUi
import com.saferoute.app.qa.OfflineQa
import com.saferoute.app.voice.VoiceQuestion
import com.saferoute.app.alert.PreparedPlanUi
import com.saferoute.app.alert.AlertResult
import com.saferoute.app.alert.AlertScreen
import com.saferoute.app.alert.MapMark
import com.saferoute.app.location.DEMO_PLACES
import com.saferoute.app.location.Fix
import com.saferoute.app.location.headingUpdates
import java.time.LocalDate
import java.time.LocalDateTime
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val g by lazy { SafeRouteGraph.get(this) }
    private val db get() = g.db
    private val engine get() = g.engine
    private val location get() = g.location
    private val plans get() = g.plans
    private val qa get() = g.qa
    private val agent get() = g.agent
    private val learner get() = g.learner
    private val voice by lazy { VoiceQuestion(g.apiKey::get) }

    /** Intents that arrive while the screen is already open (e.g. the flood shortcut over the quake screen). */
    private val newIntent = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Demo/emulator hooks: adb shell am start -n com.saferoute.app/.MainActivity --es hazard flood --ei place 1 --ei hour 23
        val startHazard = intent.getStringExtra("hazard") ?: "earthquake"
        val startPlace = intent.getIntExtra("place", -1).takeIf { it in DEMO_PLACES.indices }?.let { DEMO_PLACES[it] }
        val demoHour = intent.getIntExtra("hour", -1).takeIf { it in 0..23 }
        fun now() = demoHour?.let { LocalDate.now().atTime(it, 0) } ?: LocalDateTime.now()
        LearnWorker.schedule(this)
        // Seal a freshly provisioned API key right away so the plaintext copy lives only seconds.
        Thread { g.apiKey.get() }.start()
        // The alert screen must stay readable while walking with the phone in hand.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        setContent {
            MaterialTheme {
                var hazard by remember { mutableStateOf(startHazard) }
                // Personal settings persist so nobody has to find a switch during an alert.
                var stepFree by remember {
                    mutableStateOf(intent.getBooleanExtra("stepfree", prefs.getBoolean("stepfree", false)))
                }
                var fix by remember { mutableStateOf(startPlace) }
                var pinned by remember { mutableStateOf(startPlace != null) }
                LaunchedEffect(newIntent.value) {
                    val i = newIntent.value ?: return@LaunchedEffect
                    i.getStringExtra("hazard")?.let { hazard = it }
                    i.getIntExtra("place", -1).takeIf { it in DEMO_PLACES.indices }?.let { fix = DEMO_PLACES[it]; pinned = true }
                }
                var result by remember { mutableStateOf<AlertResult?>(null) }
                var heading by remember { mutableStateOf<Float?>(null) }
                val rules = remember { db.rules() }
                var withChild by remember { mutableStateOf(prefs.getBoolean("withchild", false)) }
                var fieldLogOn by remember { mutableStateOf(g.fieldLog.enabled) }
                var fieldLogLines by remember { mutableStateOf(g.fieldLog.lines()) }
                var prepareUi by remember { mutableStateOf(PrepareUi()) }
                var qaUi by remember(hazard) {
                    mutableStateOf(QaUi(quick = qa.questions(hazard, "ko").map { it.question to (qa.counterpart(it)?.question ?: "") }))
                }
                var planVersion by remember { mutableStateOf(0) }
                var learnUi by remember { mutableStateOf(LearnUi()) }
                fun refreshLearn(status: String? = learnUi.status, running: Boolean = false) {
                    val all = learner.places()
                    learnUi = LearnUi(
                        visits = learner.visits().size,
                        total = all.size,
                        // Places passed through can number hundreds: show the first few only.
                        places = all.take(6).map { p ->
                            LearnedPlaceUi(p.label, p.days, rules.map { r -> r.hazard to (plans.lookup(p.lat, p.lon, r.hazard, stepFree) != null) })
                        },
                        running = running,
                        status = status,
                        candidateTiles = g.candidates.tilesDone(),
                        candidates = g.candidates.all().size,
                        route = com.saferoute.app.learn.RouteReview.load(this@MainActivity),
                    )
                }
                LaunchedEffect(planVersion, stepFree) {
                    // Today's route review is offline: refresh it whenever the screen opens.
                    withContext(Dispatchers.Default) { com.saferoute.app.learn.RouteReview.compute(this@MainActivity, g) }
                    refreshLearn()
                }
                fun askOffline(q: String) {
                    val guidance = (result as? AlertResult.Ok)?.guidance
                    val shelter = guidance?.shelter
                    val hit = qa.answer(q, hazard)
                    val korean = OfflineQa.isKorean(q)
                    val rule = rules.first { it.hazard == hazard }
                    // Both languages: the asked one first, then the same pre-checked answer in the other.
                    fun answerIn(ko: Boolean): String {
                        val e = hit?.let { if ((it.lang == "ko") == ko) it else qa.counterpart(it) }
                        val name = if (ko) shelter?.name else shelter?.nameEn?.ifBlank { shelter.name }
                        val base = e?.let { OfflineQa.fill(it.answer, name, ko) }
                            ?: if (ko) "${rule.verbKo}. ${rule.forbidKo}." else "${rule.verbEn}. ${rule.forbidEn}."
                        // An answer that sends people to a school yard at night must carry the locked-gate fallback.
                        val alt = guidance?.lockWarning?.alternative?.shelter ?: return base
                        if (name == null || !base.contains(name)) return base
                        return base + if (ko) " 밤·주말엔 문이 잠겨 있을 수 있어요. 잠겨 있으면 ${alt.name}${OfflineQa.roParticle(alt.name)} 가세요."
                        else " The gate may be locked at night or at weekends; if so, go to ${alt.nameEn.ifBlank { alt.name }}."
                    }
                    qaUi = qaUi.copy(asked = q, askedOther = hit?.let { qa.counterpart(it)?.question },
                        answer = answerIn(korean), answerOther = answerIn(!korean))
                    g.fieldLog.write("qa", mapOf("hazard" to hazard, "q" to q, "intent" to hit?.intent, "matched" to (hit != null)))
                }
                val scope = rememberCoroutineScope()
                val prepared = remember(fix, hazard, stepFree, planVersion) {
                    fix?.let { f -> plans.lookup(f.lat, f.lon, hazard, stepFree) }?.let { p ->
                        // Plans made from a raw GPS fix carry the fix label; show where it applies instead.
                        PreparedPlanUi(if (p.placeLabel in setOf("GPS", "마지막 위치", com.saferoute.app.location.LAST_KNOWN)) "이 근처" else p.placeLabel, p.notesKo,
                            p.backupId?.let { db.byId(it)?.bilingual }, p.meetingPointId?.let { db.byId(it)?.bilingual }, p.notesEn,
                            listOfNotNull(
                                p.backupId?.let { db.byId(it) }?.let { MapMark(it.lat, it.lon, "backup") },
                                p.meetingPointId?.let { db.byId(it) }?.let { MapMark(it.lat, it.lon, "meeting") },
                            ))
                    }
                }

                fun hasLocation() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                var locationGranted by remember { mutableStateOf(hasLocation()) }
                // Re-subscribe once the user answers: a subscription started before the grant never delivers.
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    locationGranted = hasLocation()
                }
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) qaUi = qaUi.copy(voiceStatus = "마이크 권한이 없어요. 버튼이나 입력으로 물어보세요. · No microphone permission. Use the buttons or type.")
                }
                LaunchedEffect(Unit) {
                    if (!locationGranted) {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                // Poll while there is no fix: the user may switch location on in settings and come back.
                var locationOff by remember { mutableStateOf(false) }
                LaunchedEffect(fix == null) {
                    while (fix == null) {
                        locationOff = !locationGranted || !location.enabled()
                        if (!locationOff && !locationGranted) locationGranted = hasLocation()
                        kotlinx.coroutines.delay(2_000)
                        locationGranted = hasLocation()
                    }
                    locationOff = false
                }
                // Only while the screen is visible: GPS, compass and the minute re-check stop in the background
                // (the field log showed background recomputes every minute, which only costs battery).
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(pinned, locationGranted, locationOff) {
                    if (pinned || !locationGranted || locationOff) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        location.lastKnown()?.let { fix = it }
                        location.updates().collect {
                            if (!pinned) fix = it
                            learner.record(Visit(it.lat, it.lon, System.currentTimeMillis()))
                            refreshLearn()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { headingUpdates(this@MainActivity).collect { heading = it } }
                }
                // Re-evaluate every minute too: school gates close at 18:00 even if nobody moves.
                var minute by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        minute++ // re-check on return to the screen, then every minute
                        while (true) { kotlinx.coroutines.delay(60_000); minute++ }
                    }
                }
                LaunchedEffect(fix, hazard, stepFree, minute, prepared) {
                    val f = fix ?: return@LaunchedEffect
                    result = withContext(Dispatchers.Default) { engine.guide(f, hazard, stepFree, now(), prepared?.marks.orEmpty()) }
                    val ok = (result as? AlertResult.Ok)?.guidance
                    g.fieldLog.write("guide", mapOf(
                        "hazard" to hazard, "stepfree" to stepFree, "source" to f.label, "lat" to f.lat, "lon" to f.lon,
                        "acc_m" to f.accuracyM, "fix_age_s" to f.timeMs?.let { (System.currentTimeMillis() - it) / 1000 },
                        "result" to (result?.let { it::class.simpleName }), "dist_m" to ok?.distanceM, "walk_min" to ok?.walkMinutes,
                        "shelter" to ok?.shelter?.id, "snap_m" to ok?.snapM?.toInt(), "compute_ms" to ok?.computeMs,
                        "heading" to (heading != null), "outside_city" to ok?.outsideCity, "lock" to (ok?.lockWarning != null),
                        "online" to online(),
                    ))
                    fieldLogLines = g.fieldLog.lines()
                }

                AlertScreen(
                    rules = rules,
                    hazard = hazard,
                    onHazard = { hazard = it },
                    stepFree = stepFree,
                    onStepFree = { stepFree = it; prefs.edit().putBoolean("stepfree", it).apply() },
                    fix = fix,
                    places = DEMO_PLACES,
                    onPlace = { fix = it; pinned = true },
                    result = result,
                    headingDeg = heading,
                    prepared = prepared,
                    prepare = prepareUi,
                    withChild = withChild,
                    qa = qaUi,
                    onAsk = { askOffline(it) },
                    onWithChild = { withChild = it; prefs.edit().putBoolean("withchild", it).apply() },
                    onCall119 = { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))) },
                    locationOff = locationOff,
                    fieldLog = fieldLogOn,
                    fieldLogLines = fieldLogLines,
                    onFieldLog = { g.fieldLog.enabled = it; fieldLogOn = it },
                    onTurnOnLocation = {
                        if (!locationGranted) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    knownPlaces = remember(learnUi) { learner.places().filter { it.regular }.take(3).map { Fix(it.lat, it.lon, it.label) } },
                    onMic = {
                        when {
                            voice.isRecording -> voice.stop()
                            !online() -> qaUi = qaUi.copy(voiceStatus = "음성 질문은 온라인에서만 돼요. 위의 질문 버튼을 눌러 주세요. · Voice questions need a network. Use the buttons above.")
                            g.apiKey.get().isBlank() -> qaUi = qaUi.copy(voiceStatus = "이 기기에 API 키가 없어 음성 질문을 쓸 수 없어요. 위의 질문 버튼을 눌러 주세요. · No API key on this phone for voice. Use the buttons above.")
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            else -> scope.launch {
                                qaUi = qaUi.copy(listening = true, voiceStatus = "듣는 중… 다시 누르면 멈춰요 (최대 10초) · Listening… tap again to stop (max 10 s)")
                                val heard = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val wav = voice.record()
                                        qaUi = qaUi.copy(listening = false, voiceStatus = "받아쓰는 중… · Transcribing… (Muse Voice Transcribe)")
                                        voice.transcribe(wav, qa.questions(hazard, "ko").flatMap { it.keywords })
                                    }
                                }
                                heard.onSuccess { t ->
                                    qaUi = qaUi.copy(listening = false, voiceStatus = "들은 말 Heard: \"$t\"")
                                    if (t.isNotBlank()) askOffline(t)
                                }.onFailure { qaUi = qaUi.copy(listening = false, voiceStatus = "음성 인식 실패 Voice failed: ${it.message?.take(80)}") }
                            }
                        }
                    },
                    learn = learnUi,
                    onSimulateWeek = {
                        // Emulator/demo: home = 문래동, work = 여의도 (no real location history here).
                        learner.clear()
                        PlaceLearner.simulatedWeek(DEMO_PLACES[0].lat to DEMO_PLACES[0].lon, DEMO_PLACES[2].lat to DEMO_PLACES[2].lon,
                            System.currentTimeMillis()).forEach(learner::record)
                        refreshLearn("일주일 이동 기록을 넣었어요 (데모). · Added a demo week of movement.")
                    },
                    onLearnNow = {
                        if (!online()) refreshLearn("오프라인이에요. 계획은 온라인일 때 자동으로 만들어져요. · Offline. Plans are made automatically when online.")
                        else scope.launch {
                            refreshLearn("자주 가는 곳마다 재난별 계획을 만드는 중… · Making plans for each place and hazard…", running = true)
                            val done = withContext(Dispatchers.IO) {
                                LearnWorker.exclusive {
                                    val made = LearnWorker.prepareMissingPlans(g, Profile(if (withChild) "a 5-year-old child" else "alone", stepFree))
                                    scope.launch { refreshLearn("주변 안전 장소 후보 찾는 중… · Searching guidance-matching places nearby…", running = true) }
                                    made to LearnWorker.findCandidates(g)
                                }
                            }
                            planVersion++
                            refreshLearn(done?.let { (made, tiles) -> "새 계획 ${made}개 · 지도 칸 ${tiles}개 검토 · $made new plans · $tiles map tiles checked" }
                                ?: "이미 백그라운드에서 갱신 중이에요. 잠시 후 다시 보세요. · An update is already running in the background.")
                        }
                    },
                    onPrepare = {
                        val f = fix
                        when {
                            f == null -> prepareUi = PrepareUi(status = "위치가 필요해요. · Location needed.")
                            !online() -> prepareUi = PrepareUi(status = "오프라인이에요. 계획은 온라인일 때 미리 만들어 두세요. · Offline. Make plans while online.")
                            g.apiKey.get().isBlank() -> prepareUi = PrepareUi(status = "이 기기에 API 키가 없어요 (scripts/provision_key.sh). 오프라인 안내는 키 없이 동작해요. · No API key on this phone; offline guidance works without it.")
                            else -> scope.launch {
                                prepareUi = PrepareUi(running = true)
                                val profile = Profile(if (withChild) "a 5-year-old child" else "alone", stepFree)
                                val run = runCatching {
                                    withContext(Dispatchers.IO) {
                                        agent.run(f, hazard, profile) { line ->
                                            scope.launch { prepareUi = prepareUi.copy(log = prepareUi.log + line) }
                                        }
                                    }
                                }
                                prepareUi = run.fold(
                                    onSuccess = { r ->
                                        if (r.plan != null && r.problems.isEmpty()) {
                                            plans.save(r.plan); planVersion++
                                            prepareUi.copy(running = false, status = "검증 통과 · 저장됨 (도구 ${r.toolLog.size}회) · Validated and saved (${r.toolLog.size} tool calls)")
                                        } else prepareUi.copy(running = false, status = "검증 실패 → 저장 안 함 · Validation failed, not saved: ${r.problems.joinToString()}")
                                    },
                                    onFailure = { prepareUi.copy(running = false, status = "오류 Error: ${it.message?.take(120)}") },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
