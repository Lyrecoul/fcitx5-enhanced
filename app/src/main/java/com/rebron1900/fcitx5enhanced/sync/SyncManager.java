package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步调度器 — 管理 WorkManager 周期任务和即时同步。
 */
public class SyncManager {

    private static final String TAG = "Fcitx5Sync";
    private static final String WORK_NAME = "rime_webdav_sync";

    /** 全局互斥锁：防止 WorkManager + MainHook 同时同步。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sSyncRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 根据配置注册/取消定时同步任务 */
    public static void scheduleSync(Context context) {
        WorkManager wm = WorkManager.getInstance(context);

        if (!ConfigStorage.isWebDavEnabled(context)) {
            wm.cancelUniqueWork(WORK_NAME);
            Log.i(TAG, "sync disabled, cancelled work");
            return;
        }

        int interval = ConfigStorage.getSyncInterval(context);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build();

        wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);

        Log.i(TAG, "sync scheduled: every " + interval + " min");
    }

    /** 立即执行一次同步（不等待定时任务） */
    public static void syncNow(Context context, SyncCallback callback) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .addTag("rime_sync_now")
                .build();

        WorkManager.getInstance(context)
                .enqueue(request);

        // 监听结果
        WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(request.getId())
                .observeForever(info -> {
                    if (info != null && info.getState().isFinished()) {
                        String result = info.getState() == WorkInfo.State.SUCCEEDED
                                ? "同步完成" : "同步失败";
                        if (callback != null) callback.onResult(result);
                    }
                });

        Log.i(TAG, "sync now triggered");
    }

    /**
     * 尝试执行一次同步（互斥锁版）。
     * 如果已有同步在跑，直接跳过不排队。
     */
    public static boolean runSyncOnce(Context context) {
        if (!sSyncRunning.compareAndSet(false, true)) {
            Log.d(TAG, "sync already in progress, skip");
            return false;
        }
        try {
            Context appCtx = context.getApplicationContext();
            LocalFileAccess localAccess = LocalFileAccessFactory.create(appCtx);
            WebDavSyncHelper helper = new WebDavSyncHelper(appCtx, localAccess);
            WebDavSyncHelper.SyncResult result = helper.sync();
            ConfigStorage.saveLastSyncResult(appCtx, result.toToastString(), System.currentTimeMillis());
            Log.i(TAG, "sync done: " + result.toToastString());
            return true;
        } catch (IllegalArgumentException e) {
            ConfigStorage.saveLastSyncResult(context, "配置错误: " + e.getMessage(), System.currentTimeMillis());
            Log.e(TAG, "sync config error", e);
            return false;
        } catch (Exception e) {
            ConfigStorage.saveLastSyncResult(context, "同步失败: " + e.getMessage(), System.currentTimeMillis());
            Log.w(TAG, "sync run failed", e);
            return false;
        } finally {
            sSyncRunning.set(false);
        }
    }

    /** 初始化：应用启动时注册定时任务 */
    public static void init(Context context) {
        if (ConfigStorage.isWebDavEnabled(context)) {
            scheduleSync(context);
        }
    }

    public interface SyncCallback {
        void onResult(String result);
    }
}
