package com.projectorguy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import java.io.*;
import java.util.List;
import java.util.regex.*;

/** Explicit, device-local ADB fallback for firmware with a broken split installer. */
final class ProjectorSplitInstaller {
    interface Completion { void finish(String message); }
    static void install(Activity activity, List<File> apks, Completion done) {
        new Thread(() -> {
            File dir = new File(activity.getExternalFilesDir(null), "split-install-" + System.nanoTime());
            String session = null;
            try (LocalAdb adb = new LocalAdb(activity)) {
                String device = adb.shell("getprop ro.product.device").trim();
                if (!device.equals("uiw4068pxm") && !device.equals("pioneer5_sdp_lr"))
                    throw new IOException("This fallback is only tested on the A5 Pro and 360.");
                if (!dir.mkdirs()) throw new IOException("Could not prepare installation files.");
                long total = 0;
                for (int i = 0; i < apks.size(); i++) {
                    File source = apks.get(i), copy = new File(dir, i == 0 ? "base.apk" : "split_" + i + ".apk");
                    try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(copy)) {
                        byte[] b = new byte[65536]; int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                    if (copy.length() != source.length()) throw new IOException("Incomplete APK copy.");
                    total += copy.length();
                }
                String created = adb.shell("pm install-create -r -S " + total);
                Matcher match = Pattern.compile("Success:.*\\[(\\d+)\\]").matcher(created);
                if (!match.find()) throw new IOException(created);
                session = match.group(1);
                try {
                    for (int i = 0; i < apks.size(); i++) {
                        File copy = new File(dir, i == 0 ? "base.apk" : "split_" + i + ".apk");
                        String command = "cat '" + copy.getAbsolutePath() + "' | pm install-write -S "
                                + copy.length() + " " + session + " " + copy.getName() + " -";
                        String written = adb.shell(command);
                        if (!written.contains("Success")) throw new IOException(written);
                    }
                    String result = adb.shell("pm install-commit " + session);
                    if (!result.trim().equals("Success")) throw new IOException(result);
                    session = null;
                } finally {
                    if (session != null) adb.shell("pm install-abandon " + session);
                }
                activity.runOnUiThread(() -> done.finish("Installed successfully using the projector connection."));
            } catch (Exception e) {
                final String message = e.getMessage();
                activity.runOnUiThread(() -> {
                    done.finish("Installation did not complete.");
                    new AlertDialog.Builder(activity).setTitle("Projector connection needed")
                        .setMessage("Enable USB/network debugging in Developer options and approve Projector Guy's debugging prompt, then retry. No app data has been removed.\n\n" + message)
                        .setPositiveButton("Retry", (d,w) -> install(activity, apks, done))
                        .setNeutralButton("Developer options", (d,w) -> {
                            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                            catch (RuntimeException ignored) { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                        }).setNegativeButton("Close", null).show();
                });
            } finally {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
                dir.delete();
            }
        }, "ProjectorSplitInstall").start();
    }
}
