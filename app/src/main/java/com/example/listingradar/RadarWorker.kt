package com.example.listingradar

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RadarWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Radar.runScan(applicationContext).alerts.forEach { Radar.notify(applicationContext, it) }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
