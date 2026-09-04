package com.projectorguy.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    /**
     * The app loads the live Projector Guy website. The AndroidBridge below is
     * attached to the WebView, so it is available to every page loaded here —
     * including the firmware helper page at <START_URL>/fix.html.
     *
     * To run fully offline instead, change this to:
     *   "file:///android_asset/www/fix.html"
     */
    private static final String START_URL = "https://karlbutlertts.github.io/xbj-apk-store/";

    private static final String TAG = "MainActivity";

    private static final int REQ_STORAGE = 101;

    // Same manifest the store website reads, so both stay in sync on what
    // "latest" means. Matched by packageName, not display name.
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/karlbutlertts/xbj-apk-store/main/apps.json";
    private static final String APK_BASE_URL =
            "https://raw.githubusercontent.com/karlbutlertts/xbj-apk-store/main/apks/";

    private WebView webView;
    private View updateOverlay;
    private TextView updateStatusText;
    private View installHelpOverlay;
    private boolean updateCheckStarted = false;

    // Set when installApk() has to send the user to the "install unknown
    // apps" settings screen. onResume() checks this so the install resumes
    // automatically as soon as they come back, instead of leaving them to
    // figure out they need to relaunch the app and redo the whole flow.
    private File pendingUpdateApk = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableImmersiveMode();
        setContentView(R.layout.activity_main);

        requestStoragePermissions();

        webView = findViewById(R.id.webView);
        updateOverlay = findViewById(R.id.updateOverlay);
        updateStatusText = findViewById(R.id.updateStatusText);
        installHelpOverlay = findViewById(R.id.installHelpOverlay);
        findViewById(R.id.installHelpContinueBtn).setOnClickListener(v -> goToUnknownSourcesSettings());

        // Make sure the WebView can receive D-pad/remote focus and key events
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The store page changes often and is meant to always be fresh — the
        // default heuristic HTTP cache was serving a build that was one
        // commit behind, so every launch now bypasses the cache entirely.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " ProjectorGuyApp/1.0");

        // Expose native bridge to JavaScript as window.AndroidBridge
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Let the browser-layer download links work too (non-native fallback)
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        // Check for updates unconditionally on every cold launch, rather than
        // waiting for the web page to call AndroidBridge.onUnlocked(). That
        // JS-triggered path only fires on a fresh code entry or a returning
        // session — so a device already sitting inside its 30-day session,
        // running a build from before that returning-session call existed,
        // would never run the check at all and could never update itself.
        // Doing it here instead means every future update reaches every
        // device the moment it's opened, independent of login state or
        // whatever the web page happens to do.
        onUserUnlocked();
    }

    /**
     * Hide the status bar and navigation bar so the WebView fills the whole TV
     * screen with no Android chrome. Re-applied whenever the window regains
     * focus, since the system bars can reappear after dialogs/permission prompts.
     */
    private void enableImmersiveMode() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveMode();
    }

    /**
     * Let D-pad left/right/up/down on the remote scroll the page when nothing
     * inside the WebView itself consumes the key (e.g. plain content areas).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (webView != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    webView.scrollBy(0, 120);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    webView.scrollBy(0, -120);
                    break;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageStorage();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"
            }, REQ_STORAGE);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void requestManageStorage() {
        if (Environment.isExternalStorageManager()) return;

        Intent perApp = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        perApp.setData(Uri.parse("package:" + getPackageName()));
        if (startActivitySafely(perApp)) return;

        // Some custom/TV firmware (e.g. the projector's stripped-down Settings
        // app) doesn't implement the per-app screen above. Fall back to the
        // generic "all files access" list — and if even that isn't present,
        // just log it and carry on instead of crashing the whole app on launch.
        if (!startActivitySafely(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) {
            Log.w(TAG, "No Settings screen available to grant MANAGE_EXTERNAL_STORAGE; "
                    + "USB access may be limited on this firmware.");
        }
    }

    /**
     * Starts an activity, swallowing ActivityNotFoundException (and any other
     * failure) so a missing Settings screen on custom firmware never crashes
     * the app at launch. Returns true on success.
     */
    private boolean startActivitySafely(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed for " + intent.getAction() + ": " + e.getMessage());
            return false;
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  SELF-UPDATE CHECK
    //
    //  Triggered by AndroidBridge.onUnlocked() once the web page's own
    //  6-digit-code lock screen grants access. Shows updateOverlay on top of
    //  the (already-loaded) WebView while it checks apps.json for a newer
    //  versionCode, then either offers to download+install the update or
    //  dismisses the overlay after a short delay to reveal the app.
    // ───────────────────────────────────────────────────────────────────────

    public void onUserUnlocked() {
        if (updateCheckStarted) return;
        updateCheckStarted = true;
        updateStatusText.setText("Checking for updates…");
        updateOverlay.setVisibility(View.VISIBLE);
        new Thread(this::checkForUpdate, "update-check").start();
    }

    /**
     * Called from the "Check for Updates" hub tile. Unlike onUserUnlocked(),
     * this deliberately ignores updateCheckStarted — that flag only exists to
     * stop the automatic launch-time check from firing twice, not to block a
     * user who explicitly asks to check again.
     */
    public void runManualUpdateCheck() {
        if (updateOverlay.getVisibility() == View.VISIBLE) return; // already busy
        updateStatusText.setText("Checking for updates…");
        updateOverlay.setVisibility(View.VISIBLE);
        new Thread(this::checkForUpdate, "manual-update-check").start();
    }

    private void checkForUpdate() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            String body = readAll(conn.getInputStream());
            conn.disconnect();

            JSONArray apps = new JSONArray(body);
            JSONObject match = null;
            for (int i = 0; i < apps.length(); i++) {
                JSONObject o = apps.getJSONObject(i);
                if (getPackageName().equals(o.optString("packageName", null))) {
                    match = o;
                    break;
                }
            }

            int remoteVersionCode = match != null ? match.optInt("versionCode", -1) : -1;
            String apkFile = match != null ? match.optString("file", null) : null;

            if (remoteVersionCode <= 0 || apkFile == null) {
                runOnUiThread(this::finishNoUpdate);
                return;
            }

            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long localVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;

            if (remoteVersionCode > localVersionCode) {
                String apkUrl = APK_BASE_URL + apkFile;
                runOnUiThread(() -> promptForUpdate(apkUrl));
            } else {
                runOnUiThread(this::finishNoUpdate);
            }
        } catch (Exception e) {
            Log.w(TAG, "Update check failed: " + e.getMessage());
            runOnUiThread(this::finishNoUpdate);
        }
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    /** No update found (or the check failed) — reveal the app after a short delay. */
    private void finishNoUpdate() {
        updateStatusText.setText("You're up to date");
        updateOverlay.postDelayed(this::hideUpdateOverlay, 1200);
    }

    private void hideUpdateOverlay() {
        updateOverlay.setVisibility(View.GONE);
    }

    private void promptForUpdate(String apkUrl) {
        updateStatusText.setText("Update available");
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A new version of this app is available. Download and install it now?")
                .setCancelable(false)
                .setPositiveButton("Download & Install", (d, w) -> startUpdateDownload(apkUrl))
                .setNegativeButton("Not now", (d, w) -> hideUpdateOverlay())
                .show();
    }

    private void startUpdateDownload(String apkUrl) {
        updateStatusText.setText("Downloading update…");
        new Thread(() -> downloadAndInstall(apkUrl, "update.apk", "update"), "update-download").start();
    }

    // ───────────────────────────────────────────────────────────────────────
    //  IN-APP STORE DOWNLOADS
    //
    //  Called from AndroidBridge.downloadApp() when a user taps an app tile
    //  on the store page. Reuses the same download → FileProvider → installer
    //  pipeline (and the same permission-resume handling) as the self-update
    //  check above, so tapping Download never bounces the user out to the
    //  system browser/Downloads app and back — it all happens in an overlay
    //  on top of the WebView, and lands on the system install prompt directly.
    // ───────────────────────────────────────────────────────────────────────

    public void startAppDownload(String url, String displayName) {
        if (updateOverlay.getVisibility() == View.VISIBLE) return; // something's already downloading
        String rawSafeName = displayName.replaceAll("[^a-zA-Z0-9.]+", "_");
        final String safeName = rawSafeName.toLowerCase().endsWith(".apk") ? rawSafeName : rawSafeName + ".apk";
        updateStatusText.setText("Downloading " + displayName + "…");
        updateOverlay.setVisibility(View.VISIBLE);
        new Thread(() -> downloadAndInstall(url, safeName, displayName), "app-download").start();
    }

    private void downloadAndInstall(String apkUrl, String cacheFilename, String label) {
        File dir = new File(getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, cacheFilename);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apkUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            long total = conn.getContentLengthLong();

            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long received = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                received += n;
                if (total > 0) {
                    int pct = (int) (received * 100 / total);
                    runOnUiThread(() -> updateStatusText.setText("Downloading " + label + "… " + pct + "%"));
                }
            }
            fos.flush();
            fos.close();
            in.close();

            runOnUiThread(() -> installApk(out));
        } catch (Exception e) {
            Log.w(TAG, "Download failed: " + e.getMessage());
            if (out.exists()) out.delete();
            runOnUiThread(() -> {
                updateStatusText.setText("Download failed");
                updateOverlay.postDelayed(this::hideUpdateOverlay, 1500);
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void installApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateApk = apkFile;
            // Explain what's about to happen in our own branding before
            // handing off to Android's own settings screen — that one lists
            // every app on the device with install-unknown-apps access and
            // gives no context, which was confusing buyers. installHelpOverlay's
            // Continue button is what actually fires the Settings intent.
            updateOverlay.setVisibility(View.GONE);
            installHelpOverlay.setVisibility(View.VISIBLE);
            return;
        }
        launchInstaller(apkFile);
    }

    private void goToUnknownSourcesSettings() {
        installHelpOverlay.setVisibility(View.GONE);
        updateOverlay.setVisibility(View.VISIBLE);
        updateStatusText.setText("Opening settings…");
        startActivitySafely(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        // Leave the overlay up — onResume() picks this back up as soon as
        // they return from Settings, so it doesn't just quietly time out.
    }

    private void launchInstaller(File apkFile) {
        pendingUpdateApk = null;
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (!startActivitySafely(installIntent)) {
            updateStatusText.setText("Couldn't open the installer on this device.");
            updateOverlay.postDelayed(this::hideUpdateOverlay, 2000);
            return;
        }
        hideUpdateOverlay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingUpdateApk != null) {
            File apk = pendingUpdateApk;
            pendingUpdateApk = null;
            // Deliberately NOT re-checking canRequestPackageInstalls() here.
            // On this firmware it can still report the old "denied" result
            // for a moment right after returning from Settings, even though
            // the toggle already shows as on — which was forcing users to
            // flip it off/on again just to get our cached read to catch up.
            // The system installer does its own authoritative permission
            // check regardless, so just hand off to it directly and let it
            // be the judge (it shows its own blocked-install prompt if the
            // permission genuinely isn't granted).
            launchInstaller(apk);
        }
    }

    @Override
    public void onBackPressed() {
        if (installHelpOverlay != null && installHelpOverlay.getVisibility() == View.VISIBLE) {
            // Cancel the install rather than closing the whole app — this is
            // exactly the screen where someone's likely to reach for Back.
            installHelpOverlay.setVisibility(View.GONE);
            pendingUpdateApk = null;
            hideUpdateOverlay();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }
}
