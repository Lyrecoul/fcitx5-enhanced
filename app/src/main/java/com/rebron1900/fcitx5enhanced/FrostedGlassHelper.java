package com.rebron1900.fcitx5enhanced;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 键盘毛玻璃 + 圆角裁剪。 */
public class FrostedGlassHelper {
    private static final String TAG = "Fcitx5Enh";

    // ══════════════════════════════════════════
    //  性能缓存
    // ══════════════════════════════════════════

    /** 缓存 customBackground：value 不能反向持有 key，避免 WeakHashMap 失去弱引用效果。 */
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<ImageView>> sCustomBgCache =
            new java.util.WeakHashMap<>();

    /** 每个背景 View 独占一张 tint bitmap，避免旧键盘与新键盘共享并发擦除同一 Bitmap。 */
    private static final java.util.WeakHashMap<ImageView, TintBitmap> sTintBitmapCache =
            new java.util.WeakHashMap<>();

    private static final class TintBitmap {
        final Bitmap bitmap;
        final int width;
        final int height;
        final int fingerprint;

        TintBitmap(Bitmap bitmap, int width, int height, int fingerprint) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.fingerprint = fingerprint;
        }
    }

    /** 保存背景 ImageView 原始 ScaleType，关闭毛玻璃后恢复，避免后续查找失效。 */
    private static final java.util.WeakHashMap<ImageView, ImageView.ScaleType>
            sOriginalFrostedScaleTypes = new java.util.WeakHashMap<>();
    /** 记录本次由 Helper 接管的状态，目标 View 重建 Drawable 后可更新恢复基线。 */
    private static final java.util.WeakHashMap<ImageView, java.lang.ref.WeakReference<Drawable>>
            sManagedFrostedBackgrounds = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ImageView, java.lang.ref.WeakReference<Drawable>>
            sManagedFrostedImages = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ImageView, java.lang.ref.WeakReference<Drawable>>
            sManagedFrostedForegrounds = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ImageView, ImageView.ScaleType>
            sManagedFrostedScaleTypes = new java.util.WeakHashMap<>();

    /** 键盘前景可能由目标输入法自己使用，描边关闭时恢复原始 foreground。 */
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<Drawable>>
            sOriginalKeyboardForegrounds = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> sOriginalKeyboardClipStates =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, ViewOutlineProvider>
            sOriginalKeyboardOutlineProviders = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> sSavedTransparentBackgrounds =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> sSavedFrostedBackgrounds =
            new java.util.WeakHashMap<>();

    public static boolean apply(View inputView, MainHook.Config c, MainHook.ThemeInfo ti) {
        boolean frostedApplied = applyFrostedGlass(inputView, c, ti);
        boolean cornersApplied = applyRoundedCorners(inputView, c, ti);
        return frostedApplied && cornersApplied;
    }

    // ══════════════════════════════════════════
    //  毛玻璃 — ViewRootImpl.createBackgroundBlurDrawable()
    // ══════════════════════════════════════════
    private static boolean applyFrostedGlass(View inputView, MainHook.Config c, MainHook.ThemeInfo ti) {
        try {
            ImageView bg = findCustomBackground(inputView);
            if (bg == null) {
                Log.w(TAG, "customBackground ImageView not found");
                return false;
            }
            refreshFrostedOriginalState(bg);

            // 直接用传入的 ThemeInfo，不再反射
            boolean isDark = ti.isDark;
            int keyBgColor = ti.keyBgColor;

            Object viewRootImpl = null;
            try {
                Method getVri = View.class.getMethod("getViewRootImpl");
                viewRootImpl = getVri.invoke(inputView);
            } catch (Exception ignored) {
                viewRootImpl = inputView.getRootView().getParent();
            }

            if (c.blur <= 0) {
                clearFrostedEffect(bg);
                Log.i(TAG, "frosted glass disabled");
                return true;
            }

            if (viewRootImpl != null) {
                Method createBlur = viewRootImpl.getClass()
                        .getDeclaredMethod("createBackgroundBlurDrawable");
                Object blurDrawable = createBlur.invoke(viewRootImpl);

                if (blurDrawable != null) {
                    blurDrawable.getClass().getMethod("setBlurRadius", Integer.TYPE)
                            .invoke(blurDrawable, c.blur);
                    blurDrawable.getClass().getMethod("setColor", Integer.TYPE)
                            .invoke(blurDrawable, Color.TRANSPARENT);

                    rememberFrostedBackground(bg);
                    bg.setBackground((Drawable) blurDrawable);

                    // tint 位图：四角填色遮 BlurDrawable 矩形模糊
                    int alpha = c.alpha;
                    int w = Math.max(1, bg.getWidth());
                    int h = Math.max(1, bg.getHeight());

                    // 计算指纹：alpha + corner + w + h + keyBgColor + isDark
                    int tintFp = alpha ^ (c.corner << 8) ^ (w << 12) ^ (h << 18)
                               ^ keyBgColor ^ (isDark ? 0x20000000 : 0);

                    Bitmap tint = obtainTintBitmap(bg, w, h, tintFp);
                    Canvas cnv = new Canvas(tint);

                    int topColor, bottomColor;
                    if (keyBgColor != 0) {
                        // 从主题按键底色推导渐变
                        int baseR = Color.red(keyBgColor);
                        int baseG = Color.green(keyBgColor);
                        int baseB = Color.blue(keyBgColor);
                        topColor = Color.argb(alpha, baseR, baseG, baseB);
                        bottomColor = Color.argb(Math.min(255, alpha + 20),
                                Math.max(0, baseR - 15),
                                Math.max(0, baseG - 15),
                                Math.max(0, baseB - 15));
                    } else if (isDark) {
                        topColor = Color.argb(alpha, 30, 35, 50);
                        bottomColor = Color.argb(alpha, 20, 25, 40);
                    } else {
                        topColor = Color.argb(alpha, 245, 248, 255);
                        bottomColor = Color.argb(alpha, 225, 230, 245);
                    }

                    // 圆角外保持透明。不能预先填满 bottomColor，否则矩形 tint 会盖住扣空区域。
                    if (c.corner > 0) {
                        float cr = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, c.corner,
                                inputView.getResources().getDisplayMetrics());
                        Path cornerPath = new Path();
                        cornerPath.addRoundRect(0, 0, w, h, cr, cr, Path.Direction.CW);
                        cnv.save();
                        cnv.clipPath(cornerPath);
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(cnv);
                        cnv.restore();
                    } else {
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(cnv);
                    }
                    // 恢复旧版语义：有自定义图片时降低原图可见度；无图片时直接显示 tint 位图。
                    Drawable oldImage = bg.getDrawable();
                    boolean isOurTint = Boolean.TRUE.equals(bg.getTag(R.id.tag_frosted_tint));
                    boolean hasCustomImage = !isOurTint
                            && (oldImage instanceof android.graphics.drawable.BitmapDrawable)
                            && ((android.graphics.drawable.BitmapDrawable) oldImage).getBitmap() != null;

                    if (hasCustomImage) {
                        bg.setImageAlpha(Math.max(10, 255 - alpha));
                        bg.setForeground(new android.graphics.drawable.ColorDrawable(
                                Color.argb(Math.min(255, alpha / 2), 0, 0, 0)));
                        bg.setTag(R.id.tag_frosted_tint, null);
                        Log.i(TAG, "✅ REAL blur=" + c.blur + " alpha=" + alpha + " (custom image preserved)");
                    } else {
                        // 不 recycle 旧 Bitmap：可能被其他 View 共享，交给 GC。
                        bg.setImageBitmap(tint);
                        bg.setScaleType(ImageView.ScaleType.FIT_XY);
                        bg.setImageAlpha(255);
                        bg.setForeground(null);
                        bg.setTag(R.id.tag_frosted_tint, Boolean.TRUE);
                        Log.i(TAG, "✅ REAL blur=" + c.blur + " alpha=" + alpha + " dark=" + isDark);
                    }
                    rememberManagedFrostedState(bg, (Drawable) blurDrawable);
                    return true;
                } else {
                    Log.w(TAG, "createBackgroundBlurDrawable returned null");
                    return fallback(bg, inputView, isDark, c, keyBgColor);
                }
            } else {
                Log.w(TAG, "viewRootImpl=" + viewRootImpl + " blur=" + c.blur);
                return fallback(bg, inputView, isDark, c, keyBgColor);
            }
        } catch (Throwable t) {
            Log.w(TAG, "frosted glass failed: " + t);
        }
        return false;
    }

    private static Bitmap obtainTintBitmap(ImageView bg, int width, int height, int fingerprint) {
        TintBitmap cached = sTintBitmapCache.get(bg);
        if (cached != null && cached.width == width && cached.height == height
                && cached.fingerprint == fingerprint && !cached.bitmap.isRecycled()) {
            cached.bitmap.eraseColor(Color.TRANSPARENT);
            return cached.bitmap;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        sTintBitmapCache.put(bg, new TintBitmap(bitmap, width, height, fingerprint));
        return bitmap;
    }

    /** 保存目标输入法的完整 ImageView 状态，关闭效果时无损恢复。 */
    private static void rememberFrostedBackground(ImageView bg) {
        if (sSavedFrostedBackgrounds.containsKey(bg)) return;
        bg.setTag(R.id.tag_original_frosted_background, bg.getBackground());
        bg.setTag(R.id.tag_original_frosted_drawable, bg.getDrawable());
        bg.setTag(R.id.tag_original_frosted_alpha, bg.getImageAlpha());
        bg.setTag(R.id.tag_original_frosted_foreground, bg.getForeground());
        sOriginalFrostedScaleTypes.put(bg, bg.getScaleType());
        sSavedFrostedBackgrounds.put(bg, Boolean.TRUE);
    }

    private static void restoreFrostedBackground(ImageView bg) {
        if (!sSavedFrostedBackgrounds.containsKey(bg)) return;
        Object originalBackground = bg.getTag(R.id.tag_original_frosted_background);
        Object originalDrawable = bg.getTag(R.id.tag_original_frosted_drawable);
        Object originalAlpha = bg.getTag(R.id.tag_original_frosted_alpha);
        Object originalForeground = bg.getTag(R.id.tag_original_frosted_foreground);

        bg.setBackground(originalBackground instanceof Drawable ? (Drawable) originalBackground : null);
        bg.setImageDrawable(originalDrawable instanceof Drawable ? (Drawable) originalDrawable : null);
        bg.setImageAlpha(originalAlpha instanceof Integer ? (Integer) originalAlpha : 255);
        bg.setForeground(originalForeground instanceof Drawable ? (Drawable) originalForeground : null);
        ImageView.ScaleType originalScaleType = sOriginalFrostedScaleTypes.remove(bg);
        if (originalScaleType != null) bg.setScaleType(originalScaleType);
        bg.setTag(R.id.tag_original_frosted_background, null);
        bg.setTag(R.id.tag_original_frosted_drawable, null);
        bg.setTag(R.id.tag_original_frosted_alpha, null);
        bg.setTag(R.id.tag_original_frosted_foreground, null);
        bg.setTag(R.id.tag_frosted_tint, null);
        sSavedFrostedBackgrounds.remove(bg);
        sManagedFrostedBackgrounds.remove(bg);
        sManagedFrostedImages.remove(bg);
        sManagedFrostedForegrounds.remove(bg);
        sManagedFrostedScaleTypes.remove(bg);
    }

    /** 若目标输入法复用了 ImageView 但替换了 Drawable，更新关闭毛玻璃时的恢复基线。 */
    private static void refreshFrostedOriginalState(ImageView bg) {
        if (!sSavedFrostedBackgrounds.containsKey(bg)) return;

        Drawable managedBackground = getManagedDrawable(sManagedFrostedBackgrounds, bg);
        if (sManagedFrostedBackgrounds.containsKey(bg)
                && bg.getBackground() != managedBackground) {
            bg.setTag(R.id.tag_original_frosted_background, bg.getBackground());
        }
        Drawable managedImage = getManagedDrawable(sManagedFrostedImages, bg);
        if (sManagedFrostedImages.containsKey(bg)
                && bg.getDrawable() != managedImage) {
            bg.setTag(R.id.tag_original_frosted_drawable, bg.getDrawable());
            bg.setTag(R.id.tag_original_frosted_alpha, bg.getImageAlpha());
            // 目标已替换图片，旧 tint 不再是当前背景。
            bg.setTag(R.id.tag_frosted_tint, null);
        }
        Drawable managedForeground = getManagedDrawable(sManagedFrostedForegrounds, bg);
        if (sManagedFrostedForegrounds.containsKey(bg)
                && bg.getForeground() != managedForeground) {
            bg.setTag(R.id.tag_original_frosted_foreground, bg.getForeground());
        }
        if (sManagedFrostedScaleTypes.containsKey(bg)
                && bg.getScaleType() != sManagedFrostedScaleTypes.get(bg)) {
            sOriginalFrostedScaleTypes.put(bg, bg.getScaleType());
        }
    }

    private static Drawable getManagedDrawable(
            java.util.WeakHashMap<ImageView, java.lang.ref.WeakReference<Drawable>> map,
            ImageView bg) {
        java.lang.ref.WeakReference<Drawable> ref = map.get(bg);
        return ref != null ? ref.get() : null;
    }

    private static void rememberManagedFrostedState(ImageView bg, Drawable background) {
        sManagedFrostedBackgrounds.put(bg, new java.lang.ref.WeakReference<>(background));
        sManagedFrostedImages.put(bg,
                new java.lang.ref.WeakReference<>(bg.getDrawable()));
        sManagedFrostedForegrounds.put(bg,
                new java.lang.ref.WeakReference<>(bg.getForeground()));
        sManagedFrostedScaleTypes.put(bg, bg.getScaleType());
    }

    private static void clearFrostedEffect(ImageView bg) {
        restoreFrostedBackground(bg);
    }

    private static boolean fallback(ImageView bg, View inputView, boolean isDark, MainHook.Config c, int keyBgColor) {
        try {
            // 所有 fallback 分支都会改 ImageView 的至少一个属性，必须先保存。
            rememberFrostedBackground(bg);
            int alpha = c.alpha;
            int w = inputView.getWidth();
            int h = inputView.getHeight();
            if (w <= 0 || h <= 0) {
                DisplayMetrics dm = inputView.getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = (int) (dm.heightPixels * 0.4f);
            }

            // 直接用传入的 keyBgColor，不再反射
            int tintFp = alpha ^ (c.corner << 8) ^ (w << 12) ^ (h << 18)
                       ^ keyBgColor ^ (isDark ? 0x20000000 : 0);
            Bitmap out = obtainTintBitmap(bg, w, h, tintFp);
            Canvas cnv = new Canvas(out);

            int c1, c2;
            if (keyBgColor != 0) {
                int br = Color.red(keyBgColor), bg_ = Color.green(keyBgColor), bb = Color.blue(keyBgColor);
                c1 = Color.argb(alpha, br, bg_, bb);
                c2 = Color.argb(Math.min(255, alpha + 20),
                        Math.max(0, br - 15), Math.max(0, bg_ - 15), Math.max(0, bb - 15));
            } else if (isDark) {
                c1 = Color.argb(alpha, 30, 35, 50);
                c2 = Color.argb(alpha, 20, 25, 40);
            } else {
                c1 = Color.argb(alpha, 245, 248, 255);
                c2 = Color.argb(alpha, 225, 230, 245);
            }

            GradientDrawable base = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{c1, c2}
            );
            base.setBounds(0, 0, w, h);
            if (c.corner > 0) {
                float radius = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, c.corner,
                        inputView.getResources().getDisplayMetrics());
                Path clip = new Path();
                clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
                cnv.save();
                cnv.clipPath(clip);
                base.draw(cnv);
                cnv.restore();
            } else {
                base.draw(cnv);
            }

            Drawable oldImage = bg.getDrawable();
            boolean isOurTint = Boolean.TRUE.equals(bg.getTag(R.id.tag_frosted_tint));
            boolean hasCustomImage = !isOurTint
                    && (oldImage instanceof android.graphics.drawable.BitmapDrawable)
                    && ((android.graphics.drawable.BitmapDrawable) oldImage).getBitmap() != null;

            if (hasCustomImage) {
                bg.setImageAlpha(Math.max(10, 255 - alpha));
                bg.setForeground(new android.graphics.drawable.ColorDrawable(
                        Color.argb(Math.min(255, alpha / 2), 0, 0, 0)));
                bg.setTag(R.id.tag_frosted_tint, null);
            } else {
                bg.setImageBitmap(out);
                bg.setImageAlpha(255);
                bg.setBackground(null);
                bg.setForeground(null);
                bg.setTag(R.id.tag_frosted_tint, Boolean.TRUE);
            }
            rememberManagedFrostedState(bg, bg.getBackground());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "frosted fallback failed: " + e.getMessage());
        }
        return false;
    }

    // ══════════════════════════════════════════
    //  键盘圆角 — tint 位图填角 + keyboardView 裁剪
    // ══════════════════════════════════════════

    private static boolean applyRoundedCorners(View inputView, MainHook.Config c, MainHook.ThemeInfo ti) {
        try {
            if (c.corner <= 0) {
                clearRoundedCorners(inputView);
                return true;
            }

            float r = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, c.corner,
                    inputView.getResources().getDisplayMetrics());

            // 只有确认背景层和键盘层后才把 IME 窗口设为透明；否则会暴露矩形窗口底色。
            View kv = findKeyboardView(inputView);
            ImageView bgV = findCustomBackground(inputView);
            if (kv == null || bgV == null) {
                Log.w(TAG, "corners skipped: keyboard/background view not found");
                clearRoundedCorners(inputView);
                return false;
            }

            View decorView = inputView.getRootView();
            rememberBackground(decorView, R.id.tag_original_decor_background);
            rememberOutlineState(decorView);
            makeWindowTransparent(inputView);

            decorView.setBackgroundColor(Color.TRANSPARENT);
            decorView.setClipToOutline(false);
            decorView.setOutlineProvider(null);

            rememberBackground(inputView, R.id.tag_original_input_background);
            inputView.setBackgroundColor(Color.TRANSPARENT);

            applyOutline(kv, r);
            // 渐变描边（前景叠加）——暗色亮色描边
            addGradientBorder(kv, inputView, c, ti);

            // BlurDrawable 本身是矩形；必须裁剪它所属的 View，四角才是真正扣空。
            applyOutline(bgV, r);

            Log.i(TAG, "corners: r=" + c.corner + "dp (tint-fill + kv clip)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "corners failed: " + t);
        }
        return false;
    }

    private static void rememberBackground(View view, int tagId) {
        if (sSavedTransparentBackgrounds.containsKey(view)) return;
        view.setTag(tagId, view.getBackground());
        sSavedTransparentBackgrounds.put(view, Boolean.TRUE);
    }

    private static void restoreBackground(View view, int tagId) {
        if (!sSavedTransparentBackgrounds.containsKey(view)) return;
        Object original = view.getTag(tagId);
        view.setTag(tagId, null);
        view.setBackground(original instanceof Drawable ? (Drawable) original : null);
        sSavedTransparentBackgrounds.remove(view);
    }

    private static void rememberOutlineState(View view) {
        if (!sOriginalKeyboardClipStates.containsKey(view)) {
            sOriginalKeyboardClipStates.put(view, view.getClipToOutline());
            sOriginalKeyboardOutlineProviders.put(view, view.getOutlineProvider());
        }
    }

    private static void restoreOutlineState(View view) {
        if (sOriginalKeyboardClipStates.containsKey(view)) {
            Boolean originalClip = sOriginalKeyboardClipStates.remove(view);
            view.setClipToOutline(Boolean.TRUE.equals(originalClip));
        }
        if (sOriginalKeyboardOutlineProviders.containsKey(view)) {
            view.setOutlineProvider(sOriginalKeyboardOutlineProviders.remove(view));
        }
    }

    private static void clearRoundedCorners(View inputView) {
        try {
            View decor = inputView.getRootView();
            restoreWindowBackground(inputView);
            restoreBackground(decor, R.id.tag_original_decor_background);
            restoreBackground(inputView, R.id.tag_original_input_background);
            restoreOutlineState(decor);

            View keyboardView = findKeyboardView(inputView);
            if (keyboardView != null) {
                restoreOutlineState(keyboardView);
                restoreKeyboardForeground(keyboardView);
            }

            ImageView bg = findCustomBackground(inputView);
            if (bg != null) restoreOutlineState(bg);
            Log.i(TAG, "corners disabled");
        } catch (Throwable t) {
            Log.w(TAG, "clear corners failed: " + t);
        }
    }

    /** 多路径获取 IME 窗口并设背景透明。 */
    private static void makeWindowTransparent(View anyView) {
        Window window = findWindow(anyView);
        if (window != null) {
            View decor = window.getDecorView();
            if (!Boolean.TRUE.equals(decor.getTag(R.id.tag_window_background_saved))) {
                decor.setTag(R.id.tag_original_window_background, decor.getBackground());
                decor.setTag(R.id.tag_window_background_saved, Boolean.TRUE);
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            decor.setBackgroundColor(Color.TRANSPARENT);
            Log.i(TAG, "window transparent");
            return;
        }

        // 没有可访问 Window 时，最后才退化为根视图透明；保存 root/parent 以便恢复。
        View root = anyView.getRootView();
        rememberBackground(root, R.id.tag_original_window_background);
        root.setBackgroundColor(Color.TRANSPARENT);
        if (root.getParent() instanceof View) {
            View parent = (View) root.getParent();
            rememberBackground(parent, R.id.tag_original_window_background);
            parent.setBackgroundColor(Color.TRANSPARENT);
        }
        Log.i(TAG, "root transparent fallback");
    }

    private static void restoreWindowBackground(View anyView) {
        Window window = findWindow(anyView);
        if (window == null) {
            // 反射找不到 Window 时，makeWindowTransparent() 会改 root/parent；两者也必须恢复。
            View root = anyView.getRootView();
            restoreBackground(root, R.id.tag_original_window_background);
            if (root.getParent() instanceof View) {
                restoreBackground((View) root.getParent(), R.id.tag_original_window_background);
            }
            return;
        }
        View decor = window.getDecorView();
        if (!Boolean.TRUE.equals(decor.getTag(R.id.tag_window_background_saved))) return;
        Object original = decor.getTag(R.id.tag_original_window_background);
        // 原背景允许为 null；必须显式恢复，否则透明 WindowDrawable 会永久保留。
        window.setBackgroundDrawable(original instanceof Drawable ? (Drawable) original : null);
        decor.setTag(R.id.tag_original_window_background, null);
        decor.setTag(R.id.tag_window_background_saved, null);
    }

    private static Window findWindow(View anyView) {
        try {
            android.content.Context ctx = anyView.getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                try {
                    Method method = ctx.getClass().getMethod("getWindow");
                    Object result = method.invoke(ctx);
                    Window window = unwrapWindow(result);
                    if (window != null) return window;
                } catch (NoSuchMethodException ignored) {}
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
        } catch (Exception ignored) {}

        try {
            Object ctx = anyView.getContext();
            for (Class<?> cls = ctx.getClass(); cls != null; cls = cls.getSuperclass()) {
                try {
                    Method method = cls.getDeclaredMethod("getWindow");
                    method.setAccessible(true);
                    Window window = unwrapWindow(method.invoke(ctx));
                    if (window != null) return window;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}

        try {
            Object viewRoot = anyView.getRootView().getParent();
            if (viewRoot != null) {
                Field field = viewRoot.getClass().getDeclaredField("mWindow");
                field.setAccessible(true);
                Object result = field.get(viewRoot);
                if (result instanceof Window) return (Window) result;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Window unwrapWindow(Object value) {
        if (value instanceof Window) return (Window) value;
        if (value == null) return null;
        try {
            Object window = value.getClass().getMethod("getWindow").invoke(value);
            return window instanceof Window ? (Window) window : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void applyOutline(View v, float radius) {
        rememberOutlineState(v);
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                if (w <= 0 || h <= 0) return;
                // setRoundRect 是硬件 clipToOutline 明确支持的形状；
                // setConvexPath 在部分 Android 12+ 设备上只影响阴影、不裁剪 BlurDrawable。
                outline.setRoundRect(0, 0, w, h, radius);
            }
        });
    }

    /** 键盘渐变描边 — View.setForeground + GlassBorderDrawable */
    private static void addGradientBorder(View keyboardView, View inputView, MainHook.Config c, MainHook.ThemeInfo ti) {
        try {
            boolean isDark = ti.isDark;

            int borderTop, borderBottom;
            float den = inputView.getResources().getDisplayMetrics().density;
            float borderWidthPx;
            if (isDark) {
                // 暗色：白 0.20→TRANSPARENT→0.20，描边 1dp（进一步淡化）
                borderTop = 0x33FFFFFF;
                borderBottom = 0x33FFFFFF;
                borderWidthPx = 1f * den;
            } else {
                // 亮色：白 0.6→TRANSPARENT→0.6，描边 1dp
                borderTop = 0x99FFFFFF;
                borderBottom = 0x99FFFFFF;
                borderWidthPx = 1f * den;
            }
            float radius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, c.corner,
                    inputView.getResources().getDisplayMetrics());

            GlassBorderDrawable gb = new GlassBorderDrawable(
                    0, borderTop, borderBottom, radius, borderWidthPx,
                    GlassBorderDrawable.MODE_KEYBOARD);

            if (!sOriginalKeyboardForegrounds.containsKey(keyboardView)) {
                    Drawable original = keyboardView.getForeground();
                    if (original instanceof GlassBorderDrawable) {
                        original = null;
                    } else if (original instanceof android.graphics.drawable.LayerDrawable) {
                        android.graphics.drawable.LayerDrawable old =
                                (android.graphics.drawable.LayerDrawable) original;
                        if (old.getNumberOfLayers() == 2
                                && old.getDrawable(0) instanceof GlassBorderDrawable) {
                            original = old.getDrawable(1);
                        }
                    }
                    sOriginalKeyboardForegrounds.put(
                            keyboardView, new java.lang.ref.WeakReference<>(original));
                }
                java.lang.ref.WeakReference<Drawable> originalRef =
                        sOriginalKeyboardForegrounds.get(keyboardView);
                Drawable original = originalRef != null ? originalRef.get() : null;
                if (original != null) {
                    keyboardView.setForeground(new android.graphics.drawable.LayerDrawable(
                            new Drawable[]{gb, original}));
                } else {
                    keyboardView.setForeground(gb);
                }
            Log.i(TAG, "keyboard gradient border: dark=" + isDark);
        } catch (Throwable t) {
            Log.w(TAG, "gradient border failed: " + t);
        }
    }

    private static void restoreKeyboardForeground(View keyboardView) {
        if (!sOriginalKeyboardForegrounds.containsKey(keyboardView)) {
            Drawable foreground = keyboardView.getForeground();
            if (foreground instanceof GlassBorderDrawable) {
                keyboardView.setForeground(null);
            } else if (foreground instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable layer =
                        (android.graphics.drawable.LayerDrawable) foreground;
                if (layer.getNumberOfLayers() == 2
                        && layer.getDrawable(0) instanceof GlassBorderDrawable) {
                    keyboardView.setForeground(layer.getDrawable(1));
                }
            }
            return;
        }
        java.lang.ref.WeakReference<Drawable> originalRef =
                sOriginalKeyboardForegrounds.remove(keyboardView);
        keyboardView.setForeground(originalRef != null ? originalRef.get() : null);
    }

    // ══════════════════════════════════════════
    //  字段查找 — R8 混淆后字段名不可靠，按类型匹配
    // ══════════════════════════════════════════

    /** 在 InputView 中查找 customBackground ImageView（结果缓存，避免每次遍历类层级） */
    private static ImageView findCustomBackground(View inputView) {
        // 优先使用缓存。WeakReference 避免 value 通过 parent 反向持有 InputView。
        java.lang.ref.WeakReference<ImageView> cachedRef = sCustomBgCache.get(inputView);
        ImageView cached = cachedRef != null ? cachedRef.get() : null;
        View cachedKeyboard = findKeyboardView(inputView);
        if (cached != null && isDescendantOf(cached, inputView)
                && isValidCustomBackground(cached, cachedKeyboard)) return cached;
        if (cachedRef != null) sCustomBgCache.remove(inputView);

        // 方式1: 字段名匹配（靓企鹅未混淆时可用）
        for (Class<?> c = inputView.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == ImageView.class) {
                    try {
                        f.setAccessible(true);
                        ImageView iv = (ImageView) f.get(inputView);
                        if (iv != null && isValidCustomBackground(iv, findKeyboardView(inputView))) {
                            Log.i(TAG, "found customBackground by field: " + f.getName());
                            sCustomBgCache.put(inputView, new java.lang.ref.WeakReference<>(iv));
                            return iv;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        // 方式2: 在 keyboardView 的子 View 中找 CENTER_CROP 的 ImageView
        // customBackground 是 keyboardView(ConstraintLayout) 的第一个子 View
        View kbView = findKeyboardView(inputView);
        if (kbView instanceof ViewGroup) {
            ViewGroup kb = (ViewGroup) kbView;
            for (int i = 0; i < kb.getChildCount(); i++) {
                View child = kb.getChildAt(i);
                if (child instanceof ImageView) {
                    ImageView iv = (ImageView) child;
                    if (!isValidCustomBackground(iv, kbView)) continue;
                    Log.i(TAG, "found customBackground in keyboardView[" + i + "]");
                    sCustomBgCache.put(inputView, new java.lang.ref.WeakReference<>(iv));
                    return iv;
                }
            }
        }
        // 不递归猜测任意 ImageView：误把图标当背景会使真正的 BlurDrawable 未裁剪。
        return null;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        return false;
    }

    private static View findKeyboardView(View inputView) {
        try {
            java.lang.reflect.Method m = inputView.getClass().getMethod("getKeyboardView");
            return (View) m.invoke(inputView);
        } catch (Exception ignored) {}
        // fallback: 遍历子 View 找非 ImageView 的 ViewGroup（keyboardView 是 ConstraintLayout）
        if (inputView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) inputView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof ViewGroup && !(child instanceof ImageView)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** customBackground 必须是铺满键盘的 ImageView，不能是功能图标。 */
    private static boolean isValidCustomBackground(ImageView image, View keyboardView) {
        // 应用过毛玻璃后会主动切换为 FIT_XY；通过模块 tag/保存表识别自己的背景，
        // 不能再强制要求 CENTER_CROP，否则后续配置更新会找不到同一个 ImageView。
        boolean managedByHelper = Boolean.TRUE.equals(
                image.getTag(R.id.tag_frosted_tint))
                || sSavedFrostedBackgrounds.containsKey(image);
        if (managedByHelper) return true;
        if (image.getScaleType() != ImageView.ScaleType.CENTER_CROP || keyboardView == null) {
            return false;
        }
        int keyboardWidth = keyboardView.getWidth();
        int keyboardHeight = keyboardView.getHeight();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (keyboardWidth > 0 && keyboardHeight > 0 && imageWidth > 0 && imageHeight > 0) {
            return imageWidth >= keyboardWidth * 0.8f && imageHeight >= keyboardHeight * 0.8f;
        }
        ViewGroup.LayoutParams lp = image.getLayoutParams();
        return lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                && lp.height == ViewGroup.LayoutParams.MATCH_PARENT;
    }
}
