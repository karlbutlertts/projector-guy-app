package com.projectorguy.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class PictureMenuShortcutService extends AccessibilityService {
    private static final Intent PANEL = new Intent()
            .setClassName("com.sstar.customizedmenu", "com.sstar.customizedmenu.MainActivity");

    static boolean isSupported(Context context) {
        if (!"pioneer5_sdp_lr".equals(Build.DEVICE)) return false;
        ResolveInfo target = context.getPackageManager().resolveActivity(PANEL, 0);
        return target != null && target.activityInfo.exported;
    }

    static Intent panelIntent() {
        return new Intent(PANEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_MENU || !isSupported(this)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(panelIntent());
                Log.i("PictureMenuShortcut", "Opened native picture menu");
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w("PictureMenuShortcut", "Native picture menu unavailable", e);
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
