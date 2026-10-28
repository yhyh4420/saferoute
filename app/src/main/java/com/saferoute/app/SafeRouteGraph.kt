package com.saferoute.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.saferoute.app.agent.MetaApiClient
import com.saferoute.app.agent.PlanAgent
import com.saferoute.app.agent.PlanStore
import com.saferoute.app.alert.AlertEngine
import com.saferoute.app.data.ShelterDb
import com.saferoute.app.learn.PlaceLearner
import com.saferoute.app.location.LocationSource
import com.saferoute.app.pack.RoutePack
import com.saferoute.app.qa.OfflineQa

/** Process-wide objects shared by the activity and the background worker. */
class SafeRouteGraph private constructor(ctx: Context) {
    val pack: RoutePack by lazy {
        val t0 = SystemClock.elapsedRealtime()
        RoutePack.open(ctx.assets, "pack_seoul.bin").also {
            Log.i("SafeRoute", "pack_load_ms=${SystemClock.elapsedRealtime() - t0} nodes=${it.nodeCount}")
        }
    }
    val db by lazy { ShelterDb(ctx, "pack_seoul.sqlite") }
    val engine by lazy { AlertEngine(pack, db) }
    val location by lazy { LocationSource(ctx) }
    val plans by lazy { PlanStore(ctx) }
    val learner by lazy { PlaceLearner(ctx) }
    val qa by lazy { OfflineQa(db.db) }
    val agent by lazy { PlanAgent(MetaApiClient(BuildConfig.META_API_KEY), engine, db) }

    companion object {
        @Volatile private var instance: SafeRouteGraph? = null

        fun get(ctx: Context): SafeRouteGraph =
            instance ?: synchronized(this) { instance ?: SafeRouteGraph(ctx.applicationContext).also { instance = it } }
    }
}
