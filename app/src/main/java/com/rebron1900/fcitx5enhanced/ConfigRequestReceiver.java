package com.rebron1900.fcitx5enhanced;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** 通过显式有序广播向 Hook 进程返回只读配置，绕过 Android 包可见性过滤。 */
public class ConfigRequestReceiver extends BroadcastReceiver {
    public static final String ACTION_REQUEST =
            "com.rebron1900.fcitx5enhanced.action.REQUEST_CONFIG";
    public static final String EXTRA_CONFIG = "config";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REQUEST.equals(intent.getAction())) return;
        SharedPreferences preferences = context.getSharedPreferences(
                ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        if (!preferences.contains(ConfigContract.REVISION)
                && !preferences.contains(ConfigContract.BLUR_RADIUS)) return;

        Bundle config = new Bundle();
        config.putLong(ConfigContract.REVISION, preferences.getLong(
                ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION));
        config.putInt(ConfigContract.BLUR_RADIUS, preferences.getInt(
                ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR));
        config.putInt(ConfigContract.BG_ALPHA, preferences.getInt(
                ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA));
        config.putInt(ConfigContract.KEY_ALPHA, preferences.getInt(
                ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA));
        config.putInt(ConfigContract.CORNER_RADIUS, preferences.getInt(
                ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER));
        config.putBoolean(ConfigContract.VOICE_ENABLED, preferences.getBoolean(
                ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE));
        config.putBoolean(ConfigContract.SHOW_LEFT_BUTTON, preferences.getBoolean(
                ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON));
        config.putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, preferences.getBoolean(
                ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON));
        config.putBoolean(ConfigContract.KEY_BORDER, preferences.getBoolean(
                ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER));

        Bundle result = new Bundle();
        result.putBundle(EXTRA_CONFIG, config);
        setResultExtras(result);
        setResultCode(android.app.Activity.RESULT_OK);
    }
}
