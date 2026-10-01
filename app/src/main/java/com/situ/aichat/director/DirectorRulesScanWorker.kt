package com.situ.aichat.director

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * [zCODE] B9 规则档周期扫描 Worker（中继2）。周期=[DirectorRulesConfig.SCAN_INTERVAL_MS]（6h·终审五数），
 * 排程点=AppViewModel 回前台补排块（KEEP 幂等；WorkManager 周期任务自持跨重启）。
 * 纯本地判定不需网络（requireNetwork=false）；择机新闻报道为 DB 落库+延迟投递，同无需实时网络。
 * 范式对齐 [com.situ.aichat.work.NotificationRescheduleWorker]（@HiltWorker+@AssistedInject）。
 */
@HiltWorker
class DirectorRulesScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scanService: DirectorRulesScanService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val fired = scanService.scanOnce()
        Log.i(TAG, "B9 规则扫描完成：触发 $fired 起远方相遇")
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "B9 规则扫描异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "DirectorRulesScanWorker"
        const val UNIQUE_PERIODIC = "director_rules_scan_periodic"
    }
}
