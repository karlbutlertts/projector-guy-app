package com.projectorguy.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.*;

/** Exercise the shipped registry, including distinct settings and packages per device. */
public class OptimisationProfilesTest {
    private String config() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/assets/optimisation/profiles.json")), StandardCharsets.UTF_8);
    }
    private JSONArray profiles() throws Exception { return new JSONObject(config()).getJSONArray("profiles"); }

    private static final class Device implements OptimisationEngine.Shell, OptimisationEngine.Store {
        final JSONObject profile;
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> snapshots = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        String failTarget;
        Device(JSONObject profile) throws Exception {
            this.profile = profile;
            values.put("window_animation_scale", "null");
            values.put("transition_animation_scale", "0.0");
            values.put("animator_duration_scale", "1.5");
            values.put("com.android.bips", "0");
            values.put("com.android.traceur", "2");
            values.put("org.chromium.webview_shell", "1");
        }
        public String get(String key) { return snapshots.get(key); }
        public void put(String key, String value) { snapshots.put(key, value); }
        public void remove(String key) { snapshots.remove(key); }
        public String run(String command) throws Exception {
            commands.add(command);
            if (command.equals("getprop ro.product.device")) return profile.getString("device");
            if (command.equals("getprop ro.hardware")) return profile.getString("hardware");
            if (command.equals("getprop ro.build.version.sdk")) return String.valueOf(profile.getInt("sdk"));
            if (command.equals("getprop ro.build.fingerprint")) return "original-firmware";
            if (command.equals("getprop ro.serialno")) return "original-serial";
            if (command.equals("settings get secure android_id")) return "original-android-id";
            if (command.startsWith("cmd package resolve-activity")) return profile.getString("launcher") + "/.Home";
            String[] words = command.split(" ");
            if (command.startsWith("settings get global ")) return values.get(words[3]);
            if (command.startsWith("settings put global ")) { values.put(words[3], words[4]); return ""; }
            if (command.startsWith("settings delete global ")) { values.put(words[3], "null"); return ""; }
            if (command.startsWith("pm path ")) return values.containsKey(words[2]) ? "package:/system/app/Optional.apk" : "";
            if (command.startsWith("dumpsys package ")) return "    User 0: installed=true enabled=" + values.get(words[2]) + "\n";
            if (command.startsWith("pm ")) {
                String target = words[words.length - 1];
                if (target.equals(failTarget) && words[1].equals("disable-user")) throw new Exception("Device rejected package change");
                int state = Arrays.asList("default-state", "enable", "disable", "disable-user", "disable-until-used").indexOf(words[1]);
                if (state < 0 || !values.containsKey(target)) throw new AssertionError(command);
                values.put(target, String.valueOf(state));
                return "";
            }
            throw new AssertionError("Unexpected command: " + command);
        }
        long mutations() {
            return commands.stream().filter(c -> c.startsWith("settings put ") || c.startsWith("settings delete ") || (c.startsWith("pm ") && !c.startsWith("pm path "))).count();
        }
    }

    @Test public void bothProductionProfilesApplyRepeatAndRestoreExactOriginals() throws Exception {
        JSONArray profiles = profiles();
        assertEquals("Review new models explicitly", 2, profiles.length());
        for (int i = 0; i < profiles.length(); i++) {
            Device device = new Device(profiles.getJSONObject(i));
            Map<String, String> original = new LinkedHashMap<>(device.values);
            OptimisationEngine engine = new OptimisationEngine(config(), device, device);
            assertEquals(device.profile.getString("id"), engine.detect().getString("id"));
            engine.apply();
            assertEquals("0", device.values.get("window_animation_scale"));
            assertEquals("0.0", device.values.get("transition_animation_scale"));
            assertEquals("0", device.values.get("animator_duration_scale"));
            assertEquals("3", device.values.get("com.android.bips"));
            assertEquals("3", device.values.get("com.android.traceur"));
            assertEquals(i == 0 ? "1" : "3", device.values.get("org.chromium.webview_shell"));
            Map<String, String> firstSnapshot = new HashMap<>(device.snapshots);
            long mutations = device.mutations();
            assertTrue(engine.apply().contains("0 changed"));
            assertEquals(mutations, device.mutations());
            assertEquals(firstSnapshot, device.snapshots);
            engine.restore();
            assertEquals(original, device.values);
            assertTrue(device.snapshots.isEmpty());
            assertTrue("No protected service commands", device.commands.stream().noneMatch(c ->
                c.contains("com.oranth.accessibility") || c.contains("com.mylove.store") || c.contains("com.selenview.lemon") && c.startsWith("pm ") || c.contains("com.mylove.ui") && c.startsWith("pm ")));
        }
    }

    @Test public void bothProductionProfilesSkipMissingOptionalPackages() throws Exception {
        JSONArray profiles = profiles();
        for (int i = 0; i < profiles.length(); i++) {
            Device device = new Device(profiles.getJSONObject(i));
            device.values.keySet().removeIf(key -> key.contains("."));
            Map<String, String> original = new HashMap<>(device.values);
            OptimisationEngine engine = new OptimisationEngine(config(), device, device);
            assertTrue(engine.apply().contains(i == 0 ? "2 unavailable" : "3 unavailable"));
            engine.restore();
            assertEquals(original, device.values);
        }
    }

    @Test public void bothProductionProfilesRollbackEarlierChangesOnLaterFailure() throws Exception {
        JSONArray profiles = profiles();
        for (int i = 0; i < profiles.length(); i++) {
            Device device = new Device(profiles.getJSONObject(i));
            Map<String, String> original = new LinkedHashMap<>(device.values);
            device.failTarget = "com.android.traceur";
            OptimisationEngine engine = new OptimisationEngine(config(), device, device);
            Exception failure = assertThrows(Exception.class, engine::apply);
            assertTrue(failure.getMessage().contains("rolled back"));
            assertEquals(original, device.values);
            assertFalse(device.snapshots.isEmpty());
            device.failTarget = null;
            engine.restore();
            assertEquals(original, device.values);
        }
    }
}
