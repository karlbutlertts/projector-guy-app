package com.projectorguy.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class TouchOverlayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ((!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
                || !Build.DEVICE.startsWith("pioneer5") || !TouchOverlayControl.enabled(context)) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try (LocalAdb adb = new LocalAdb(context)) {
                TouchOverlayControl.set(context, adb, true);
            } catch (Exception failure) {
                Log.w("TouchOverlay", "Could not restore auto-hide after boot", failure);
            } finally { result.finish(); }
        }, "TouchOverlayBoot").start();
    }
}
