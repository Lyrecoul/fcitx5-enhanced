package com.rebron1900.fcitx5enhanced;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** 用户解锁后将 Direct Boot 旧配置迁回 LSPosed 远程偏好使用的凭据保护存储。 */
public class ConfigMigrationReceiver extends BroadcastReceiver {
    private static final String TAG = "Fcitx5Enh";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (android.os.Build.VERSION.SDK_INT < 24) return;
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        SharedPreferences target = context.getSharedPreferences(
                ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        if (isInitialized(target)) return;
        SharedPreferences source = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        if (!isInitialized(source)) return;

        boolean migrated = target.edit()
                .putLong(ConfigContract.REVISION,
                        source.getLong(ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION))
                .putInt(ConfigContract.BLUR_RADIUS,
                        source.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR))
                .putInt(ConfigContract.BG_ALPHA,
                        source.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA))
                .putInt(ConfigContract.KEY_ALPHA,
                        source.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA))
                .putInt(ConfigContract.CORNER_RADIUS,
                        source.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER))
                .putBoolean(ConfigContract.VOICE_ENABLED,
                        source.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE))
                .putBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                        source.getBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                                ConfigContract.DEFAULT_LEFT_BUTTON))
                .putBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                        source.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                                ConfigContract.DEFAULT_RIGHT_BUTTON))
                .putBoolean(ConfigContract.KEY_BORDER,
                        source.getBoolean(ConfigContract.KEY_BORDER,
                                ConfigContract.DEFAULT_KEY_BORDER))
                .commit();
        if (migrated) Log.i(TAG, "migrated device protected config");
    }

    private static boolean isInitialized(SharedPreferences preferences) {
        return preferences.contains(ConfigContract.REVISION)
                || preferences.contains(ConfigContract.BLUR_RADIUS);
    }
}
