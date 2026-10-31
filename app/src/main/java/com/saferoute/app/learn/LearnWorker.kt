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
        // No provisioned key: learn places anyway, but do not call the API (it would only fail).
        val made = if (g.apiKey.get().isBlank()) 0 else prepareMissingPlans(g, Profile.fromSettings(applicationContext))
        Log.i("SafeRoute", "learn_worker places=${g.learner.places().size} new_plans=$made")
        return Result.success()
    }

    companion object {
        private const val NAME = "saferoute-learn"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LearnWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Plans every (regular place × hazard) that has none yet. Returns how many were saved. */
        fun prepareMissingPlans(g: SafeRouteGraph, profile: Profile, onTool: (String) -> Unit = {}): Int {
            var saved = 0
            for (place in g.learner.places()) {
                for (hazard in g.db.rules().map { it.hazard }) {
                    if (g.plans.lookup(place.lat, place.lon, hazard, profile.stepFree) != null) continue
                    val run = runCatching { g.agent.run(Fix(place.lat, place.lon, place.label), hazard, profile, onTool) }.getOrNull()
                    if (run?.plan != null && run.problems.isEmpty()) { g.plans.save(run.plan); saved++ }
                }
            }
            return saved
        }
    }
}
