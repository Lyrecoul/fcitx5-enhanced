package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

/**
 * WorkManager Worker — 定时执行 WebDAV 同步。
 *
 * 通过 SyncManager.runSyncOnce() 执行，保证多实例不并发。
 */
public class SyncWorker extends Worker {

    private static final String TAG = "Fcitx5Sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "SyncWorker started");

        if (!ConfigStorage.isWebDavEnabled(getApplicationContext())) {
            Log.i(TAG, "sync disabled, skip");
            return Result.success();
        }

        SyncManager.runSyncOnce(getApplicationContext());
        String result = ConfigStorage.getLastSyncResult(getApplicationContext());
        Log.i(TAG, "SyncWorker done: " + result);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            android.widget.Toast.makeText(getApplicationContext(), result, android.widget.Toast.LENGTH_SHORT).show();
        });

        return Result.success();
    }
}
