package com.projectorguy.app;
import android.content.Intent;import android.provider.Settings;import android.app.Activity;import android.app.AlertDialog;import android.content.SharedPreferences;import android.os.Build;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.atomic.AtomicBoolean;import java.util.regex.*;

/** Narrow, reversible XBJ 360 actions; requires explicit device-local ADB authorisation. */
final class ProjectorOptimisation {
 private final Activity activity;private final SharedPreferences prefs;private static final AtomicBoolean busy=new AtomicBoolean();private static final String HELPER="com.oranth.accessibility",STORE="com.mylove.store",LAUNCHER="com.mylove.ui";
 ProjectorOptimisation(Activity a){activity=a;prefs=a.getSharedPreferences("projector_optimisation",0);}
 private Switch soundSwitch;
 private Switch performanceSwitch;
 private Switch gamingSwitch;
 private Switch touchSwitch;
 private android.app.ProgressDialog speedProgress;
 void show(String model) {
  if (busy.get()) { Toast.makeText(activity, "Optimisation is already running.", Toast.LENGTH_SHORT).show(); return; }
  if (!Build.DEVICE.startsWith("pioneer5") || !"a5pro".equals(model)) {
   showPerformance(model);
   return;
  }
  LinearLayout panel = new LinearLayout(activity);
  panel.setOrientation(LinearLayout.VERTICAL);
  panel.setPadding(24, 12, 24, 12);
  soundSwitch = new Switch(activity);
  soundSwitch.setText("Remove navigation sounds");
  soundSwitch.setTextOn("ON");
  soundSwitch.setTextOff("OFF");
  soundSwitch.setShowText(true);
  styleSwitch(soundSwitch);
  soundSwitch.setPadding(12, 16, 12, 16);
  soundSwitch.setTextSize(20);
  soundSwitch.setEnabled(false);
  soundSwitch.setContentDescription("Navigation sounds, loading");
  soundSwitch.setOnClickListener(view -> navigationSounds(soundSwitch.isChecked()));
  panel.addView(soundSwitch, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  performanceSwitch = new Switch(activity);
  performanceSwitch.setText("Improve projector speed");
  performanceSwitch.setTextOn("ON");
  performanceSwitch.setTextOff("OFF");
  performanceSwitch.setShowText(true);
  performanceSwitch.setTextSize(20);
  performanceSwitch.setPadding(12, 16, 12, 16);
  performanceSwitch.setEnabled(false);
  styleSwitch(performanceSwitch);
  performanceSwitch.setOnClickListener(view -> performance(performanceSwitch.isChecked()));
  panel.addView(performanceSwitch, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  gamingSwitch = new Switch(activity);
  gamingSwitch.setText("Activate gaming mode 🎮");
  gamingSwitch.setTextOn("ON");
  gamingSwitch.setTextOff("OFF");
  gamingSwitch.setShowText(true);
  gamingSwitch.setTextSize(20);
  gamingSwitch.setPadding(12, 16, 12, 16);
  gamingSwitch.setEnabled(false);
  if (touchSwitch != null) touchSwitch.setEnabled(false);
  styleSwitch(gamingSwitch);
  gamingSwitch.setOnClickListener(view -> gamingMode(gamingSwitch.isChecked()));
  panel.addView(gamingSwitch, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  touchSwitch = new Switch(activity);
  touchSwitch.setText("Auto-hide touchscreen controls");
  touchSwitch.setTextOn("ON"); touchSwitch.setTextOff("OFF"); touchSwitch.setShowText(true);
  touchSwitch.setTextSize(20); touchSwitch.setPadding(12, 16, 12, 16);
  styleSwitch(touchSwitch); touchSwitch.setEnabled(false);
  touchSwitch.setOnClickListener(view -> touchscreenControls(touchSwitch.isChecked()));
  panel.addView(touchSwitch, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
  scroll.addView(panel);
  AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Optimise A5 Pro").setView(scroll)
      .setNegativeButton("Close", null).create();
  dialog.show();
  // Android's default alert width is too narrow for a landscape projector screen.
  int screenWidth = activity.getWindow().getDecorView().getWidth();
  if (screenWidth <= 0) screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
  boolean landscape = activity.getResources().getConfiguration().orientation
      == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
  if (dialog.getWindow() != null) dialog.getWindow().setLayout(
      Math.round(screenWidth * (landscape ? 0.74f : 0.92f)),
      android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
  navigationSounds(null);
 }
 private void gamingMode(Boolean enabled) {
  if (!busy.compareAndSet(false, true)) return;
  soundSwitch.setEnabled(false);
  performanceSwitch.setEnabled(false);
  gamingSwitch.setEnabled(false);
  if (touchSwitch != null) touchSwitch.setEnabled(false);
  new Thread(() -> {
   String error = null;
   boolean current = false;
   try (LocalAdb adb = new LocalAdb(activity)) {
    if (enabled != null) {
     String value = enabled ? "1" : "0";
     command(adb, "settings put secure minimal_post_processing_allowed " + value);
     if (!value.equals(command(adb, "settings get secure minimal_post_processing_allowed"))) {
      throw new Exception("Android did not save gaming mode.");
     }
    }
    current = "1".equals(command(adb, "settings get secure minimal_post_processing_allowed"));
   } catch (Exception failure) {
    error = failure.getMessage();
   } finally {
    busy.set(false);
   }
   String message = error;
   boolean selected = current;
   activity.runOnUiThread(() -> {
    gamingSwitch.setChecked(selected);
    soundSwitch.setEnabled(true);
    performanceSwitch.setEnabled(true);
    gamingSwitch.setEnabled(true);
    if (touchSwitch != null) touchSwitch.setEnabled(true);
    gamingSwitch.setContentDescription("Gaming mode " + (selected ? "on" : "off") + ". Press OK to switch.");
    if (enabled == null) touchscreenControls(null); else gamingSwitch.requestFocus();
    if (message != null) new AlertDialog.Builder(activity).setTitle("Gaming mode")
      .setMessage(message).setPositiveButton("OK", null).show();
   });
  }, "GamingMode").start();
 }
 private void touchscreenControls(Boolean enabled) {
  if (!busy.compareAndSet(false, true)) return;
  soundSwitch.setEnabled(false); performanceSwitch.setEnabled(false);
  gamingSwitch.setEnabled(false); touchSwitch.setEnabled(false);
  new Thread(() -> {
   String error = null; boolean selected = false;
   try (LocalAdb adb = new LocalAdb(activity)) {
    if (enabled != null) TouchOverlayControl.set(activity, adb, enabled);
    else if (TouchOverlayControl.enabled(activity) && !TouchOverlayControl.running(adb))
      TouchOverlayControl.set(activity, adb, true);
    selected = TouchOverlayControl.enabled(activity) && TouchOverlayControl.running(adb);
   } catch (Exception failure) { error = failure.getMessage(); }
   finally { busy.set(false); }
   String message = error; boolean checked = selected;
   activity.runOnUiThread(() -> {
    touchSwitch.setChecked(checked);
    soundSwitch.setEnabled(true); performanceSwitch.setEnabled(true);
    gamingSwitch.setEnabled(true); touchSwitch.setEnabled(true);
    touchSwitch.setContentDescription("Auto-hide touchscreen controls " + (checked ? "on" : "off") + ". Press OK to switch.");
    if (enabled == null) soundSwitch.requestFocus(); else touchSwitch.requestFocus();
    if (message != null) new AlertDialog.Builder(activity).setTitle("Auto-hide touchscreen controls")
      .setMessage(message).setPositiveButton("OK", null).show();
    else if (Boolean.TRUE.equals(enabled)) new AlertDialog.Builder(activity)
      .setTitle("Auto-hide touchscreen controls")
      .setMessage("Touch the built-in screen to show its controls. They hide again after five seconds without touch. This preference is saved for reboot. Switch OFF to restore the normal controls.")
      .setPositiveButton("Mate... I know", null).show();
   });
  }, "TouchOverlayControl").start();
 }
 private void styleSwitch(Switch control) {
  control.setSwitchMinWidth((int)(68 * activity.getResources().getDisplayMetrics().density));
  control.setThumbDrawable(activity.getDrawable(R.drawable.optimise_switch_thumb));
  control.setTrackDrawable(activity.getDrawable(R.drawable.optimise_switch_track));
  control.setBackgroundResource(R.drawable.optimise_switch_focus);
 }
 private void performance(Boolean enabled) {
  if (!busy.compareAndSet(false, true)) return;
  if (Boolean.TRUE.equals(enabled)) {
   speedProgress = new android.app.ProgressDialog(activity);
   speedProgress.setTitle("Improve projector speed");
   speedProgress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
   speedProgress.setMessage("Preparing apps…");
   speedProgress.setMax(1);
   speedProgress.setCancelable(false);
   speedProgress.show();
  }
  performanceSwitch.setEnabled(false);
  soundSwitch.setEnabled(false);
  performanceSwitch.setEnabled(false);
  gamingSwitch.setEnabled(false);
  if (touchSwitch != null) touchSwitch.setEnabled(false);
  new Thread(() -> {
   String error = null;
   String summary = null;
   boolean active = false;
   String[] keys = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};
   String snapshotKey = "animation_snapshot_" + Build.FINGERPRINT;
   try (LocalAdb adb = new LocalAdb(activity)) {
    JSONObject before = new JSONObject();
    for (String key : keys) before.put(key, command(adb, "settings get global " + key));
    if (enabled != null) {
     JSONObject target;
     if (enabled) {
      if (!prefs.contains(snapshotKey) &&
          !prefs.edit().putString(snapshotKey, before.toString()).commit()) {
       throw new Exception("Could not save the original animation settings.");
      }
      target = new JSONObject();
      for (String key : keys) target.put(key, "0");
     } else {
      target = new JSONObject(prefs.getString(snapshotKey, before.toString()));
     }
     try {
      for (String key : keys) setAnimation(adb, key, target.getString(key));
     } catch (Exception failure) {
      for (String key : keys) setAnimation(adb, key, before.getString(key));
      throw failure;
     }
     if (enabled) {
      String homeResult = configureLauncherHome(adb, true);
      summary = compileApps(adb) + "\n" + homeResult;
      if (!prefs.edit().putBoolean("speed_compilation_" + Build.FINGERPRINT, true).commit()) {
       throw new Exception("Apps processed, but the completed state could not be saved. Retry to verify.");
      }
     } else {
      configureLauncherHome(adb, false);
      prefs.edit().remove("speed_compilation_" + Build.FINGERPRINT).commit();
     }
     if (!enabled && !prefs.edit().remove(snapshotKey).commit()) {
      throw new Exception("Settings restored. Reopen Optimise to check the saved state.");
     }
    }
    active = prefs.contains(snapshotKey) && prefs.getBoolean("speed_compilation_" + Build.FINGERPRINT, false);
    for (String key : keys) {
     String value = command(adb, "settings get global " + key);
     active &= !"null".equals(value) && Double.parseDouble(value) == 0;
    }
   } catch (Exception failure) {
    error = failure.getMessage();
   } finally {
    busy.set(false);
   }
   String message = error;
   String completed = summary;
   boolean selected = active;
   activity.runOnUiThread(() -> {
    if (speedProgress != null) { speedProgress.dismiss(); speedProgress = null; }
    performanceSwitch.setChecked(selected);
    performanceSwitch.setEnabled(true);
    soundSwitch.setEnabled(true);
    gamingSwitch.setEnabled(true);
    if (touchSwitch != null) touchSwitch.setEnabled(true);
    if (enabled == null) gamingMode(null); else performanceSwitch.requestFocus();
    performanceSwitch.setContentDescription("Improve projector speed " + (selected ? "on" : "off") + ". Press OK to switch.");
    if (message != null) new AlertDialog.Builder(activity).setTitle("Improve projector speed")
      .setMessage(message).setNeutralButton("Restore saved settings", (dialog, which) -> performance(false))
      .setPositiveButton("OK", null).show();
    else if (completed != null) new AlertDialog.Builder(activity).setTitle("Optimisation complete")
      .setMessage(completed + "\n\nMenu animation delays are removed. App compilation is saved on the projector and normally survives restarting. Android may recompile after app or firmware updates. Switching this toggle off restores your saved animation and Home-route settings; compiled app code is retained.")
      .setPositiveButton("Mate... I know", null).show();
   });
  }, "AnimationPerformance").start();
 }
 private String configureLauncherHome(LocalAdb adb, boolean enabled) throws Exception {
  String fallback = "com.newlink.provision/.FallbackHome";
  String launcher = "com.zeasn.launcher/.ui.MainActivity";
  String key = "launcher_home_snapshot_" + Build.FINGERPRINT;
  android.content.ComponentName component = android.content.ComponentName.unflattenFromString(fallback);
  if (enabled) {
   String home = command(adb, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
   if (!prefs.contains(key) && !home.contains(fallback)) return "Stock launcher Home route already selected or managed by this firmware.";
   try {
    if (!command(adb, "su 0 id").contains("uid=0")) return "Launcher Home optimisation is unavailable on this firmware.";
   } catch (Exception unavailable) {
    return "Launcher Home optimisation is unavailable on this firmware.";
   }
   if (!prefs.contains(key)) {
    int original = activity.getPackageManager().getComponentEnabledSetting(component);
    JSONObject snapshot = new JSONObject().put("componentState", original).put("home", fallback);
    if (!prefs.edit().putString(key, snapshot.toString()).commit()) throw new Exception("Could not save the original Home route.");
   }
   command(adb, "su 0 pm disable --user 0 " + fallback);
   command(adb, "cmd package set-home-activity " + launcher);
   if (!command(adb, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").contains(launcher)) {
    configureLauncherHome(adb, false);
    throw new Exception("Could not verify the stock launcher Home route.");
   }
   return "Home now opens the existing stock launcher directly, giving it Android Home-process priority.";
  }
  if (!prefs.contains(key)) return "";
  JSONObject snapshot = new JSONObject(prefs.getString(key, "{}"));
  int state = snapshot.getInt("componentState");
  String[] verbs = {"default-state", "enable", "disable", "disable-user", "disable-until-used"};
  if (state < 0 || state >= verbs.length || !fallback.equals(snapshot.getString("home"))) throw new Exception("Invalid saved Home route.");
  command(adb, "su 0 pm " + verbs[state] + " --user 0 " + fallback);
  command(adb, "cmd package set-home-activity " + fallback);
  if (activity.getPackageManager().getComponentEnabledSetting(component) != state ||
      !command(adb, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").contains(fallback)) {
   throw new Exception("Could not verify the restored Home route. Retry Restore.");
  }
  if (!prefs.edit().remove(key).commit()) throw new Exception("Home restored but its snapshot could not be cleared.");
  return "";
 }
 private String compileApps(LocalAdb adb) throws Exception {
  java.util.Set<String> packages = new java.util.TreeSet<>();
  packages.add("com.zeasn.launcher");
  packages.add("com.android.tv.settings");
  packages.add("com.zhiying.settings");
  String list = command(adb, "for c in LAUNCHER LEANBACK_LAUNCHER; do cmd package query-activities --brief --components -a android.intent.action.MAIN -c android.intent.category.$c; done");
  for (String line : list.split("\r?\n")) {
   int slash = line.indexOf('/');
   if (slash > 0) {
    String name = line.substring(0, slash).trim();
    if (name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) packages.add(name);
   }
  }
  int done = 0;
  int processed = 0;
  java.util.List<String> skipped = new java.util.ArrayList<>();
  for (String name : packages) {
   String storage = command(adb, "df -k /data | tail -n 1");
   String[] columns = storage.trim().split("\\s+");
   if (columns.length < 4 || Long.parseLong(columns[3]) < 400000) {
    throw new Exception("Stopped to keep at least 400 MB of storage free. " + processed +
        " apps were processed. Free some storage and retry.");
   }
   int progress = done;
   activity.runOnUiThread(() -> {
    if (speedProgress != null) {
     speedProgress.setMax(packages.size());
     speedProgress.setProgress(progress);
     speedProgress.setMessage("Preparing " + name);
    }
   });
   String mode = name.equals("com.zeasn.launcher") || name.equals("com.android.tv.settings")
       || name.equals("com.zhiying.settings") ? "speed" : "speed-profile";
   String result = adb.shell("timeout 45 cmd package compile -m " + mode + " " + name);
   if (result.trim().equals("Success")) processed++; else skipped.add(name);
   done++;
   int finished = done;
   activity.runOnUiThread(() -> { if (speedProgress != null) speedProgress.setProgress(finished); });
  }
  if (!skipped.isEmpty()) {
   throw new Exception(processed + " of " + packages.size() + " apps processed. Android could not finish: " +
       android.text.TextUtils.join(", ", skipped) + ". Retry to finish the optimisation.");
  }
  return "Android processed " + processed + " apps for launch optimisation. Apps already compiled may be left as they are.";
 }
 private void setAnimation(LocalAdb adb, String key, String value) throws Exception {
  if ("null".equals(value)) command(adb, "settings delete global " + key);
  else {
   if (!value.matches("[0-9]+(\\.[0-9]+)?")) throw new Exception("Invalid saved animation value.");
   command(adb, "settings put global " + key + " " + value);
  }
  if (!value.equals(command(adb, "settings get global " + key))) {
   throw new Exception("Could not verify animation setting " + key);
  }
 }
 private void navigationSounds(Boolean enabled) {
  if (!busy.compareAndSet(false, true)) return;
  soundSwitch.setEnabled(false);
  performanceSwitch.setEnabled(false);
  gamingSwitch.setEnabled(false);
  if (touchSwitch != null) touchSwitch.setEnabled(false);
  new Thread(() -> {
   String failure = null;
   boolean connected = false;
   boolean current = false;
   try (LocalAdb adb = new LocalAdb(activity)) {
    connected = true;
    String device = command(adb, "getprop ro.product.device");
    if (!device.startsWith("pioneer5") ||
        !command(adb, "pm path com.zhiying.settings").contains("package:")) {
     throw new Exception("Navigation sound control is currently supported on the A5 Pro.");
    }
    if (enabled != null) {
     String value = enabled ? "0" : "1";
     command(adb, "setprop persist.play_sound " + value);
     if (!value.equals(command(adb, "getprop persist.play_sound"))) {
      throw new Exception("Android did not save the navigation sound setting.");
     }
     if (!prefs.edit().putBoolean("navigation_sounds", enabled).commit()) {
      throw new Exception("The projector setting was applied, but the app could not save its preference. Reopen this menu to read the current setting.");
     }
    }
    current = "0".equals(command(adb, "getprop persist.play_sound"));
   } catch (Exception e) {
    failure = e.getMessage();
   } finally {
    busy.set(false);
   }
   String error = failure;
   boolean needsConnection = !connected;
   boolean selected = current;
   activity.runOnUiThread(() -> {
    if (needsConnection) {
     setupGuide("Retry navigation sounds", () -> navigationSounds(enabled));
    } else if (error != null) {
     soundSwitch.setEnabled(true);
     performanceSwitch.setEnabled(true);
     gamingSwitch.setEnabled(true);
    if (touchSwitch != null) touchSwitch.setEnabled(true);
     new AlertDialog.Builder(activity).setTitle("Navigation click sounds")
       .setMessage(error).setPositiveButton("OK", null).show();
    } else {
     soundSwitch.setChecked(selected);
     soundSwitch.setEnabled(true);
     performanceSwitch.setEnabled(true);
     gamingSwitch.setEnabled(true);
    if (touchSwitch != null) touchSwitch.setEnabled(true);
     soundSwitch.setContentDescription("Remove navigation sounds " + (selected ? "on" : "off")
         + ". Press OK to switch.");
     soundSwitch.requestFocus();
     if (enabled == null) performance(null);
    }
   });
  }, "NavigationSounds").start();
 }
 private void showPerformance(String model){if(!"360".equals(model)||!"uiw4068pxm".equals(Build.DEVICE)){new AlertDialog.Builder(activity).setTitle("Projector optimisation").setMessage("These fixes are tested only on the XBJ 360 (uiw4068pxm). No changes were made.").setPositiveButton("OK",null).show();return;}
  LinearLayout box=new LinearLayout(activity);box.setOrientation(1);box.setPadding(28,16,28,16);TextView note=new TextView(activity);note.setText("Remove the Netflix focus helper and turn off animation delays. Netflix remote navigation may change. Your apps, accounts and home layout are kept.\n\nFirst use needs USB debugging enabled and approval of Projector Guy's connection on this projector. If debugging is off, we will guide you through enabling it in Settings.");box.addView(note);CheckBox store=new CheckBox(activity);store.setText("Also disable the bundled app store");box.addView(store);
  new AlertDialog.Builder(activity).setTitle("Optimise XBJ 360").setView(box).setPositiveButton("Optimise",(d,w)->run(false,store.isChecked())).setNeutralButton("Restore",(d,w)->run(true,false)).setNegativeButton("Cancel",null).show();
 }
 private String command(LocalAdb adb,String c)throws Exception{String s=adb.shell(c+"; printf '\\nPG_EXIT=%s\\n' $?");Matcher m=Pattern.compile("PG_EXIT=(\\d+)").matcher(s);if(!m.find()||!"0".equals(m.group(1)))throw new Exception("Command was not accepted: "+s);return s.substring(0,m.start()).trim();}
 private int state(LocalAdb adb,String pkg)throws Exception{String s=command(adb,"dumpsys package "+pkg);Matcher m=Pattern.compile("User 0:.*?enabled=(\\d+)").matcher(s);if(!m.find())throw new Exception("Cannot read package state: "+pkg);return Integer.parseInt(m.group(1));}
 private void setState(LocalAdb adb,String pkg,int state)throws Exception{String verb=state==0?"default-state":state==1?"enable":state==2?"disable":state==3?"disable-user":state==4?"disable-until-used":null;if(verb==null)throw new Exception("Unsupported saved package state");command(adb,"pm "+verb+" --user 0 "+pkg);if(state(adb,pkg)!=state)throw new Exception("Package change could not be verified: "+pkg);}
 private void apply(LocalAdb adb,boolean includeStore)throws Exception{
  JSONObject snapshot=prefs.contains("snapshot")?new JSONObject(prefs.getString("snapshot","{}")):new JSONObject();
  if(!snapshot.has("helper"))snapshot.put("helper",state(adb,HELPER));
  if(!snapshot.has("animation"))snapshot.put("animation",command(adb,"settings --user 0 get global animator_duration_scale"));
  if(includeStore&&!snapshot.has("store"))snapshot.put("store",state(adb,STORE));
  if(!prefs.edit().putString("snapshot",snapshot.toString()).commit())throw new Exception("Could not save restore settings. No changes were applied.");
  setState(adb,HELPER,3);if(includeStore)setState(adb,STORE,3);command(adb,"settings --user 0 put global animator_duration_scale 0");if(!"0".equals(command(adb,"settings --user 0 get global animator_duration_scale")))throw new Exception("Animation setting could not be verified");
 }
 private void restore(LocalAdb adb)throws Exception{
  if(!prefs.contains("snapshot"))throw new Exception("No saved settings to restore on this installation.");JSONObject s=new JSONObject(prefs.getString("snapshot","{}"));setState(adb,HELPER,s.getInt("helper"));if(s.has("store"))setState(adb,STORE,s.getInt("store"));String animation=s.getString("animation");if("null".equals(animation))command(adb,"settings --user 0 delete global animator_duration_scale");else{if(!animation.matches("[0-9]+(\\.[0-9]+)?"))throw new Exception("Invalid saved animation setting");command(adb,"settings --user 0 put global animator_duration_scale "+animation);}if(!animation.equals(command(adb,"settings --user 0 get global animator_duration_scale")))throw new Exception("Restore could not be verified");prefs.edit().remove("snapshot").commit();
 }
 private void openSettings(String action) {
  try { activity.startActivity(new Intent(action)); }
  catch (android.content.ActivityNotFoundException e) {
   try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
   catch (android.content.ActivityNotFoundException unavailable) {
    Toast.makeText(activity,"Open Settings using the projector remote, then return here.",Toast.LENGTH_LONG).show();
   }
  }
 }
 private void setupGuide(boolean restore,boolean store) {
  setupGuide(restore?"Retry Restore":"Retry Optimise",()->run(restore,store));
 }
 private void setupGuide(String retryLabel,Runnable retry) {
  LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(28,16,28,16);
  TextView instructions=new TextView(activity);
  instructions.setText("Projector Guy could not connect to this projector. Enable debugging or approve its connection, then retry.\n\n1. Open About. Find Build number (sometimes called Build) and press OK seven times to unlock Developer options. If already unlocked, skip this step.\n\n2. Open Developer options. Enable USB debugging and network debugging if your projector shows that switch. Menu names may vary.\n\n3. Press Back to return to Projector Guy, then choose the connection or retry button below. If Android asks to allow USB debugging, choose Allow for Projector Guy. You can tick Always allow to remember it.\n\nNo optimisation changes were made during this connection attempt. Your saved Restore settings are kept.");
  box.addView(instructions);
  Button about=new Button(activity);about.setText("1. Open About");about.setOnClickListener(v->openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));box.addView(about);
  Button developer=new Button(activity);developer.setText("2. Open Developer options");developer.setOnClickListener(v->openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));box.addView(developer);
  ScrollView scroll=new ScrollView(activity);scroll.addView(box);
  new AlertDialog.Builder(activity).setTitle("Set up projector optimisation").setView(scroll)
   .setPositiveButton(retryLabel,(d,w)->retry.run())
   .setNegativeButton("Close",null).show();
 }
 private void run(boolean restore,boolean store) {
  if(!busy.compareAndSet(false,true))return;
  Toast.makeText(activity,"Connecting… If Android asks to allow debugging, choose Allow for Projector Guy.",Toast.LENGTH_LONG).show();
  new Thread(()->{
   String message;boolean connected=false;boolean needsSetup=false;
   try(LocalAdb adb=new LocalAdb(activity)) {
    connected=true;
    String device=command(adb,"getprop ro.product.device");
    if(!"uiw4068pxm".equals(device))throw new Exception("Connected device is not a supported XBJ 360");
    if(restore){restore(adb);message="Saved settings restored and verified.";}
    else{apply(adb,store);message="Netflix helper disabled and animation delays removed. Changes verified. Reopen Netflix to try navigation. Restore returns to the settings saved before this app's first optimisation.";}
   } catch(Exception e) {
    needsSetup=!connected && e instanceof java.io.IOException;
    android.util.Log.w("PGOptimisation","Optimisation did not complete",e);
    message="The operation did not complete. Any saved restore settings are kept. You can retry or use Restore if changes were partially applied.";
   } finally {busy.set(false);}
   String result=message;boolean guide=needsSetup;
   activity.runOnUiThread(()->{
    if(guide)setupGuide(restore,store);
    else new AlertDialog.Builder(activity).setTitle("Projector optimisation").setMessage(result).setPositiveButton("OK",null).show();
   });
  },"ProjectorOptimisation").start();
 }
}
