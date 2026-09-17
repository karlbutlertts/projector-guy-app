package com.projectorguy.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native bridge exposed to the WebView as window.AndroidBridge.
 *
 * Handles USB detection, formatting, and streaming firmware files straight to
 * the USB drive so the fix.html page can automate the whole process.
 *
 * IMPORTANT: formatUsbFat32() shells out to umount / mkfs.fat / mount. Those
 * commands only succeed if the app runs with root or system/platform privileges.
 * On a normal installed APK they will fail and the method returns false.
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    private final Context context;

    // Shared streaming write sessions (used by both uota and root writers)
    private final ConcurrentHashMap<String, FileOutputStream> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File> sessionFiles = new ConcurrentHashMap<>();

    // Native download jobs: token -> [received, total, state]  (state 0=downloading,1=done,2=error)
    private final ConcurrentHashMap<String, long[]> downloads = new ConcurrentHashMap<>();

    public AndroidBridge(Context context) {
        this.context = context;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  ACCESS-CODE UNLOCK NOTIFICATION
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Called by the web page once its own 6-digit-code lock screen grants
     * access (fresh code entry, or an already-valid saved session). Triggers
     * the native "check for app update" splash. JavascriptInterface callbacks
     * run on a WebView worker thread, not the UI thread, so hop over before
     * touching any views.
     */
    @JavascriptInterface
    public void onUnlocked() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).runOnUiThread(((MainActivity) context)::onUserUnlocked);
        }
    }

    /**
     * Called by the "Check for Updates" hub tile so a user can re-check on
     * demand, instead of only ever checking once automatically at launch.
     */
    @JavascriptInterface
    public void checkForUpdates() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).runOnUiThread(((MainActivity) context)::runManualUpdateCheck);
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  IN-APP STORE DOWNLOADS
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Called by the store page when a user taps an app tile's Download
     * button. Downloads the APK and hands it straight to the system
     * installer without ever leaving the app for the browser/Downloads app.
     */
    @JavascriptInterface
    public void downloadApp(String url, String displayName) {
        if (context instanceof MainActivity) {
            MainActivity activity = (MainActivity) context;
            activity.runOnUiThread(() -> activity.startAppDownload(url, displayName));
        }
    }

    /**
     * Called by the store page for apps that ship as a split APK bundle
     * (base + arch/locale/density splits) instead of one file — urlsJson is
     * a JSON array of download URLs, e.g. ["…/base.apk","…/split_en.apk"].
     * Downloads all of them, then installs them together in a single atomic
     * PackageInstaller session rather than one-by-one — a standalone split
     * APK isn't installable on its own outside of that session.
     */
    @JavascriptInterface
    public void downloadAppSplits(String urlsJson, String displayName) {
        if (context instanceof MainActivity) {
            MainActivity activity = (MainActivity) context;
            activity.runOnUiThread(() -> activity.startSplitAppDownload(urlsJson, displayName));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  USB DETECTION
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Finds the mount point of the first removable USB drive, or null.
     * Reads /proc/mounts and picks a removable vfat/exfat/ntfs/fuse mount that
     * is not internal/emulated storage.
     */
    private String findUsbPath() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(" ");
                if (p.length < 3) continue;
                String dev = p[0];
                String mp = p[1];
                String fs = p[2];

                if (mp.contains("emulated") || mp.equals("/") || mp.startsWith("/system")
                        || mp.startsWith("/vendor") || mp.startsWith("/data")) {
                    continue;
                }

                boolean usbish = mp.startsWith("/storage/")
                        || mp.startsWith("/mnt/usb")
                        || mp.startsWith("/mnt/media")
                        || mp.toLowerCase().contains("usb");

                boolean fsOk = fs.equals("vfat") || fs.equals("exfat")
                        || fs.equals("ntfs") || fs.startsWith("fuse");

                boolean devOk = dev.startsWith("/dev/")
                        || dev.startsWith("/dev/block")
                        || dev.contains("fuse");

                if (usbish && fsOk && devOk) {
                    File f = new File(mp);
                    if (f.exists() && f.canRead()) {
                        return mp;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findUsbPath failed: " + e.getMessage());
        } finally {
            if (br != null) try { br.close(); } catch (IOException ignored) {}
        }
        return null;
    }

    @JavascriptInterface
    public String getUsbPath() {
        return findUsbPath();
    }

    @JavascriptInterface
    public boolean isUsbMounted() {
        return findUsbPath() != null;
    }

    /**
     * Ensures a "uota" folder exists at the USB root and returns its absolute
     * path, or null on failure.
     */
    @JavascriptInterface
    public String ensureUotaFolder() {
        String usbPath = findUsbPath();
        if (usbPath == null) return null;
        File uota = new File(usbPath, "uota");
        if (!uota.exists() && !uota.mkdirs()) {
            Log.e(TAG, "Could not create uota folder");
            return null;
        }
        return uota.getAbsolutePath();
    }

    // ───────────────────────────────────────────────────────────────────────
    //  STREAMING WRITE SESSIONS
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Opens a streaming write session into the USB /uota folder.
     */
    @JavascriptInterface
    public String openWriteSession(String filename) {
        String uota = ensureUotaFolder();
        if (uota == null) return null;
        return openSessionInto(new File(uota), filename);
    }

    /**
     * Opens a streaming write session directly into the USB ROOT (no subfolder).
     */
    @JavascriptInterface
    public String openWriteSessionRoot(String filename) {
        String usbPath = findUsbPath();
        if (usbPath == null) return null;
        return openSessionInto(new File(usbPath), filename);
    }

    private String openSessionInto(File dir, String filename) {
        String safeName = new File(filename).getName();
        if (safeName.isEmpty()) return null;
        File out = new File(dir, safeName);
        if (out.exists()) out.delete();
        try {
            FileOutputStream fos = new FileOutputStream(out);
            String token = UUID.randomUUID().toString();
            sessions.put(token, fos);
            sessionFiles.put(token, out);
            Log.d(TAG, "Opened session → " + out.getAbsolutePath());
            return token;
        } catch (IOException e) {
            Log.e(TAG, "openSession failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Appends a base64-encoded chunk to an open session.
     */
    @JavascriptInterface
    public boolean appendChunk(String token, String base64Chunk) {
        FileOutputStream fos = sessions.get(token);
        if (fos == null) return false;
        try {
            byte[] data = Base64.decode(base64Chunk, Base64.DEFAULT);
            fos.write(data);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "appendChunk failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Flushes and closes a write session. Returns true on success.
     */
    @JavascriptInterface
    public boolean closeWriteSession(String token) {
        FileOutputStream fos = sessions.remove(token);
        File f = sessionFiles.remove(token);
        if (fos == null) return false;
        try {
            fos.flush();
            fos.getFD().sync();
            fos.close();
            Log.d(TAG, "Closed session → " + (f != null ? f.getAbsolutePath() : "?"));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "closeWriteSession failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Aborts a write session and deletes the partial file.
     */
    @JavascriptInterface
    public boolean abortWriteSession(String token) {
        FileOutputStream fos = sessions.remove(token);
        File f = sessionFiles.remove(token);
        try {
            if (fos != null) fos.close();
        } catch (IOException ignored) {
        }
        if (f != null && f.exists()) f.delete();
        return true;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  USB INFO / FORMATTING / CLEANUP
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Returns the current filesystem type of the USB drive (e.g. "vfat",
     * "exfat", "ntfs"). Returns null if unknown or not mounted.
     */
    @JavascriptInterface
    public String getUsbFilesystem() {
        String usbPath = findUsbPath();
        if (usbPath == null) return null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 3 && parts[1].equals(usbPath)) {
                    return parts[2];
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getUsbFilesystem failed: " + e.getMessage());
        } finally {
            if (br != null) try { br.close(); } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Returns total and free space on USB in bytes as "total,free".
     * e.g. "8053063680,7932215296"
     */
    @JavascriptInterface
    public String getUsbSpaceInfo() {
        String usbPath = findUsbPath();
        if (usbPath == null) return null;
        try {
            android.os.StatFs stat = new android.os.StatFs(usbPath);
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            return total + "," + free;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the block device path for a given mount point by reading /proc/mounts.
     */
    private String getBlockDeviceForPath(String mountPath) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2 && parts[1].equals(mountPath)) {
                    return parts[0]; // e.g. /dev/block/sda1
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getBlockDeviceForPath failed: " + e.getMessage());
        } finally {
            if (br != null) try { br.close(); } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Many USB sticks (exFAT/NTFS) are mounted via a FUSE passthrough on this
     * firmware, so /proc/mounts reports the source as "/dev/fuse" — not the
     * real partition. Scan /dev/block for typical USB mass-storage partitions
     * (sdXN, as opposed to the internal mmcblk0 eMMC) so we can format the
     * actual device instead of the fuse pseudo-device.
     */
    private String findUsbBlockDeviceFallback() {
        File blockDir = new File("/dev/block");
        File[] files = blockDir.listFiles();
        if (files == null) return null;
        // Prefer numbered partitions (sda1, sdb1...) over the whole-disk node
        for (File f : files) {
            if (f.getName().matches("sd[a-z][0-9]+")) return f.getAbsolutePath();
        }
        for (File f : files) {
            if (f.getName().matches("sd[a-z]")) return f.getAbsolutePath();
        }
        return null;
    }

    /**
     * Runs a shell command as root via "su -c", waits for it to finish and
     * returns its exit code, or -1 if "su" itself couldn't be executed.
     */
    private int runAsRoot(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "runAsRoot failed (" + command + "): " + e.getMessage());
            return -1;
        }
    }

    /**
     * Formats the mounted USB drive to FAT32. Uses mkfs.fat (falls back to
     * newfs_msdos), running as root via "su -c" since this firmware exposes a
     * working su binary over adb/shell. Falls back to running the commands
     * unprivileged if su is unavailable. Returns true on success, false on
     * failure. WIPES ALL DATA on the drive.
     */
    @JavascriptInterface
    public boolean formatUsbFat32() {
        String usbPath = findUsbPath();
        if (usbPath == null) return false;
        try {
            String blockDevice = getBlockDeviceForPath(usbPath);
            // /dev/fuse is a passthrough, not a real partition we can format
            if (blockDevice == null || blockDevice.contains("fuse")) {
                String fallback = findUsbBlockDeviceFallback();
                if (fallback != null) blockDevice = fallback;
            }
            if (blockDevice == null) return false;

            // Unmount (root first, falls back to unprivileged)
            if (runAsRoot("umount " + usbPath) != 0) {
                Process unmount = Runtime.getRuntime().exec(new String[]{"umount", usbPath});
                unmount.waitFor();
            }

            // Format to FAT32 — try root first
            int result = runAsRoot("mkfs.fat -F 32 -I " + blockDevice);
            if (result != 0) result = runAsRoot("newfs_msdos -F 32 " + blockDevice);
            if (result != 0) {
                // su unavailable or failed — try unprivileged as a last resort
                Process format = Runtime.getRuntime().exec(
                        new String[]{"mkfs.fat", "-F", "32", "-I", blockDevice}
                );
                result = format.waitFor();
                if (result != 0) {
                    format = Runtime.getRuntime().exec(
                            new String[]{"newfs_msdos", "-F", "32", blockDevice}
                    );
                    result = format.waitFor();
                }
            }

            // Remount
            Thread.sleep(1000);
            if (runAsRoot("mount -t vfat " + blockDevice + " " + usbPath) != 0) {
                Process mount = Runtime.getRuntime().exec(
                        new String[]{"mount", "-t", "vfat", blockDevice, usbPath}
                );
                mount.waitFor();
            }

            return result == 0;
        } catch (Exception e) {
            Log.e(TAG, "formatUsbFat32 failed: " + e.getMessage());
            return false;
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  NATIVE DOWNLOAD → USB  (no CORS, handles big files + Drive confirm page)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Starts a background download of a URL straight onto the USB root.
     * Returns a token to poll with getDownloadProgress(), or null on failure.
     * Runs server-side over HttpURLConnection, so it is NOT subject to browser
     * CORS and copes with files of any size (e.g. the ~1 GB DY image).
     */
    @JavascriptInterface
    public String startDownloadToUsb(final String url, final String filename) {
        final String usbPath = findUsbPath();
        if (usbPath == null) return null;
        final String safeName = new File(filename).getName();
        if (safeName.isEmpty()) return null;

        final String token = UUID.randomUUID().toString();
        downloads.put(token, new long[]{0, -1, 0});
        final File out = new File(usbPath, safeName);

        new Thread(new Runnable() {
            @Override public void run() { doDownload(token, url, out); }
        }, "dl-" + safeName).start();

        return token;
    }

    /**
     * Returns "received,total,state" for a download token (state 0=downloading,
     * 1=done, 2=error). total is -1 until known. Returns null for bad token.
     */
    @JavascriptInterface
    public String getDownloadProgress(String token) {
        long[] p = downloads.get(token);
        if (p == null) return null;
        return p[0] + "," + p[1] + "," + p[2];
    }

    private void doDownload(String token, String urlStr, File out) {
        long[] prog = downloads.get(token);
        HttpURLConnection conn = null;
        try {
            if (out.exists()) out.delete();

            CookieManager cm = new CookieManager();
            cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(cm);

            String id = extractParam(urlStr, "id");
            conn = openFollowing(urlStr);

            // Google Drive may return an HTML "can't scan for viruses" warning page
            String ct = conn.getContentType();
            if (ct != null && ct.toLowerCase().contains("text/html")) {
                String body = readAll(conn.getInputStream());
                conn.disconnect();
                String confirm = firstMatch(body, "confirm=([0-9A-Za-z_-]+)");
                String uuid = firstMatch(body, "name=\"uuid\"\\s+value=\"([^\"]+)\"");
                StringBuilder u = new StringBuilder("https://drive.usercontent.google.com/download?export=download");
                if (id != null) u.append("&id=").append(id);
                u.append("&confirm=").append(confirm != null ? confirm : "t");
                if (uuid != null) u.append("&uuid=").append(uuid);
                conn = openFollowing(u.toString());
            }

            long total = Build.VERSION.SDK_INT >= 24 ? conn.getContentLengthLong() : conn.getContentLength();
            prog[1] = total;

            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[262144]; // 256 KB
            long received = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (findUsbPath() == null) throw new IOException("USB removed");
                fos.write(buf, 0, n);
                received += n;
                prog[0] = received;
            }
            fos.flush();
            fos.getFD().sync();
            fos.close();
            in.close();

            prog[2] = 1; // done
            Log.d(TAG, "Downloaded " + out.getName() + " (" + received + " bytes)");
        } catch (Exception e) {
            prog[2] = 2; // error
            if (out.exists()) out.delete();
            Log.e(TAG, "doDownload failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openFollowing(String urlStr) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 ProjectorGuyApp");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect with no Location");
                current = loc.startsWith("http") ? loc : new URL(new URL(current), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        int cap = 2 * 1024 * 1024; // only need the small HTML warning page
        while ((n = in.read(b)) != -1 && bos.size() < cap) bos.write(b, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private String extractParam(String url, String key) {
        String m = firstMatch(url, "[?&]" + Pattern.quote(key) + "=([^&]+)");
        return m;
    }

    private String firstMatch(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Deletes all files from the USB root (not recursive into folders).
     */
    @JavascriptInterface
    public boolean clearUsbRoot() {
        String usbPath = findUsbPath();
        if (usbPath == null) return false;
        File root = new File(usbPath);
        File[] files = root.listFiles();
        if (files == null) return true;
        for (File f : files) {
            if (f.isFile()) f.delete();
        }
        return true;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  ENGINEERING / HIDDEN MENU LAUNCHER
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Opens the standard Android Settings app.
     */
    @JavascriptInterface
    public boolean openAndroidSettings() {
        return openSettingsAction(Settings.ACTION_SETTINGS);
    }

    /**
     * Opens any android.settings.* (or other) intent action, e.g.
     * "android.settings.ACCESSIBILITY_SETTINGS" or
     * "android.settings.APPLICATION_SETTINGS".
     */
    @JavascriptInterface
    public boolean openSettingsAction(String action) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivitySafely(intent, action);
    }

    /**
     * Reads an arbitrary system property (e.g. "persist.sys.debug") via
     * reflection on the hidden android.os.SystemProperties API — the same
     * trick most Android apps use to read OEM/vendor flags that don't have
     * a public getter. Returns "" if the property can't be read at all.
     */
    @JavascriptInterface
    public String getSystemProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            return (String) cls.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            Log.w(TAG, "getSystemProperty(" + key + ") failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Starts an activity, swallowing any failure (package/activity not
     * present on this firmware, missing permission, etc.) so a bad
     * engineering-menu shortcut never crashes the app. Returns true on
     * success.
     */
    private boolean startActivitySafely(Intent intent, String description) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed for " + description + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns true if a package is installed on this device.
     */
    @JavascriptInterface
    public boolean isPackageInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Launches a package's normal launcher activity (if it has one).
     * Returns false if the package has no launcher (common for factory/hidden
     * menu apps) — use listActivities()/launchActivity() instead in that case.
     */
    @JavascriptInterface
    public boolean launchApp(String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivitySafely(intent, packageName);
    }

    /**
     * Lists every activity declared by a package as JSON:
     * [{"name":"com.foo.BarActivity","exported":true}, ...]
     * Returns "[]" if the package isn't found or can't be read.
     */
    @JavascriptInterface
    public String listActivities(String packageName) {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info.activities != null) {
                for (ActivityInfo a : info.activities) {
                    JSONObject o = new JSONObject();
                    o.put("name", a.name);
                    o.put("exported", a.exported);
                    o.put("enabled", a.enabled);
                    arr.put(o);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listActivities failed for " + packageName + ": " + e.getMessage());
        }
        return arr.toString();
    }

    /**
     * Launches a specific activity by package + activity class name.
     * activityName may be the fully-qualified class, or start with "." to be
     * shorthand for packageName + activityName.
     */
    @JavascriptInterface
    public boolean launchActivity(String packageName, String activityName) {
        String cls = activityName.startsWith(".") ? packageName + activityName : activityName;
        Intent intent = new Intent();
        intent.setClassName(packageName, cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivitySafely(intent, packageName + "/" + activityName);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  PICTURE PRESETS — see PictureModeBridge for how this actually talks
    //  to the vendor's picture-mode service. Also used by the native
    //  double-menu-press overlay (PictureOverlayService).
    // ───────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public boolean applyPicturePreset(String preset) {
        return PictureModeBridge.applyPreset(context, preset);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  XBJ REMOTE CONTROL — see remote.html and LocalRemoteServer.
    // ───────────────────────────────────────────────────────────────────────

    private static final Map<String, Integer> COMMAND_KEYEVENTS = new HashMap<>();
    static {
        COMMAND_KEYEVENTS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        COMMAND_KEYEVENTS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        COMMAND_KEYEVENTS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        COMMAND_KEYEVENTS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        COMMAND_KEYEVENTS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        COMMAND_KEYEVENTS.put("power", KeyEvent.KEYCODE_POWER);
        COMMAND_KEYEVENTS.put("menu", KeyEvent.KEYCODE_MENU);
        COMMAND_KEYEVENTS.put("volumeUp", KeyEvent.KEYCODE_VOLUME_UP);
        COMMAND_KEYEVENTS.put("volumeDown", KeyEvent.KEYCODE_VOLUME_DOWN);
        COMMAND_KEYEVENTS.put("pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    // NOTE: package names below are the standard ones for these apps' Android
    // TV builds; not verified against what's actually installed on the XBJ
    // A5 Pro. If a hotkey reports "fail" in practice, check the real package
    // name via listActivities()/apps.json rather than assuming these are wrong.
    private static final Map<String, String> HOTKEY_PACKAGES = new HashMap<>();
    static {
        HOTKEY_PACKAGES.put("netflix", "com.netflix.ninja");
        HOTKEY_PACKAGES.put("disney", "com.disney.disneyplus");
        HOTKEY_PACKAGES.put("prime", "com.amazon.amazonvideo.livingroom");
        HOTKEY_PACKAGES.put("youtube", "com.google.android.youtube.tv");
    }

    /**
     * Entry point for the Remote Control page (remote.html): dispatches
     * D-pad/power/volume/menu key events and launches streaming-app hotkeys.
     * Called directly when remote.html runs in this app's own WebView, and
     * internally by LocalRemoteServer when a phone loads it over the LAN
     * instead. Returns "ok" or "fail" (remote.html checks for exactly "ok").
     *
     * focusUp/focusDown are not implemented — there is no known vendor API
     * for driving the XBJ A5 Pro's motorized lens focus from software, so
     * those two commands always report failure rather than silently doing
     * nothing while claiming success.
     */
    @JavascriptInterface
    public String sendProjectorCommand(String target, String command) {
        if (command == null) return "fail";
        try {
            String hotkeyPackage = HOTKEY_PACKAGES.get(command);
            if (hotkeyPackage != null) {
                return launchApp(hotkeyPackage) ? "ok" : "fail";
            }
            Integer keyCode = COMMAND_KEYEVENTS.get(command);
            if (keyCode != null) {
                return injectKeyEvent(keyCode) ? "ok" : "fail";
            }
            Log.w(TAG, "sendProjectorCommand: unsupported command '" + command + "'");
            return "fail";
        } catch (Exception e) {
            Log.e(TAG, "sendProjectorCommand failed for '" + command + "': " + e.getMessage());
            return "fail";
        }
    }

    /**
     * Injects a key event system-wide via "input keyevent", the same
     * privileged-shell pattern formatUsbFat32() uses — requires the su
     * binary this firmware exposes.
     */
    private boolean injectKeyEvent(int keyCode) {
        return runAsRoot("input keyevent " + keyCode) == 0;
    }

    /**
     * This device's own LAN address for the embedded Remote Control server
     * (e.g. "http://192.168.1.42:8899/remote.html"), or null if no WiFi/
     * Ethernet IPv4 address is currently up. remote.html uses this to build
     * a QR code/link a phone can actually reach — a phone can't fetch() this
     * server's plain-HTTP endpoint from the GitHub-hosted HTTPS copy of this
     * page (mixed-content blocking), so the shareable link must point here.
     */
    @JavascriptInterface
    public String getLocalRemoteUrl() {
        String ip = getLocalIpAddress();
        if (ip == null) return null;
        return "http://" + ip + ":" + LocalRemoteServer.PORT + "/remote.html";
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "getLocalIpAddress failed: " + e.getMessage());
        }
        return null;
    }
}
