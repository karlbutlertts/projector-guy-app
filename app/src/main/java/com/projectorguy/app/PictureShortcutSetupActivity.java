package com.projectorguy.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class PictureShortcutSetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_picture_shortcut_setup);
        Button next = findViewById(R.id.pictureSetupContinue);
        next.setOnClickListener(v -> openTvSettings());
        findViewById(R.id.pictureSetupOpenPicture).setOnClickListener(v -> {
            try {
                startActivity(PictureMenuShortcutService.panelIntent());
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.picture_open_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        Switch restore = findViewById(R.id.pictureSetupRestoreOnBoot);
        restore.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                view.performClick();
            }
            return true;
        });
        next.post(next::requestFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isShortcutEnabled(this);
        ((TextView) findViewById(R.id.pictureSetupHeading)).setText(
                enabled ? R.string.picture_setup_ready : R.string.picture_setup_heading);
        ((TextView) findViewById(R.id.pictureSetupInstructions)).setText(
                enabled ? R.string.picture_setup_usage : R.string.picture_setup_instructions);
        ((TextView) findViewById(R.id.pictureSetupNote)).setText(
                enabled ? R.string.picture_setup_turn_off : R.string.picture_setup_note);
        ((Button) findViewById(R.id.pictureSetupContinue)).setText(
                enabled ? R.string.picture_setup_settings : R.string.picture_setup_continue);
        findViewById(R.id.pictureSetupBackHint).setVisibility(enabled ? View.GONE : View.VISIBLE);

        Switch restore = findViewById(R.id.pictureSetupRestoreOnBoot);
        boolean restoring = PictureShortcutBootReceiver.isOptedIn(this);
        restore.setVisibility(PictureShortcutBootReceiver.canRestore(this) && (enabled || restoring)
                ? View.VISIBLE : View.GONE);
        restore.setOnCheckedChangeListener(null);
        restore.setChecked(restoring);
        restore.setOnCheckedChangeListener((button, checked) ->
                PictureShortcutBootReceiver.setOptedIn(this, checked));
        if (enabled && restore.getVisibility() == View.VISIBLE) {
            ((TextView) findViewById(R.id.pictureSetupNote)).setText(R.string.picture_setup_boot_note);
        }
    }

    private static boolean isShortcutEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        ComponentName shortcut = new ComponentName(context, PictureMenuShortcutService.class);
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (shortcut.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    private void openTvSettings() {
        Intent settings = new Intent().setClassName(
                "com.android.tv.settings", "com.android.tv.settings.MainSettings");
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.picture_setup_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
