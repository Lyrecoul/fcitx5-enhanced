package com.rebron1900.fcitx5enhanced;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private static final String TAG = "Fcitx5Enh";

    // Theme tab
    private SeekBar sbBlur, sbAlpha, sbKeyAlpha, sbCorner;
    private TextView tvBlur, tvAlpha, tvKeyAlpha, tvCorner;
    private Switch swVoice, swLeft, swRight, swKeyBorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle("Fcitx5 增强");

        sbBlur = findViewById(R.id.sb_blur_radius);
        sbAlpha = findViewById(R.id.sb_bg_alpha);
        sbKeyAlpha = findViewById(R.id.sb_key_alpha);
        sbCorner = findViewById(R.id.sb_corner_radius);
        tvBlur = findViewById(R.id.tv_blur_val);
        tvAlpha = findViewById(R.id.tv_alpha_val);
        tvKeyAlpha = findViewById(R.id.tv_key_alpha_val);
        tvCorner = findViewById(R.id.tv_corner_val);
        swVoice = findViewById(R.id.sw_voice);
        swLeft = findViewById(R.id.sw_left_btn);
        swRight = findViewById(R.id.sw_right_btn);
        swKeyBorder = findViewById(R.id.sw_key_border);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (sb == sbBlur) tvBlur.setText(progress == 0 ? "关" : progress + "");
                else if (sb == sbAlpha) tvAlpha.setText((progress * 100) / 255 + "%");
                else if (sb == sbKeyAlpha) tvKeyAlpha.setText((progress * 100) / 255 + "%");
                else if (sb == sbCorner) tvCorner.setText(progress == 0 ? "关" : progress + "");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveAndApply(); }
        };

        sbBlur.setOnSeekBarChangeListener(listener);
        sbAlpha.setOnSeekBarChangeListener(listener);
        sbKeyAlpha.setOnSeekBarChangeListener(listener);
        sbCorner.setOnSeekBarChangeListener(listener);

        View.OnClickListener switchListener = v -> saveAndApply();
        swVoice.setOnClickListener(switchListener);
        swLeft.setOnClickListener(switchListener);
        swRight.setOnClickListener(switchListener);
        swKeyBorder.setOnClickListener(switchListener);

        // 配置必须始终可编辑：模块可先于目标输入法安装，且包可见性/分支包名
        // 不应影响用户保存偏好。运行时仅在支持语音的目标输入法中显示语音控件。
        loadSettings();
    }

    // ══════════════════════════════════════════
    //  持久化：模块 SharedPreferences + ConfigProvider
    // ══════════════════════════════════════════

    private SharedPreferences getConfigPreferences() {
        return getSharedPreferences(ConfigContract.PREFS_NAME, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences sp = getConfigPreferences();
        migrateLegacySettings(sp);

        sbBlur.setProgress(sp.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR));
        sbAlpha.setProgress(sp.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA));
        sbKeyAlpha.setProgress(sp.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA));
        sbCorner.setProgress(sp.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER));
        swVoice.setChecked(sp.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE));
        swLeft.setChecked(sp.getBoolean(ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON));
        swRight.setChecked(sp.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON));
        swKeyBorder.setChecked(sp.getBoolean(ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER));
        updateLabels();
    }

    /** 将旧版 Activity 私有 SP 迁移到新的统一配置 SP。 */
    private void migrateLegacySettings(SharedPreferences target) {
        if (target.contains(ConfigContract.BLUR_RADIUS)) return;
        SharedPreferences legacy = getPreferences(MODE_PRIVATE);
        if (!legacy.contains("blur_radius")) return;

        target.edit()
                .putInt(ConfigContract.BLUR_RADIUS, legacy.getInt("blur_radius", ConfigContract.DEFAULT_BLUR))
                .putInt(ConfigContract.BG_ALPHA, legacy.getInt("bg_alpha", ConfigContract.DEFAULT_ALPHA))
                .putInt(ConfigContract.KEY_ALPHA, legacy.getInt("key_alpha", ConfigContract.DEFAULT_KEY_ALPHA))
                .putInt(ConfigContract.CORNER_RADIUS, legacy.getInt("corner_radius", ConfigContract.DEFAULT_CORNER))
                .putBoolean(ConfigContract.VOICE_ENABLED, legacy.getBoolean("voice_enabled", ConfigContract.DEFAULT_VOICE))
                .putBoolean(ConfigContract.SHOW_LEFT_BUTTON, legacy.getBoolean("show_left_button", ConfigContract.DEFAULT_LEFT_BUTTON))
                .putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, legacy.getBoolean("show_right_button", ConfigContract.DEFAULT_RIGHT_BUTTON))
                .putBoolean(ConfigContract.KEY_BORDER, legacy.getBoolean("key_border", ConfigContract.DEFAULT_KEY_BORDER))
                .commit();
        Log.i(TAG, "migrated legacy settings");
    }

    private void updateLabels() {
        tvBlur.setText(sbBlur.getProgress() == 0 ? "关" : String.valueOf(sbBlur.getProgress()));
        tvAlpha.setText((sbAlpha.getProgress() * 100) / 255 + "%");
        tvKeyAlpha.setText((sbKeyAlpha.getProgress() * 100) / 255 + "%");
        tvCorner.setText(sbCorner.getProgress() == 0 ? "关" : String.valueOf(sbCorner.getProgress()));
    }

    private void saveAndApply() {
        MainHook.Config config = new MainHook.Config();
        config.blur = sbBlur.getProgress();
        config.alpha = sbAlpha.getProgress();
        config.keyAlpha = sbKeyAlpha.getProgress();
        config.corner = sbCorner.getProgress();
        config.toolbar = config.corner;
        config.voice = swVoice.isChecked();
        config.leftBtn = swLeft.isChecked();
        config.rightBtn = swRight.isChecked();
        config.keyBorder = swKeyBorder.isChecked();

        Log.i(TAG, "Settings saving: L=" + config.leftBtn
                + " R=" + config.rightBtn + " V=" + config.voice);

        ContentValues values = ConfigContract.toValues(config);
        try {
            int updated = getContentResolver().update(
                    ConfigContract.CONTENT_URI, values, null, null);
            if (updated <= 0) writeLocalFallback(values);
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider update failed, writing local fallback", e);
            writeLocalFallback(values);
        }
    }

    private void writeLocalFallback(ContentValues values) {
        SharedPreferences.Editor editor = getConfigPreferences().edit();
        Object blur = values.get(ConfigContract.BLUR_RADIUS);
        Object alpha = values.get(ConfigContract.BG_ALPHA);
        Object keyAlpha = values.get(ConfigContract.KEY_ALPHA);
        Object corner = values.get(ConfigContract.CORNER_RADIUS);
        if (blur instanceof Integer) editor.putInt(ConfigContract.BLUR_RADIUS, (Integer) blur);
        if (alpha instanceof Integer) editor.putInt(ConfigContract.BG_ALPHA, (Integer) alpha);
        if (keyAlpha instanceof Integer) editor.putInt(ConfigContract.KEY_ALPHA, (Integer) keyAlpha);
        if (corner instanceof Integer) editor.putInt(ConfigContract.CORNER_RADIUS, (Integer) corner);
        if (values.containsKey(ConfigContract.VOICE_ENABLED)) {
            editor.putBoolean(ConfigContract.VOICE_ENABLED, values.getAsBoolean(ConfigContract.VOICE_ENABLED));
        }
        if (values.containsKey(ConfigContract.SHOW_LEFT_BUTTON)) {
            editor.putBoolean(ConfigContract.SHOW_LEFT_BUTTON, values.getAsBoolean(ConfigContract.SHOW_LEFT_BUTTON));
        }
        if (values.containsKey(ConfigContract.SHOW_RIGHT_BUTTON)) {
            editor.putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, values.getAsBoolean(ConfigContract.SHOW_RIGHT_BUTTON));
        }
        if (values.containsKey(ConfigContract.KEY_BORDER)) {
            editor.putBoolean(ConfigContract.KEY_BORDER, values.getAsBoolean(ConfigContract.KEY_BORDER));
        }
        editor.commit();
    }
}
