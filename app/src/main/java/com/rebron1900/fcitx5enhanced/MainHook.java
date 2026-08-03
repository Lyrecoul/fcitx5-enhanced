package com.rebron1900.fcitx5enhanced;

import android.content.SharedPreferences;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** Fcitx5 Frosted Glass — 毛玻璃键盘美观插件入口。 */
public class MainHook extends XposedModule {

    private static final String TAG = "Fcitx5Enh";
    private static final String PKG_FX = "org.fcitx.fcitx5.android.fx";
    private static final String PKG_ORIGINAL = "org.fcitx.fcitx5.android";
    private static final String CLS_SVC = "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    /** 配置快照，传递给各 Helper */
    public static class Config {
        public int blur = 100;
        public int alpha = 60;
        public int keyAlpha = 140;  // 按键背景透明度（独立于键盘背景）
        public int corner = 20;
        public int toolbar = 20;
        public boolean voice = true;
        public boolean leftBtn = true;
        public boolean rightBtn = true;
        public boolean keyBorder = true;

        /** 快速比较配置是否相等（避免不必要的全量重绘） */
        public boolean equals(Config o) {
            return o != null
                && blur == o.blur && alpha == o.alpha && keyAlpha == o.keyAlpha
                && corner == o.corner && keyBorder == o.keyBorder
                && leftBtn == o.leftBtn && rightBtn == o.rightBtn && voice == o.voice;
        }
    }

    /** 主题信息快照，避免各 Helper 重复反射读 theme */
    public static class ThemeInfo {
        public boolean isDark;
        public int keyBgColor;
        public int barColor;
        public int accentColor;
        public int altKeyTextColor;
    }

    private Config cfg = new Config();
    private java.lang.ref.WeakReference<View> mCurrentInputViewRef;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener mThemePrefListener;
    private boolean mConfigObserved;

    /** 上次全量应用过的 InputView（WeakReference 防泄漏；view 重建时必须重新应用） */
    private static java.lang.ref.WeakReference<View> sLastAppliedViewRef = null;

    /** 上次全量应用时的配置快照（用于跳过配置未变时的重复调用） */
    private static final Config sLastAppliedCfg = new Config();

    private static final java.util.WeakHashMap<View,
            java.lang.ref.WeakReference<android.graphics.drawable.Drawable>>
            sToolbarOriginalBackgrounds = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, ViewOutlineProvider>
            sToolbarOriginalOutlineProviders = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> sToolbarOriginalClipStates =
            new java.util.WeakHashMap<>();

    // ══════════════════════════════════════════
    //  Hook 入口
    // ══════════════════════════════════════════

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!PKG_FX.equals(pkg) && !PKG_ORIGINAL.equals(pkg)) return;
        Log.i(TAG, "init pkg=" + pkg);

        try {
            Class<?> svc = Class.forName(CLS_SVC, true, param.getClassLoader());
            Method setIv = svc.getMethod("setInputView", View.class);

            hook(setIv).intercept(chain -> {
                View v = (View) chain.getArgs().get(0);
                chain.proceed();

                String viewName = v != null ? v.getClass().getName() : "null";
                Log.i(TAG, "setInputView view=" + viewName);
                if (v != null) {
                    // setInputView 的参数就是目标输入法 View，不依赖被 R8 混淆的类名。
                    registerThemePrefListener(v);
                    registerConfigObserver(v);
                    mCurrentInputViewRef = new java.lang.ref.WeakReference<>(v);
                    View fv = v;
                    if (fv.getWidth() > 0 && fv.getHeight() > 0) {
                        // 即使复用同一个 InputView，也强制重建内部效果，覆盖语言/主题切换。
                        fv.post(() -> applyAllEffects(fv, true));
                    } else {
                        // post() 可能早于首次 layout，等 layout 完成后再应用。
                        fv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(View v, int l, int t, int r, int b,
                                                       int ol, int ot, int or, int ob) {
                                v.removeOnLayoutChangeListener(this);
                                v.post(() -> applyAllEffects(fv, true));
                            }
                        });
                    }
                }
                return null;
            });

            // 键盘弹出时重读配置 + 重绘
            Method onWindowShown = svc.getMethod("onWindowShown");
            hook(onWindowShown).intercept(chain -> {
                chain.proceed();
                View cv = getCurrentInputView();
                if (cv != null) {
                    // 已在 setInputView 强制应用；窗口重复显示时允许快照跳过。
                    cv.post(() -> applyAllEffects(cv));
                }
                return null;
            });

            Log.i(TAG, "hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "hook failed", t);
        }
    }

    // ══════════════════════════════════════════
    //  Config — Provider 读取，旧版目标进程 SP 兜底
    // ══════════════════════════════════════════

    /** 从模块 SharedPreferences Provider 读取配置，失败时兼容旧版目标进程 SP。 */
    private void readConfig(View anyView) {
        cfg = readConfigSync(anyView);
    }

    /** 同步读取配置（static，供外部调用）。 */
    public static Config readConfigSync(View anyView) {
        try (android.database.Cursor cursor = anyView.getContext().getContentResolver().query(
                ConfigContract.CONTENT_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                Config config = ConfigContract.fromCursor(cursor);
                Log.i(TAG, "readConfig from provider: L=" + config.leftBtn + " R=" + config.rightBtn);
                return config;
            }
        } catch (Throwable t) {
            Log.w(TAG, "readConfig provider failed, using legacy SP: " + t.getMessage());
        }

        // 仅作为旧版本升级兜底；新版本不再写入目标进程 SP。
        Config config = new Config();
        try {
            SharedPreferences sp = anyView.getContext().getSharedPreferences(
                    ConfigContract.PREFS_NAME, android.content.Context.MODE_PRIVATE);
            config.blur = sp.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR);
            config.alpha = sp.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA);
            config.keyAlpha = sp.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA);
            config.corner = sp.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER);
            config.toolbar = config.corner;
            config.voice = sp.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE);
            config.leftBtn = sp.getBoolean(ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON);
            config.rightBtn = sp.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON);
            config.keyBorder = sp.getBoolean(ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER);
        } catch (Throwable t) {
            Log.w(TAG, "readConfig fallback failed: " + t.getMessage());
        }
        ConfigContract.sanitize(config);
        return config;
    }

    // ══════════════════════════════════════════
    //  Apply all visual effects
    // ══════════════════════════════════════════

    private void applyAllEffects(View inputView) {
        applyAllEffects(inputView, false);
    }

    private void applyAllEffects(View inputView, boolean force) {
        readConfig(inputView);

        // setInputView、主题变化和 Provider 通知使用 force；窗口重复显示可用快照跳过。
        // 同一个 InputView 也可能已经替换了内部键盘树或主题对象。
        View lastView = sLastAppliedViewRef != null ? sLastAppliedViewRef.get() : null;
        if (!force && sLastAppliedCfg.equals(cfg) && lastView == inputView) {
            Log.d(TAG, "applyAllEffects: config unchanged, skip");
            return;
        }
        sLastAppliedViewRef = new java.lang.ref.WeakReference<>(inputView);
        // 更新快照
        sLastAppliedCfg.blur = cfg.blur;
        sLastAppliedCfg.alpha = cfg.alpha;
        sLastAppliedCfg.keyAlpha = cfg.keyAlpha;
        sLastAppliedCfg.corner = cfg.corner;
        sLastAppliedCfg.toolbar = cfg.toolbar;
        sLastAppliedCfg.voice = cfg.voice;
        sLastAppliedCfg.leftBtn = cfg.leftBtn;
        sLastAppliedCfg.rightBtn = cfg.rightBtn;
        sLastAppliedCfg.keyBorder = cfg.keyBorder;

        Log.i(TAG, "applyAllEffects start");

        // 一次性提取主题信息，避免各 Helper 重复反射
        ThemeInfo themeInfo = new ThemeInfo();
        try {
            Field tf = findField(inputView.getClass(), "theme");
            if (tf == null) throw new NoSuchFieldException("theme");
            tf.setAccessible(true);
            Object theme = tf.get(inputView);
            themeInfo.isDark = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
            themeInfo.keyBgColor = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
            themeInfo.barColor = (Integer) theme.getClass().getMethod("getBarColor").invoke(theme);
            themeInfo.accentColor = (Integer) theme.getClass().getMethod("getAccentKeyBackgroundColor").invoke(theme);
            themeInfo.altKeyTextColor = (Integer) theme.getClass().getMethod("getAltKeyTextColor").invoke(theme);
        } catch (Exception ignored) {}

        // 同一份 cfg + themeInfo 传给所有 Helper，避免重复读 SP/file 和反射
        final MainHook.Config c = cfg;
        final MainHook.ThemeInfo ti = themeInfo;

        FrostedGlassHelper.apply(inputView, c, ti);
        roundToolbarTop(inputView);
        PreeditHelper.apply(inputView, c, ti);
        ExtraButtonsHelper.add(inputView, c, ti);
        KeyEffectsHelper.apply(inputView, c, ti.isDark);

        Log.i(TAG, "applyAllEffects done");
    }

    // ══════════════════════════════════════════
    //  工具栏圆角
    // ══════════════════════════════════════════

    private void roundToolbarTop(View inputView) {
        try {
            View toolbar = findToolbarView(inputView);
            if (toolbar == null) {
                Log.w(TAG, "toolbar bar field not found, skip");
                return;
            }
            if (cfg.toolbar <= 0) {
                clearToolbarRounding(toolbar);
                return;
            }
            roundToolbarTopWithRetry(inputView, toolbar, 0);
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> cls = start; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private View findToolbarView(View inputView) throws Exception {
        Object bar = null;
        for (Class<?> cls = inputView.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (String fieldName : new String[]{"kawaiiBar", "toolbarBar", "toolbar"}) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    bar = f.get(inputView);
                    if (bar != null) break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (bar != null) break;
        }
        if (bar == null) return null;
        Method gv = bar.getClass().getMethod("getView");
        Object toolbar = gv.invoke(bar);
        return toolbar instanceof View ? (View) toolbar : null;
    }

    private void clearToolbarRounding(View toolbar) {
        Object tag = toolbar.getTag(R.id.tag_toolbar_original_background);
        Drawable taggedOriginal = tag instanceof Drawable ? (Drawable) tag : null;
        toolbar.setTag(R.id.tag_toolbar_original_background, null);
        java.lang.ref.WeakReference<Drawable> originalRef =
                sToolbarOriginalBackgrounds.containsKey(toolbar)
                        ? sToolbarOriginalBackgrounds.remove(toolbar) : null;
        if (taggedOriginal != null || originalRef != null) {
            toolbar.setBackground(taggedOriginal != null
                    ? taggedOriginal : originalRef.get());
        }

        ViewParent parent = toolbar.getParent();
        if (parent instanceof View) {
            View parentView = (View) parent;
            if (sToolbarOriginalClipStates.containsKey(parentView)) {
                Boolean originalClip = sToolbarOriginalClipStates.remove(parentView);
                parentView.setClipToOutline(Boolean.TRUE.equals(originalClip));
            }
            if (sToolbarOriginalOutlineProviders.containsKey(parentView)) {
                parentView.setOutlineProvider(sToolbarOriginalOutlineProviders.remove(parentView));
            }
        }
        Log.i(TAG, "toolbar corners disabled");
    }

    private void roundToolbarTopWithRetry(View inputView, View toolbar, int attempt) {
        try {
            if (cfg.toolbar <= 0) {
                clearToolbarRounding(toolbar);
                return;
            }
            if (attempt > 5) {
                Log.w(TAG, "toolbar retry exhausted, skip");
                return;
            }
            if (toolbar.getWidth() <= 0 || toolbar.getHeight() <= 0) {
                toolbar.post(() -> roundToolbarTopWithRetry(inputView, toolbar, attempt + 1));
                return;
            }

            float radiusPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, cfg.toolbar,
                    inputView.getResources().getDisplayMetrics());

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
            gd.setColor(Color.TRANSPARENT);
            if (!sToolbarOriginalBackgrounds.containsKey(toolbar)) {
                Drawable original = toolbar.getBackground();
                toolbar.setTag(R.id.tag_toolbar_original_background, original);
                sToolbarOriginalBackgrounds.put(toolbar,
                        new java.lang.ref.WeakReference<>(original));
            }
            toolbar.setBackground(gd);

            ViewParent parent = toolbar.getParent();
            if (parent instanceof View) {
                final float pr = radiusPx;
                View parentView = (View) parent;
                if (!sToolbarOriginalClipStates.containsKey(parentView)) {
                    sToolbarOriginalClipStates.put(parentView, parentView.getClipToOutline());
                    sToolbarOriginalOutlineProviders.put(parentView, parentView.getOutlineProvider());
                }
                parentView.setClipToOutline(true);
                parentView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int pw = view.getWidth(), ph = view.getHeight();
                        if (pw <= 0 || ph <= 0) return;
                        // 让底部圆角落到可见区域之外，只显示上方圆角。
                        // RoundRect 是 clipToOutline 在所有目标版本都可靠支持的形状；
                        // ConvexPath 在部分 Android 12+ 设备上只作用于阴影而不会裁剪。
                        outline.setRoundRect(0, 0, pw, ph + (int) pr + 1, pr);
                    }
                });
            }

            Log.i(TAG, "toolbar round r=" + cfg.toolbar + "dp");
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  Theme preference change listener
    // ══════════════════════════════════════════

    /** 监听 fcitx5 主题配置变化（key_radius 等），自动重绘按键描边。 */
    private void registerThemePrefListener(View anyView) {
        try {
            if (mThemePrefListener != null) return; // 只注册一次
            android.content.SharedPreferences sp =
                android.preference.PreferenceManager.getDefaultSharedPreferences(
                    anyView.getContext());
            mThemePrefListener = (sp_, key) -> {
                try {
                    Log.i(TAG, key + " changed, re-applying effects");
                    View cv = getCurrentInputView();
                    if (cv != null) cv.post(() -> applyAllEffects(cv, true));
                } catch (Throwable t) {
                    Log.w(TAG, "theme pref listener: " + t.getMessage());
                }
            };
            sp.registerOnSharedPreferenceChangeListener(mThemePrefListener);
            Log.i(TAG, "theme pref listener registered");
        } catch (Throwable t) {
            Log.w(TAG, "register theme pref listener failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  ConfigProvider ContentObserver
    // ══════════════════════════════════════════

    /** 监听 ConfigProvider 变化（SettingsActivity 写入时触发）。 */
    private void registerConfigObserver(View anyView) {
        if (mConfigObserved) return;
        try {
            anyView.getContext().getContentResolver().registerContentObserver(
                    ConfigContract.CONTENT_URI, false,
                    new android.database.ContentObserver(
                            new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            View cv = getCurrentInputView();
                            if (cv != null) cv.post(() -> applyAllEffects(cv, true));
                        }
                    });
            mConfigObserved = true;
            Log.i(TAG, "config observer registered");
        } catch (Throwable t) {
            Log.w(TAG, "config observer failed: " + t);
        }
    }

    private View getCurrentInputView() {
        return mCurrentInputViewRef != null ? mCurrentInputViewRef.get() : null;
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam p) {
        Log.i(TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam p) {
        String pkg = p.getPackageName();
        if (PKG_FX.equals(pkg) || PKG_ORIGINAL.equals(pkg)) {
            Log.i(TAG, "pkg_loaded " + pkg);
        }
    }
}
