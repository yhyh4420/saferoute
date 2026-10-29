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
import com.saferoute.app.location.DEMO_PLACES
import com.saferoute.app.location.Fix
import com.saferoute.app.location.headingUpdates
import java.time.LocalDate
import java.time.LocalDateTime
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
    private val voice by lazy { VoiceQuestion(BuildConfig.META_API_KEY) }

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
                var prepareUi by remember { mutableStateOf(PrepareUi()) }
                var qaUi by remember(hazard) {
                    mutableStateOf(QaUi(quick = qa.questions(hazard, "ko").map { it.question }))
                }
                var planVersion by remember { mutableStateOf(0) }
                var learnUi by remember { mutableStateOf(LearnUi()) }
                fun refreshLearn(status: String? = learnUi.status, running: Boolean = false) {
                    learnUi = LearnUi(
                        visits = learner.visits().size,
                        places = learner.places().map { p ->
                            LearnedPlaceUi(p.label, p.days, rules.map { r -> r.hazard to (plans.lookup(p.lat, p.lon, r.hazard) != null) })
                        },
                        running = running,
                        status = status,
                    )
                }
                LaunchedEffect(planVersion) { refreshLearn() }
                fun askOffline(q: String) {
                    val shelterName = (result as? AlertResult.Ok)?.guidance?.shelter?.name
                    val hit = qa.answer(q, hazard)
                    val korean = OfflineQa.isKorean(q)
                    qaUi = qaUi.copy(asked = q, answer = hit?.let { OfflineQa.fill(it.answer, shelterName, korean) }
                        ?: rules.first { it.hazard == hazard }.let {
                            if (korean) "${it.verbKo}. ${it.forbidKo}." else "${it.verbEn}. ${it.forbidEn}."
                        })
                }
                val scope = rememberCoroutineScope()
                val prepared = remember(fix, hazard, planVersion) {
                    fix?.let { f -> plans.lookup(f.lat, f.lon, hazard) }?.let { p ->
                        // Plans made from a raw GPS fix carry the fix label; show where it applies instead.
                        PreparedPlanUi(if (p.placeLabel in setOf("GPS", "마지막 위치")) "이 근처" else p.placeLabel, p.notesKo, p.backupId?.let { db.byId(it)?.name },
                            p.meetingPointId?.let { db.byId(it)?.name })
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
                    if (!granted) qaUi = qaUi.copy(voiceStatus = "마이크 권한이 없어요. 버튼이나 입력으로 물어보세요.")
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
                LaunchedEffect(pinned, locationGranted, locationOff) {
                    if (pinned || !locationGranted || locationOff) return@LaunchedEffect
                    location.lastKnown()?.let { fix = it }
                    location.updates().collect {
                        if (!pinned) fix = it
                        learner.record(Visit(it.lat, it.lon, System.currentTimeMillis()))
                        refreshLearn()
                    }
                }
                LaunchedEffect(Unit) { headingUpdates(this@MainActivity).collect { heading = it } }
                LaunchedEffect(fix, hazard, stepFree) {
                    val f = fix ?: return@LaunchedEffect
                    result = withContext(Dispatchers.Default) { engine.guide(f, hazard, stepFree, now()) }
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
                    onTurnOnLocation = {
                        if (!locationGranted) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    knownPlaces = remember(learnUi) { learner.places().map { Fix(it.lat, it.lon, it.label) } },
                    onMic = {
                        when {
                            voice.isRecording -> voice.stop()
                            !online() -> qaUi = qaUi.copy(voiceStatus = "음성 질문은 온라인에서만 돼요. 위의 질문 버튼을 눌러 주세요.")
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            else -> scope.launch {
                                qaUi = qaUi.copy(listening = true, voiceStatus = "듣는 중… 다시 누르면 멈춰요 (최대 10초)")
                                val heard = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val wav = voice.record()
                                        qaUi = qaUi.copy(listening = false, voiceStatus = "받아쓰는 중… (Muse Voice Transcribe)")
                                        voice.transcribe(wav, qa.questions(hazard, "ko").flatMap { it.keywords })
                                    }
                                }
                                heard.onSuccess { t ->
                                    qaUi = qaUi.copy(listening = false, voiceStatus = "들은 말: \"$t\"")
                                    if (t.isNotBlank()) askOffline(t)
                                }.onFailure { qaUi = qaUi.copy(listening = false, voiceStatus = "음성 인식 실패: ${it.message?.take(80)}") }
                            }
                        }
                    },
                    learn = learnUi,
                    onSimulateWeek = {
                        // Emulator/demo: home = 문래동, work = 여의도 (no real location history here).
                        learner.clear()
                        PlaceLearner.simulatedWeek(DEMO_PLACES[0].lat to DEMO_PLACES[0].lon, DEMO_PLACES[2].lat to DEMO_PLACES[2].lon,
                            System.currentTimeMillis()).forEach(learner::record)
                        refreshLearn("일주일 이동 기록을 넣었어요 (데모).")
                    },
                    onLearnNow = {
                        if (!online()) refreshLearn("오프라인이에요. 계획은 온라인일 때 자동으로 만들어져요.")
                        else scope.launch {
                            refreshLearn("자주 가는 곳마다 재난별 계획을 만드는 중…", running = true)
                            val made = withContext(Dispatchers.IO) {
                                LearnWorker.prepareMissingPlans(g, Profile(if (withChild) "a 5-year-old child" else "alone", stepFree))
                            }
                            planVersion++
                            refreshLearn("새 계획 ${made}개 저장 (검증 통과분만)")
                        }
                    },
                    onPrepare = {
                        val f = fix
                        when {
                            f == null -> prepareUi = PrepareUi(status = "위치가 필요해요.")
                            !online() -> prepareUi = PrepareUi(status = "오프라인이에요. 계획은 온라인일 때 미리 만들어 두세요.")
                            BuildConfig.META_API_KEY.isBlank() -> prepareUi = PrepareUi(status = "META_API_KEY가 빌드에 없어요.")
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
                                            prepareUi.copy(running = false, status = "검증 통과 · 저장됨 (도구 ${r.toolLog.size}회)")
                                        } else prepareUi.copy(running = false, status = "검증 실패 → 저장 안 함: ${r.problems.joinToString()}")
                                    },
                                    onFailure = { prepareUi.copy(running = false, status = "오류: ${it.message?.take(120)}") },
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
