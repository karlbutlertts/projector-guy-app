package com.projectorguy.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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
    private View installHelpContinueBtn;
    private View installTipOverlay;
    private View installTipContinueBtn;
    private boolean updateCheckStarted = false;

    // Set when installApk() has to send the user to the "install unknown
    // apps" settings screen. onResume() checks this so the install resumes
    // automatically as soon as they come back, instead of leaving them to
    // figure out they need to relaunch the app and redo the whole flow.
    private File pendingUpdateApk = null;

    // Same idea as pendingUpdateApk, but for a split-APK bundle (see
    // installSplitApks()) — kept as a separate field since the two flows
    // resume into different installers (single ACTION_VIEW vs a
    // PackageInstaller session) once the user comes back from Settings.
    private List<File> pendingUpdateSplitApks = null;

    // Set whenever installTipOverlay is showing, so its Continue button (or
    // onResume(), coming back from the unknown-sources settings screen)
    // knows what to actually launch the installer on. A single-file install
    // is just a one-element list.
    private List<File> pendingInstallApks = null;

    // Set right before handing off to the system installer in
    // launchInstaller(), so onResume() knows we're coming back from that —
    // as opposed to a fresh cold launch, where onResume() fires too but
    // must NOT touch updateOverlay (it's legitimately showing "Checking
    // for updates…" at that point). Acts as a safety net in case
    // hideUpdateOverlay() didn't fully take effect before the installer's
    // activity took over the screen, which could otherwise leave our own
    // popup visible when the user is returned to the app.
    private boolean awaitingInstallerReturn = false;

    // Set right before session.commit() in launchMultiInstaller(), so
    // onNewIntent() knows which files to offer for retry if the commit
    // reports back a genuine failure.
    private List<File> currentMultiInstallApks = null;

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
        installHelpContinueBtn = findViewById(R.id.installHelpContinueBtn);
        installHelpContinueBtn.setOnClickListener(v -> goToUnknownSourcesSettings());
        installTipOverlay = findViewById(R.id.installTipOverlay);
        installTipContinueBtn = findViewById(R.id.installTipContinueBtn);
        installTipContinueBtn.setOnClickListener(v -> {
            // Disabled immediately so a double-tap can't fire this twice —
            // re-enabled in showInstallTip() next time this screen is shown.
            installTipContinueBtn.setEnabled(false);
            List<File> apks = pendingInstallApks;
            pendingInstallApks = null;
            installTipOverlay.setVisibility(View.GONE);
            if (apks == null || apks.isEmpty()) return;
            if (apks.size() == 1) {
                launchInstaller(apks.get(0));
            } else {
                launchMultiInstaller(apks);
            }
        });

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

    /**
     * True while any of the download/install overlays are up — from the
     * moment a download starts through to the tip screen and the actual
     * install. Guards startAppDownload()/startSplitAppDownload() against a
     * duplicate call: once the flow moves on from updateOverlay to
     * installTipOverlay, updateOverlay alone reports GONE, so a stray tap
     * that reaches the WebView underneath (still showing the same app tile
     * behind the overlay) could otherwise kick off a second, overlapping
     * download of the same app — which is exactly what a split-APK install
     * looked like it was doing after tapping Install.
     */
    private boolean installFlowBusy() {
        return updateOverlay.getVisibility() == View.VISIBLE
                || installHelpOverlay.getVisibility() == View.VISIBLE
                || installTipOverlay.getVisibility() == View.VISIBLE;
    }

    public void startAppDownload(String url, String displayName) {
        if (installFlowBusy()) return; // something's already downloading/installing
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
            // The WebView held D-pad focus from launch (see onCreate) and
            // nothing ever moved it off, so the remote's OK button kept
            // going to the hidden WebView underneath and Continue couldn't
            // be selected at all. Posting the focus request defers it past
            // the layout pass this now-visible view still needs to run —
            // requesting focus in the same frame as setVisibility(VISIBLE)
            // is unreliable coming from a GONE state.
            installHelpContinueBtn.post(() -> installHelpContinueBtn.requestFocus());
            return;
        }
        showInstallTip(apkFile);
    }

    /**
     * Shown right before every install (new app or update) so the fix for the
     * most common failure — a same-named app already on the projector — is
     * already in front of people. For a single-file install there's no way
     * to detect that failure after the fact, hence showing it up front; for
     * a split-APK install (see launchMultiInstaller()) we actually do get a
     * failure callback, and re-show this same screen when that happens so
     * they can delete the old app and hit Install again.
     */
    private void showInstallTip(File apkFile) {
        List<File> single = new ArrayList<>();
        single.add(apkFile);
        showInstallTip(single);
    }

    private void showInstallTip(List<File> apkFiles) {
        pendingInstallApks = apkFiles;
        updateOverlay.setVisibility(View.GONE);
        installHelpOverlay.setVisibility(View.GONE);
        installTipOverlay.setVisibility(View.VISIBLE);
        installTipContinueBtn.setEnabled(true);
        // Same deferred-focus-request pattern as installHelpContinueBtn — the
        // remote's OK button won't reach a freshly-VISIBLE view until after
        // its layout pass, so requesting focus in the same frame is unreliable.
        installTipContinueBtn.post(() -> installTipContinueBtn.requestFocus());
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
        awaitingInstallerReturn = true;
        hideUpdateOverlay();
    }

    // ───────────────────────────────────────────────────────────────────────
    //  SPLIT-APK STORE DOWNLOADS
    //
    //  Called from AndroidBridge.downloadAppSplits() for an app that ships as
    //  a base APK plus arch/locale/density splits instead of one file. A lone
    //  split isn't installable by itself — Android only accepts them together
    //  in one atomic PackageInstaller session — so unlike the single-file
    //  path above, these all get downloaded first, then installed as a unit.
    // ───────────────────────────────────────────────────────────────────────

    public void startSplitAppDownload(String urlsJson, String displayName) {
        if (installFlowBusy()) return; // something's already downloading/installing
        updateStatusText.setText("Downloading " + displayName + "…");
        updateOverlay.setVisibility(View.VISIBLE);
        new Thread(() -> downloadAndInstallSplits(urlsJson, displayName), "app-download-splits").start();
    }

    private void downloadAndInstallSplits(String urlsJson, String label) {
        List<String> urls = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(urlsJson);
            for (int i = 0; i < arr.length(); i++) urls.add(arr.getString(i));
        } catch (Exception e) {
            Log.w(TAG, "Bad split URL list for " + label + ": " + e.getMessage());
            runOnUiThread(() -> {
                updateStatusText.setText("Download failed");
                updateOverlay.postDelayed(this::hideUpdateOverlay, 1500);
            });
            return;
        }

        File dir = new File(getCacheDir(), "updates/splits");
        if (!dir.exists()) dir.mkdirs();
        List<File> outFiles = new ArrayList<>();
        try {
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                String name = new File(Uri.parse(url).getPath()).getName();
                if (name.isEmpty()) name = "split_" + i + ".apk";
                File out = new File(dir, name);
                downloadOneSplitFile(url, out, i + 1, urls.size(), label);
                outFiles.add(out);
            }
            runOnUiThread(() -> installSplitApks(outFiles));
        } catch (Exception e) {
            Log.w(TAG, "Split download failed for " + label + ": " + e.getMessage());
            for (File f : outFiles) if (f.exists()) f.delete();
            runOnUiThread(() -> {
                updateStatusText.setText("Download failed");
                updateOverlay.postDelayed(this::hideUpdateOverlay, 1500);
            });
        }
    }

    private void downloadOneSplitFile(String apkUrl, File out, int index, int total, String label) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apkUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            long size = conn.getContentLengthLong();

            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long received = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                received += n;
                if (size > 0) {
                    int pct = (int) (received * 100 / size);
                    runOnUiThread(() -> updateStatusText.setText(
                            "Downloading " + label + " (" + index + "/" + total + ")… " + pct + "%"));
                }
            }
            fos.flush();
            fos.close();
            in.close();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void installSplitApks(List<File> apkFiles) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateSplitApks = apkFiles;
            updateOverlay.setVisibility(View.GONE);
            installHelpOverlay.setVisibility(View.VISIBLE);
            installHelpContinueBtn.post(() -> installHelpContinueBtn.requestFocus());
            return;
        }
        showInstallTip(apkFiles);
    }

    /**
     * Installs a base APK + splits together in one atomic PackageInstaller
     * session — the only way Android will accept a lone split at all. Unlike
     * the single-file ACTION_VIEW path, committing a session DOES report back
     * whether it actually succeeded (see onNewIntent()), so a genuine failure
     * re-shows installTipOverlay with the same files ready to retry, rather
     * than just hoping the up-front tip was enough.
     */
    private void launchMultiInstaller(List<File> apkFiles) {
        currentMultiInstallApks = apkFiles;
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                for (File apk : apkFiles) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                }

                Intent callbackIntent = new Intent(this, MainActivity.class);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pendingIntent = PendingIntent.getActivity(this, sessionId, callbackIntent, piFlags);

                updateStatusText.setText("Installing…");
                updateOverlay.setVisibility(View.VISIBLE);
                session.commit(pendingIntent.getIntentSender());
            } finally {
                session.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Multi-APK install session failed: " + e.getMessage());
            hideUpdateOverlay();
            showInstallTip(apkFiles);
        }
    }

    /**
     * PackageInstaller reports a committed session's outcome by relaunching
     * the PendingIntent passed to session.commit() — for a getActivity()
     * PendingIntent targeting this (singleTask) activity, that means a call
     * here rather than a fresh onCreate(). STATUS_PENDING_USER_ACTION carries
     * the system's own install-confirmation screen to show; anything else is
     * the final result.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySafely(confirmIntent);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            currentMultiInstallApks = null;
            updateStatusText.setText("Installed");
            updateOverlay.setVisibility(View.VISIBLE);
            updateOverlay.postDelayed(this::hideUpdateOverlay, 1500);
            return;
        }

        Log.w(TAG, "Split install failed (status=" + status + "): " + message);
        final List<File> retryApks = currentMultiInstallApks;
        currentMultiInstallApks = null;
        // Show the actual PackageInstaller status/message on screen for a
        // few seconds before falling back to the tip — this is temporary
        // diagnostic instrumentation while we're still tracking down why
        // STV Player/ITVX fail to install, not the final copy.
        updateStatusText.setText("Install failed (status " + status + "): " + message);
        updateOverlay.setVisibility(View.VISIBLE);
        updateOverlay.postDelayed(() -> {
            // We actually know this failed, unlike the single-file install
            // path — re-show the tip with the same files so Install just
            // works after they've deleted whatever's conflicting, no need
            // to redownload.
            if (retryApks != null) {
                showInstallTip(retryApks);
            } else {
                hideUpdateOverlay();
            }
        }, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Deliberately NOT re-checking canRequestPackageInstalls() here. On
        // this firmware it can still report the old "denied" result for a
        // moment right after returning from Settings, even though the toggle
        // already shows as on — which was forcing users to flip it off/on
        // again just to get our cached read to catch up. The system
        // installer does its own authoritative permission check regardless,
        // so just hand off to it directly and let it be the judge (it shows
        // its own blocked-install prompt if the permission genuinely isn't
        // granted).
        if (pendingUpdateApk != null) {
            File apk = pendingUpdateApk;
            pendingUpdateApk = null;
            showInstallTip(apk);
        } else if (pendingUpdateSplitApks != null) {
            List<File> apks = pendingUpdateSplitApks;
            pendingUpdateSplitApks = null;
            showInstallTip(apks);
        } else if (awaitingInstallerReturn) {
            // Back from the system installer with nothing left pending —
            // make sure no popup of ours is still showing over the store
            // page, in case hideUpdateOverlay() didn't fully render before
            // the installer's activity took over the screen.
            awaitingInstallerReturn = false;
            hideUpdateOverlay();
        }
    }

    @Override
    public void onBackPressed() {
        if (installHelpOverlay != null && installHelpOverlay.getVisibility() == View.VISIBLE) {
            // Cancel the install rather than closing the whole app — this is
            // exactly the screen where someone's likely to reach for Back.
            installHelpOverlay.setVisibility(View.GONE);
            pendingUpdateApk = null;
            pendingUpdateSplitApks = null;
            hideUpdateOverlay();
        } else if (installTipOverlay != null && installTipOverlay.getVisibility() == View.VISIBLE) {
            installTipOverlay.setVisibility(View.GONE);
            pendingInstallApks = null;
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
