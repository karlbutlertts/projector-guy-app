package com.projectorguy.app;
import android.content.Intent;import android.provider.Settings;import android.app.Activity;import android.app.AlertDialog;import android.content.SharedPreferences;import android.os.Build;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.atomic.AtomicBoolean;import java.util.regex.*;

/** Narrow, reversible XBJ 360 actions; requires explicit device-local ADB authorisation. */
final class ProjectorOptimisation {
 private final Activity activity;private final SharedPreferences prefs;private final AtomicBoolean busy=new AtomicBoolean();private static final String HELPER="com.oranth.accessibility",STORE="com.mylove.store",LAUNCHER="com.mylove.ui";
 ProjectorOptimisation(Activity a){activity=a;prefs=a.getSharedPreferences("projector_optimisation",0);}
 void show(String model){if(!"360".equals(model)||!"uiw4068pxm".equals(Build.DEVICE)){new AlertDialog.Builder(activity).setTitle("Projector optimisation").setMessage("These fixes are tested only on the XBJ 360 (uiw4068pxm). No changes were made.").setPositiveButton("OK",null).show();return;}
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
