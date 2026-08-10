package com.projectorguy.app;

import android.content.Context;
import android.util.Log;

/**
 * Shared helper for controlling picture mode via the vendor's
 * com.newlink.android.projector shared library (NLProjector.jar on the
 * device's framework classpath, declared via <uses-library> in the
 * manifest). Called via reflection rather than compiling against the
 * library directly: NLProjector.jar is a dex-format Android jar, not
 * standard .class bytecode, so javac can't resolve symbols from it even as
 * a compileOnly dependency — the real classes exist at runtime via
 * <uses-library>, which is all reflection needs.
 *
 * This calls the exact same API the vendor's own picture-mode screen calls
 * internally when you tap an item — no UI opens, no key injection needed.
 * (An earlier version drove the vendor's PictureModeActivity via injected
 * key events instead; that never worked, since input keyevent requires
 * INJECT_EVENTS, which this app's regular UID doesn't have — confirmed via
 * logcat, every injected key exited 255.)
 *
 * Used by both AndroidBridge's JS-facing applyPicturePreset() and the
 * native double-menu-press overlay (PictureOverlayService).
 */
final class PictureModeBridge {
    private static final String TAG = "PictureModeBridge";

    // Real mode names/values (from decompiling com.zhiying.settings):
    // 0=Standard, 1=Film("Movie"), 2=Dynamic, 3=Soft, 4=Game, 5=Customized.
    static final int MODE_CINEMA = 1; // Film ("Movie")
    static final int MODE_BRIGHT = 2; // Dynamic
    static final int MODE_GAMING = 4; // Game

    private PictureModeBridge() {}

    static int presetToMode(String preset) {
        switch (preset) {
            case "cinema": return MODE_CINEMA;
            case "bright": return MODE_BRIGHT;
            case "gaming": return MODE_GAMING;
            default: return -1;
        }
    }

    static boolean applyPreset(Context context, String preset) {
        int mode = presetToMode(preset);
        if (mode < 0) {
            Log.w(TAG, "applyPreset: unknown preset '" + preset + "'");
            return false;
        }
        try {
            Object factoryManager = getFactoryManager(context);
            if (factoryManager == null) return false;
            factoryManager.getClass().getMethod("setPictureMode", int.class).invoke(factoryManager, mode);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "applyPreset(" + preset + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Returns the current raw mode value, or -1 if unavailable. */
    static int getCurrentMode(Context context) {
        try {
            Object factoryManager = getFactoryManager(context);
            if (factoryManager == null) return -1;
            return (int) factoryManager.getClass().getMethod("getPictureMode").invoke(factoryManager);
        } catch (Exception e) {
            Log.e(TAG, "getCurrentMode failed: " + e.getMessage());
            return -1;
        }
    }

    private static Object getFactoryManager(Context context) throws Exception {
        Class<?> managerClass = Class.forName("com.newlink.android.projector.ProjectorManager");
        Object manager = managerClass.getMethod("getInstance", Context.class)
                .invoke(null, context.getApplicationContext());

        Class<?> factoryManagerClass = Class.forName("com.newlink.android.projector.FactoryManager");
        Object factoryManager = managerClass.getMethod("getService", Class.class).invoke(manager, factoryManagerClass);
        if (factoryManager == null) {
            Log.e(TAG, "FactoryManager unavailable");
        }
        return factoryManager;
    }
}
