package com.projectorguy.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Standalone launcher shortcut — shows up as its own icon on the home
 * screen (see the LAUNCHER <activity> entry in the manifest) so picture
 * presets are reachable without digging through the main app's WebView UI.
 *
 * This replaced an earlier attempt at a double-MENU-press system overlay
 * triggered via the ACTION_GLOBAL_BUTTON broadcast: that broadcast never
 * reached this app at all when backgrounded, even from a persistent
 * foreground service with a dynamically-registered, high-priority
 * receiver — confirmed dead end via extensive logcat testing. A plain
 * launcher icon has no such restrictions to fight.
 *
 * BACK closes it via the Activity's own default behavior — no custom
 * handling needed.
 */
public class PicturePresetActivity extends Activity {
    private Button cinemaBtn, brightBtn, gamingBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#101018"));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(roundedFill("#1C1C27", dp(20)));

        TextView title = new TextView(this);
        title.setText("Picture Preset");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(18));
        card.addView(title);

        cinemaBtn = buildButton("Cinema", "cinema");
        brightBtn = buildButton("Bright Room", "bright");
        gamingBtn = buildButton("Gaming", "gaming");
        card.addView(cinemaBtn);
        card.addView(brightBtn);
        card.addView(gamingBtn);

        root.addView(card, new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        // requestFocus() called immediately after setContentView() can
        // silently fail — the view hasn't been through a layout pass yet.
        // Posting it defers until after that, which reliably works.
        root.post(cinemaBtn::requestFocus);
        refreshHighlight();
    }

    private Button buildButton(String label, String preset) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        int padH = dp(24), padV = dp(14);
        b.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        // Apply live as focus moves (D-pad up/down), like a real TV picture
        // menu — no need to press OK to preview it. OK on an already-focused
        // button (or a mouse-mode click) just re-applies the same thing,
        // which is harmless.
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;
            boolean ok = PictureModeBridge.applyPreset(this, preset);
            if (!ok) Toast.makeText(this, "Could not apply " + label, Toast.LENGTH_SHORT).show();
            refreshHighlight();
        });
        b.setOnClickListener(v -> {
            boolean ok = PictureModeBridge.applyPreset(this, preset);
            if (!ok) Toast.makeText(this, "Could not apply " + label, Toast.LENGTH_SHORT).show();
            refreshHighlight();
        });
        return b;
    }

    /** Re-checks the active mode and updates each button's highlight. */
    private void refreshHighlight() {
        int currentMode = PictureModeBridge.getCurrentMode(this);
        setButtonActive(cinemaBtn, "Cinema", currentMode == PictureModeBridge.MODE_CINEMA);
        setButtonActive(brightBtn, "Bright Room", currentMode == PictureModeBridge.MODE_BRIGHT);
        setButtonActive(gamingBtn, "Gaming", currentMode == PictureModeBridge.MODE_GAMING);
    }

    private void setButtonActive(Button b, String label, boolean active) {
        b.setBackground(roundedFill(active ? "#3B6FF5" : "#26263A", dp(10)));
        b.setText(active ? label + "  ✓" : label);
    }

    private GradientDrawable roundedFill(String color, int cornerRadius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(cornerRadius);
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
