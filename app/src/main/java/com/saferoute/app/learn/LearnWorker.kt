package com.saferoute.app.learn

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saferoute.app.SafeRouteGraph
import com.saferoute.app.agent.Profile
import com.saferoute.app.location.Fix
import java.util.concurrent.TimeUnit

/**
 * Background preparation: sample the location, and for every regular place without a plan,
 * run the planning agent while the network is up. Never runs on the alert path.
 */
class LearnWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = SafeRouteGraph.get(applicationContext)
        g.location.lastKnown()?.let { g.learner.record(Visit(it.lat, it.lon, System.currentTimeMillis())) }
        val ran = exclusive {
            // No provisioned key: learn places anyway, but do not call the API (it would only fail).
            val made = if (g.apiKey.get().isBlank()) 0 else prepareMissingPlans(g, Profile.fromSettings(applicationContext))
            val tiles = findCandidates(g)
            Log.i("SafeRoute", "learn_worker places=${g.learner.places().size} new_plans=$made candidate_tiles=$tiles")
        }
        if (ran == null) Log.i("SafeRoute", "learn_worker skipped: an update is already running")
        RouteReview.compute(applicationContext, g)
        return Result.success()
    }

    companion object {
        private const val NAME = "saferoute-learn"
        private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Runs [block] unless another plan/candidate update is in progress (the 15-min worker and the
         * "update now" button share it): returns null when skipped. Avoids duplicate API calls and
         * double load on the public map servers.
         */
        fun <T> exclusive(block: () -> T): T? {
            if (!busy.compareAndSet(false, true)) return null
            try { return block() } finally { busy.set(false) }
        }

        fun schedule(context: Context) {
            // 15 min is WorkManager's minimum: with the app closed this is how often a place is sampled.
            val request = PeriodicWorkRequestBuilder<LearnWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Map data only around the user's own places inside the pack's city; needs the network. */
        fun findCandidates(g: SafeRouteGraph): Int = runCatching {
            g.candidates.update(g.learner.places().filter { g.db.inArea(it.lat, it.lon) }.map { it.lat to it.lon })
        }.getOrDefault(0)

        /** Cost guard: places passed through can be many; at most this many new places per run (×2 hazards). */
        private const val MAX_NEW_PLACES_PER_RUN = 5

        /** Plans (place × hazard) without a plan yet, regular places first, then the most recently passed. */
        fun prepareMissingPlans(g: SafeRouteGraph, profile: Profile, onTool: (String) -> Unit = {}): Int {
            var saved = 0
            val hazards = g.db.rules().map { it.hazard }
            val todo = g.learner.places()
                // Outside the pack's city a plan would point at far-away shelters: skip (and save the API call).
                .filter { p -> g.db.inArea(p.lat, p.lon) }
                // Missing, or made before plans carried English notes: (re)make it.
                .filter { p -> hazards.any { g.plans.lookup(p.lat, p.lon, it, profile.stepFree)?.notesEn == null } }
                .take(MAX_NEW_PLACES_PER_RUN)
            for (place in todo) {
                for (hazard in hazards) {
                    if (g.plans.lookup(place.lat, place.lon, hazard, profile.stepFree)?.notesEn != null) continue
                    val run = runCatching { g.agent.run(Fix(place.lat, place.lon, place.label), hazard, profile, onTool) }.getOrNull()
                    if (run?.plan != null && run.problems.isEmpty()) { g.plans.save(run.plan); saved++ }
                }
            }
            return saved
        }
    }
}
