package com.projectorguy.app;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controls only the OEM floating-window package; does not change display mode or input routing. */
final class TouchOverlayControl {
    private static final String DIR = "/data/local/tmp/pg-touch-overlay";
    static boolean enabled(Context context) {
        return context.getSharedPreferences("projector_optimisation", 0)
                .getBoolean("touch_overlay_auto_hide", false);
    }
    static String run(LocalAdb adb, String command) throws Exception {
        String result = adb.shell(command + "; printf '\\nPG_TOUCH_EXIT=%s\\n' $?");
        Matcher match = Pattern.compile("PG_TOUCH_EXIT=(\\d+)").matcher(result);
        if (!match.find() || !"0".equals(match.group(1))) {
            throw new Exception("Touchscreen control failed: " + result);
        }
        return result.substring(0, match.start()).trim();
    }
    private static String root(LocalAdb adb, String command) throws Exception {
        return run(adb, "su 0 sh -c '" + command.replace("'", "'\\''") + "'");
    }
    static boolean running(LocalAdb adb) throws Exception {
        return "yes".equals(root(adb, "if [ -f " + DIR + "/enabled ] && [ -f " + DIR
                + "/running ] && kill -0 $(cat " + DIR + "/running) 2>/dev/null && tr '\\000' ' ' < /proc/$(cat " + DIR
                + "/running)/cmdline | grep -qF " + DIR + "/agent.sh; then echo yes; else echo no; fi"));
    }
    static synchronized void set(Context context, LocalAdb adb, boolean enable) throws Exception {
        if (!Build.DEVICE.startsWith("pioneer5") || !root(adb, "id").contains("uid=0")) {
            throw new Exception("Auto-hide requires a supported rooted A5 Pro.");
        }
        java.io.File flag = new java.io.File(context.getNoBackupFilesDir(), "touch-overlay-enabled");
        if (enable) {
            if (!flag.exists() && !flag.createNewFile()) throw new Exception("Could not save auto-hide state.");
            if (!running(adb)) {
                String operation = run(adb, "cmd appops get com.newlink.floatwindow SYSTEM_ALERT_WINDOW");
                Matcher matcher = Pattern.compile("SYSTEM_ALERT_WINDOW: (default|allow|ignore|deny|foreground)").matcher(operation);
                String original;
                if (matcher.find()) original = matcher.group(1);
                else if (operation.contains("No operations")) original = "default";
                else throw new Exception("Could not read the original touchscreen overlay permission.");
                if (enabled(context)) {
                    String saved = root(adb, "cat " + DIR + "/original-mode 2>/dev/null || true");
                    if (saved.matches("default|allow|ignore|deny|foreground")) original = saved;
                }
                String script;
                try (InputStream input = context.getAssets().open("touch-overlay-agent.sh")) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    String source = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                            .replace("@PREF_FLAG@", flag.getAbsolutePath());
                    script = Base64.encodeToString(source.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                }
                root(adb, "mkdir -p " + DIR + " && chmod 700 " + DIR + " && : > " + DIR + "/agent.sh");
                // Local ADB negotiates 4096-byte OPEN payloads. Bound every command.
                for(int offset=0;offset<script.length();offset+=2048) {
                    String chunk=script.substring(offset,Math.min(script.length(),offset+2048));
                    root(adb,"printf %s " + chunk + " | base64 -d >> " + DIR + "/agent.sh");
                }
                int expected=Base64.decode(script,Base64.NO_WRAP).length;
                if(!String.valueOf(expected).equals(root(adb,"wc -c < " + DIR + "/agent.sh")))
                    throw new Exception("Touchscreen helper copy could not be verified.");
                root(adb, "chmod 700 " + DIR + "/agent.sh"
                        + " && printf %s " + original + " > " + DIR + "/original-mode"
                        + " && touch " + DIR + "/enabled");
                root(adb, "CLASSPATH='" + context.getApplicationInfo().sourceDir.replace("'", "'\\''")
                        + "' app_process /system/bin com.projectorguy.app.TouchOverlayLauncher");
                boolean started = false;
                for (int attempt = 0; attempt < 20; attempt++) {
                    Thread.sleep(200);
                    if (running(adb)) { started = true; break; }
                }
                if (!started) {
                    root(adb, "rm -f " + DIR + "/enabled; cmd appops set com.newlink.floatwindow SYSTEM_ALERT_WINDOW " + original);
                    flag.delete();
                    throw new Exception("Auto-hide could not start. The normal controls have been restored.");
                }
            }
        } else {
            if (flag.exists() && !flag.delete()) throw new Exception("Could not clear auto-hide state.");
            if (!running(adb)) root(adb, "rm -f " + DIR + "/running");
            root(adb, "rm -f " + DIR + "/enabled");
            for (int attempt = 0; attempt < 20; attempt++) {
                if ("gone".equals(root(adb, "if [ -f " + DIR + "/running ]; then echo waiting; else echo gone; fi"))) break;
                Thread.sleep(200);
            }
            root(adb, "if [ -f " + DIR + "/running ]; then exit 1; fi; "
                    + "if [ -f " + DIR + "/original-mode ]; then "
                    + "mode=$(cat " + DIR + "/original-mode); case $mode in default|allow|ignore|deny|foreground) "
                    + "cmd appops set com.newlink.floatwindow SYSTEM_ALERT_WINDOW $mode;; *) exit 1;; esac; fi");
        }
        if (!context.getSharedPreferences("projector_optimisation", 0).edit()
                .putBoolean("touch_overlay_auto_hide", enable).commit()) {
            throw new Exception("Could not save the auto-hide preference. Reopen Optimise to check it.");
        }
    }
}
