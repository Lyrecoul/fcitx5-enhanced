package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** 按键半透明磨砂效果 + 描边。 */
public class KeyEffectsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static ViewTreeObserver.OnGlobalLayoutListener mKeyLayoutListener;
    /** 弱引用：防止 static 强引用旧键盘树导致泄漏 */
    private static java.lang.ref.WeakReference<ViewGroup> mAttachedViewRef;

    /** 保存每个按键的原始 foreground（press highlight），防止重绘时嵌套叠加。 */
    private static final WeakHashMap<View, java.lang.ref.WeakReference<Drawable>> sOriginalForegrounds =
            new WeakHashMap<>();

    /** 记录被修改过的 drawable alpha，以便关闭透明度效果时恢复原值。 */
    private static final WeakHashMap<Drawable, Integer> sOriginalAlphas = new WeakHashMap<>();

    /** 标记已设置描边的 view，避免重复设置 */
    private static final WeakHashMap<View, Boolean> sBorderedViews = new WeakHashMap<>();

    /** 缓存 appearanceView 反射结果；WeakReference 防止 value 反向持有 key。 */
    private static final WeakHashMap<View, java.lang.ref.WeakReference<View>> sAppearanceCache =
            new WeakHashMap<>();

    /** 缓存 Field 对象（Class→Field），避免重复 getDeclaredField */
    private static final WeakHashMap<Class<?>, Field> sAppearanceFieldCache = new WeakHashMap<>();

    /** 缓存资源 ID，避免每次 getIdentifier 查找 */
    private static int sIdReturn = -1;
    private static int sIdSwitch = -1;
    private static boolean sIdResolved = false;

    /** 标记 listener 中是否正在执行，防止重入 */
    private static boolean sApplying = false;

    /** 记录上次的 wmView（弱引用），检测 InputView 是否变化 */
    private static java.lang.ref.WeakReference<ViewGroup> sLastWmViewRef = null;

    /** 键盘 View 树哈希（快速检测结构变化，避免每次 layout 全量遍历） */
    private static int sLastViewHash = 0;

    /** 每次 apply 时从 SP 读取的主题参数（不缓存，保证实时性） */
    private static int sKeyRadius = 4;
    private static boolean sSpecialKeyOval = false;

    public static void apply(View inputView, MainHook.Config c, boolean isDark) {
        try {
            Field wf = findField(inputView.getClass(), "windowManager");
            if (wf == null) throw new NoSuchFieldException("windowManager");
            wf.setAccessible(true);
            Object wm = wf.get(inputView);
            Method gv = wm.getClass().getMethod("getView");
            final ViewGroup wmView = (ViewGroup) gv.invoke(wm);
            if (wmView == null) return;

            int keyAlpha = c.keyAlpha;

            // 主题参数必须在首次遍历前读取，否则首轮描边会使用默认圆角。
            readThemePreferences(inputView);
            resolveSpecialKeyIds(inputView);

            // 移除旧 listener
            removeOldListener();

            // 只在 InputView 变化时清除缓存（新 view 树，旧缓存无效）
            ViewGroup lastWm = sLastWmViewRef != null ? sLastWmViewRef.get() : null;
            if (wmView != lastWm) {
                sBorderedViews.clear();
                sOriginalForegrounds.clear();
                sAppearanceCache.clear();
                sLastWmViewRef = new java.lang.ref.WeakReference<>(wmView);
            }

            // 单次遍历完成透明度+描边，避免两次全树遍历
            applyKeyEffects(wmView, keyAlpha, c, isDark);

            // listener 处理新增按键和中英文切换
            // 使用 View 树哈希快速检测结构变化，命中跳过遍历以提升打字帧率
            final java.lang.ref.WeakReference<ViewGroup> wmViewRef =
                    new java.lang.ref.WeakReference<>(wmView);
            mKeyLayoutListener = () -> {
                if (sApplying) return;  // 防重入
                ViewGroup current = wmViewRef.get();
                if (current == null) return;
                sApplying = true;
                try {
                    int newHash = computeViewHash(current);
                    if (newHash == sLastViewHash) {
                        Log.d(TAG, "layout: view tree unchanged, skip traversal");
                        return;
                    }
                    sLastViewHash = newHash;
                    Log.i(TAG, "layout: view tree changed, applying key effects");
                    applyKeyEffects(current, c.keyAlpha, c, isDark);
                } finally {
                    sApplying = false;
                }
            };
            mAttachedViewRef = new java.lang.ref.WeakReference<>(wmView);
            wmView.getViewTreeObserver().addOnGlobalLayoutListener(mKeyLayoutListener);

            // 初始遍历后记录哈希值（下次 layout 变化时才重新遍历）
            sLastViewHash = computeViewHash(wmView);

            Log.i(TAG, "key effects: alpha=" + keyAlpha);
        } catch (Throwable t) {
            Log.w(TAG, "applyKeyEffects: " + t.getMessage());
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

    private static void removeOldListener() {
        ViewGroup attached = mAttachedViewRef != null ? mAttachedViewRef.get() : null;
        if (mKeyLayoutListener != null && attached != null) {
            try {
                ViewTreeObserver vto = attached.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(mKeyLayoutListener);
                }
            } catch (Exception ignored) {}
        }
        mKeyLayoutListener = null;
        mAttachedViewRef = null;
    }

    /** 计算有限深度的 View 树哈希，覆盖语言切换时替换的内部按键。 */
    private static int computeViewHash(ViewGroup root) {
        return computeViewHash(root, 0);
    }

    private static int computeViewHash(ViewGroup root, int depth) {
        int hash = 31 + root.getChildCount();
        if (depth >= 4) return hash;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            hash = hash * 31 + System.identityHashCode(child);
            if (child instanceof ViewGroup) {
                hash = hash * 31 + computeViewHash((ViewGroup) child, depth + 1);
            }
        }
        return hash;
    }

    private static void resolveSpecialKeyIds(View inputView) {
        if (sIdResolved) return;
        sIdResolved = true;
        String pkg = inputView.getContext().getPackageName();
        android.content.res.Resources res = inputView.getContext().getResources();
        sIdReturn = res.getIdentifier("button_return", "id", pkg);
        sIdSwitch = res.getIdentifier("button_layout_switch", "id", pkg);
    }

    private static void readThemePreferences(View inputView) {
        try {
            String pkg = inputView.getContext().getPackageName();
            SharedPreferences sp = inputView.getContext().getSharedPreferences(
                    pkg + "_preferences", Context.MODE_PRIVATE);
            sKeyRadius = sp.getInt("key_radius", 4);
            sSpecialKeyOval = sp.getBoolean("special_key_oval_shape", false);
        } catch (Exception ignored) {}
    }

    private static void applyKeyEffects(ViewGroup root, int alpha,
                                         MainHook.Config c, boolean isDark) {
        boolean needAlpha = alpha <= 250;
        boolean needBorder = c.keyBorder;

        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            try {
                View appView = findAppearanceView(child);
                if (appView != null) {
                    Drawable bg = appView.getBackground();
                    if (bg != null) {
                        if (needAlpha) {
                            setDrawableAlphaTree(bg, alpha);
                        } else {
                            restoreDrawableAlphaTree(bg);
                        }
                    }

                    // 描边配置、主题或圆角变化时都重建 Drawable，避免旧缓存阻止更新。
                    if (needBorder) {
                        applyKeyGlassBorder(appView, c, isDark);
                    } else {
                        removeBorderFromView(appView);
                    }
                }
            } catch (Exception ignored) {}
            if (child instanceof ViewGroup) {
                applyKeyEffects((ViewGroup) child, alpha, c, isDark);
            }
        }
    }

    private static void rememberDrawableAlpha(Drawable drawable) {
        if (!sOriginalAlphas.containsKey(drawable)) {
            sOriginalAlphas.put(drawable, drawable.getAlpha());
        }
    }

    private static void setDrawableAlphaTree(Drawable drawable, int alpha) {
        rememberDrawableAlpha(drawable);
        drawable.setAlpha(alpha);
        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            Drawable inner = ((android.graphics.drawable.InsetDrawable) drawable).getDrawable();
            if (inner != null) setDrawableAlphaTree(inner, alpha);
        } else if (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layer =
                    (android.graphics.drawable.LayerDrawable) drawable;
            for (int i = 0; i < layer.getNumberOfLayers(); i++) {
                Drawable child = layer.getDrawable(i);
                if (child != null) setDrawableAlphaTree(child, alpha);
            }
        }
    }

    private static void restoreDrawableAlphaTree(Drawable drawable) {
        Integer original = sOriginalAlphas.get(drawable);
        if (original != null) drawable.setAlpha(original);
        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            Drawable inner = ((android.graphics.drawable.InsetDrawable) drawable).getDrawable();
            if (inner != null) restoreDrawableAlphaTree(inner);
        } else if (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layer =
                    (android.graphics.drawable.LayerDrawable) drawable;
            for (int i = 0; i < layer.getNumberOfLayers(); i++) {
                Drawable child = layer.getDrawable(i);
                if (child != null) restoreDrawableAlphaTree(child);
            }
        }
    }

    /** 移除单个 view 的描边 */
    private static void removeBorderFromView(View appView) {
        try {
            Drawable fg = appView.getForeground();
            if (fg instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) fg;
                if (ld.getNumberOfLayers() == 2 && ld.getDrawable(0) instanceof GlassBorderDrawable) {
                    appView.setForeground(ld.getDrawable(1));
                }
            } else if (fg instanceof GlassBorderDrawable) {
                appView.setForeground(null);
            }
            sBorderedViews.remove(appView);
            sOriginalForegrounds.remove(appView);
        } catch (Exception ignored) {}
    }

    /** 带缓存的 appearanceView 查找（缓存 Field 对象 + 结果） */
    private static View findAppearanceView(View v) {
        java.lang.ref.WeakReference<View> cachedRef = sAppearanceCache.get(v);
        View cached = cachedRef != null ? cachedRef.get() : null;
        if (cached != null) return cached;
        if (cachedRef != null) sAppearanceCache.remove(v);

        Class<?> c = v.getClass();
        while (c != null && c != Object.class) {
            Field f = sAppearanceFieldCache.get(c);
            if (f == null && sAppearanceFieldCache.containsKey(c)) {
                c = c.getSuperclass();
                continue;
            }
            if (f == null) {
                try {
                    f = c.getDeclaredField("appearanceView");
                    f.setAccessible(true);
                    sAppearanceFieldCache.put(c, f);
                } catch (NoSuchFieldException ignored) {
                    sAppearanceFieldCache.put(c, null);
                    c = c.getSuperclass();
                    continue;
                }
            }
            try {
                View result = (View) f.get(v);
                if (result != null) {
                    sAppearanceCache.put(v, new java.lang.ref.WeakReference<>(result));
                }
                return result;
            } catch (Exception ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    // ══════════════════════════════════════════
    //  按键玻璃描边 — 每个按键顶部+转角
    // ══════════════════════════════════════════

    /** 从 actual drawable 链解析：边距、圆角、形状 */
    private static class KeyBgInfo {
        float radius;
        int hInset;
        int vInset;
        boolean isOval;
        boolean isPill;
        KeyBgInfo(float r, int h, int v, boolean oval, boolean pill) {
            radius = r; hInset = h; vInset = v; isOval = oval; isPill = pill;
        }
    }

    /** 缓存 GradientDrawable 内部字段（mShape, mRadius） */
    private static Field sShapeField;
    private static Field sRadiusField;
    private static boolean sDrawableFieldsResolved = false;

    /** 遍历 drawable 链提取实际圆角和形状 */
    private static KeyBgInfo parseKeyBg(Drawable bg, Context ctx, float den) {
        int hInset = 0, vInset = 0;
        float radius = 0f;
        boolean isOval = false;
        boolean isPill = false;

        Drawable d = bg;
        while (d != null) {
            if (d instanceof android.graphics.drawable.InsetDrawable) {
                android.graphics.drawable.InsetDrawable id = (android.graphics.drawable.InsetDrawable) d;
                android.graphics.Rect pad = new android.graphics.Rect();
                id.getPadding(pad);
                hInset = Math.max(hInset, pad.left);
                vInset = Math.max(vInset, pad.top);
                d = id.getDrawable();
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                int last = ld.getNumberOfLayers() - 1;
                int inL = ld.getLayerInsetLeft(last);
                int inT = ld.getLayerInsetTop(last);
                int inR = ld.getLayerInsetRight(last);
                int inB = ld.getLayerInsetBottom(last);
                if (inL == inR && inT == inB) {
                    hInset = Math.max(hInset, inL);
                    vInset = Math.max(vInset, inT);
                }
                d = ld.getDrawable(last);
            } else if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd = (android.graphics.drawable.GradientDrawable) d;
                try {
                    // 缓存 Field 对象，避免每次 getDeclaredField
                    if (!sDrawableFieldsResolved) {
                        sDrawableFieldsResolved = true;
                        try {
                            sShapeField = android.graphics.drawable.GradientDrawable.class
                                    .getDeclaredField("mShape");
                            sShapeField.setAccessible(true);
                        } catch (Exception ignored) {}
                        try {
                            sRadiusField = android.graphics.drawable.GradientDrawable.class
                                    .getDeclaredField("mRadius");
                            sRadiusField.setAccessible(true);
                        } catch (Exception ignored) {}
                    }
                    if (sShapeField != null) {
                        int shape = sShapeField.getInt(gd);
                        isOval = (shape == android.graphics.drawable.GradientDrawable.OVAL);
                    }
                } catch (Exception ignored) {}
                if (Build.VERSION.SDK_INT >= 29) {
                    float[] radii = gd.getCornerRadii();
                    if (radii != null && radii.length > 0 && radii[0] > 0) {
                        radius = radii[0];
                    }
                }
                if (radius == 0) {
                    try {
                        if (sRadiusField != null) {
                            float fr = sRadiusField.getFloat(gd);
                            if (fr > 0) radius = fr;
                        }
                    } catch (Exception ignored) {}
                }
                if (radius >= 10000f) {
                    isPill = true;
                    isOval = false;
                    radius = 0f;
                }
                break;
            } else {
                break;
            }
        }
        // 兜底：用缓存的 key_radius（仅当 drawable 链解析不到时）
        if (radius == 0 && !isOval) {
            int kr = sKeyRadius;
            if (kr < 0) kr = 0;
            if (kr > 48) kr = 48;
            radius = Math.max(kr * den, 2f * den);
        }

        return new KeyBgInfo(radius, hInset, vInset, isOval, isPill);
    }

    /** 给单个按键套上描边 foreground */
    private static void applyKeyGlassBorder(View keyView, MainHook.Config c, boolean isDark) {
        try {
            float den = keyView.getResources().getDisplayMetrics().density;

            int borderTop, borderBottom;
            float borderWidthPx;
            if (isDark) {
                borderTop = 0x33FFFFFF;
                borderBottom = 0x33FFFFFF;
                borderWidthPx = 0.8f * den;
            } else {
                borderTop = 0x88FFFFFF;
                borderBottom = 0x88FFFFFF;
                borderWidthPx = 0.8f * den;
            }

            int hMargin = 0, vMargin = 0;
            View outer = null;
            try {
                outer = (View) keyView.getParent();
                if (outer != null) {
                    boolean foundH = false, foundV = false;
                    Class<?> cls = outer.getClass();
                    while (cls != null && cls != Object.class && (!foundH || !foundV)) {
                        if (!foundH) {
                            try {
                                Field hm = cls.getDeclaredField("hMargin");
                                hm.setAccessible(true);
                                hMargin = hm.getInt(outer);
                                foundH = true;
                            } catch (NoSuchFieldException ignored) {}
                        }
                        if (!foundV) {
                            try {
                                Field vm = cls.getDeclaredField("vMargin");
                                vm.setAccessible(true);
                                vMargin = vm.getInt(outer);
                                foundV = true;
                            } catch (NoSuchFieldException ignored) {}
                        }
                        if (!foundH || !foundV) cls = cls.getSuperclass();
                        else break;
                    }
                }
            } catch (Exception ignored) {}

            KeyBgInfo info = parseKeyBg(keyView.getBackground(), keyView.getContext(), den);

            // 药丸检测：用缓存的资源 ID 和 SP 值
            if (!info.isPill && !info.isOval && outer != null && sIdResolved && sSpecialKeyOval) {
                try {
                    Object tag = outer.getTag();
                    if (tag instanceof Integer) {
                        int vid = (Integer) tag;
                        if (vid == sIdReturn || vid == sIdSwitch) {
                            info.isPill = true;
                            info.radius = Math.min(keyView.getWidth(), keyView.getHeight()) * 0.5f;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (info.isPill && info.radius <= 0) {
                info.radius = Math.min(keyView.getWidth(), keyView.getHeight()) * 0.5f;
            }

            int useH = Math.max(hMargin, info.hInset);
            int useV = Math.max(vMargin, info.vInset);

            GlassBorderDrawable gb;
            if (info.isOval) {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx * 1.8f,
                        GlassBorderDrawable.MODE_DIAGONAL, true, useH, useV);
            } else if (info.isPill) {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx * 1.8f,
                        GlassBorderDrawable.MODE_DIAGONAL, false, useH, useV);
            } else {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx,
                        GlassBorderDrawable.MODE_DIAGONAL, false, useH, useV);
            }

            java.lang.ref.WeakReference<Drawable> originalRef = sOriginalForegrounds.get(keyView);
            Drawable originalFg = originalRef != null ? originalRef.get() : null;
            if (originalRef == null) {
                Drawable currentFg = keyView.getForeground();
                // 如果当前 foreground 是上次 apply 的 LayerDrawable(GlassBorder + 原始)，解包
                if (currentFg instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) currentFg;
                    if (ld.getNumberOfLayers() == 2 && ld.getDrawable(0) instanceof GlassBorderDrawable) {
                        originalFg = ld.getDrawable(1);
                    } else {
                        originalFg = currentFg;
                    }
                } else if (currentFg instanceof GlassBorderDrawable) {
                    originalFg = null;
                } else {
                    originalFg = currentFg;
                }
                sOriginalForegrounds.put(keyView, new java.lang.ref.WeakReference<>(originalFg));
            }

            if (originalFg != null) {
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new Drawable[]{gb, originalFg});
                keyView.setForeground(ld);
            } else {
                keyView.setForeground(gb);
            }

            sBorderedViews.put(keyView, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "key glass border: " + t.getMessage());
        }
    }
}
