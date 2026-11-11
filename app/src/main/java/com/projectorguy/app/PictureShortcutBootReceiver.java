package com.projectorguy.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

public final class PictureShortcutBootReceiver extends BroadcastReceiver {
    private static final String TAG = "PictureShortcutBoot";
    private static final String PREFS = "picture_shortcut";
    private static final String RESTORE_ON_BOOT = "restore_on_boot";

    static boolean canRestore(Context context) {
        return PictureMenuShortcutService.isSupported(context)
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOptedIn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(RESTORE_ON_BOOT, false);
    }

    static void setOptedIn(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(RESTORE_ON_BOOT, enabled).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !isOptedIn(context) || !canRestore(context)) {
            return;
        }

        PendingResult pending = goAsync();
        // Give NewLink time to finish writing its startup accessibility settings.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (isOptedIn(context) && canRestore(context)) {
                    restoreShortcut(context);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Could not restore Picture Shortcut", e);
            } finally {
                pending.finish();
            }
        }, 5_000);
    }

    private static void restoreShortcut(Context context) {
        ComponentName shortcut = new ComponentName(context, PictureMenuShortcutService.class);
        String setting = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String enabled = Settings.Secure.getString(context.getContentResolver(), setting);
        for (String entry : TextUtils.split(enabled == null ? "" : enabled, ":")) {
            if (shortcut.equals(ComponentName.unflattenFromString(entry))) {
                return;
            }
        }

        String updated = TextUtils.isEmpty(enabled)
                ? shortcut.flattenToString() : enabled + ":" + shortcut.flattenToString();
        if (Settings.Secure.putString(context.getContentResolver(), setting, updated)) {
            Log.i(TAG, "Restored Picture Shortcut after reboot");
        } else {
            Log.w(TAG, "Android rejected the Picture Shortcut setting");
        }
    }
}
