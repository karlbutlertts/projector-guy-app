package com.projectorguy.app;
import android.content.Intent;import android.provider.Settings;import android.app.Activity;import android.app.AlertDialog;import android.content.SharedPreferences;import android.os.Build;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.atomic.AtomicBoolean;import java.util.regex.*;

/** Hardware-matched, reversible profiles using device-local ADB authorisation. */
final class ProjectorOptimisation {
 private final Activity activity;private final SharedPreferences prefs;private static final AtomicBoolean busy=new AtomicBoolean();private static final String HELPER="com.oranth.accessibility",STORE="com.mylove.store",LAUNCHER="com.mylove.ui";
 ProjectorOptimisation(Activity a){activity=a;prefs=a.getSharedPreferences("projector_optimisation",0);}
 private String config() throws Exception {
  try(java.io.InputStream in=activity.getAssets().open("optimisation/profiles.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
   byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
  }
 }
 void show(String ignoredModel){
  try{
   org.json.JSONArray profiles=new JSONObject(config()).getJSONArray("profiles");JSONObject profile=null;
   for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(Build.DEVICE.equals(p.getString("device"))&&Build.HARDWARE.equals(p.getString("hardware"))&&Build.VERSION.SDK_INT==p.getInt("sdk"))profile=p;}
   if(profile==null)throw new Exception("No verified profile for this projector yet. Your settings are kept.");
   String note=profile.getString("strengths")+"\n\n"+profile.getString("limitations")+"\n\nOptimise removes animation delays and disables optional printing/tracing tools where present. The stock launcher, stores, casting and Netflix navigation helper are kept. Picture settings are not changed automatically.\n\nYour actual settings are saved before changes. Restore uses that saved state, not presumed factory defaults. First use needs local ADB debugging authorisation.";
   AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(profile.getString("name")).setMessage(note).setPositiveButton("Optimise",(d,w)->run(false,false)).setNeutralButton("Restore",(d,w)->run(true,false)).setNegativeButton("Cancel",null);
   if(prefs.contains("snapshot")&&Build.DEVICE.equals("uiw4068pxm")){
    dialog.setNegativeButton("Previous optimiser…",(d,w)->new AlertDialog.Builder(activity).setTitle("Restore previous 360 optimiser").setMessage("An older snapshot exists for the Netflix helper, app store and animation setting. Restore those saved values separately?").setPositiveButton("Restore previous",(x,y)->run(true,true)).setNegativeButton("Cancel",null).show());
   }
   dialog.show();
  }catch(Exception e){new AlertDialog.Builder(activity).setTitle("Projector optimisation").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
 }
 private OptimisationEngine engine(LocalAdb adb)throws Exception {
  return new OptimisationEngine(config(),c->command(adb,c),new OptimisationEngine.Store(){
   public String get(String k){return prefs.getString(k,null);}
   public void put(String k,String v)throws Exception{if(!prefs.edit().putString(k,v).commit())throw new Exception("Could not save original settings. No changes made.");}
   public void remove(String k)throws Exception{if(!prefs.edit().remove(k).commit())throw new Exception("Restore verified but could not clear snapshot; safe to retry.");}
  });
 }
 private String command(LocalAdb adb,String c)throws Exception{String s=adb.shell(c+"; printf '\\nPG_EXIT=%s\\n' $?");Matcher m=Pattern.compile("PG_EXIT=(\\d+)").matcher(s);if(!m.find()||!"0".equals(m.group(1)))throw new Exception("Command was not accepted: "+s);return s.substring(0,m.start()).trim();}
 private int state(LocalAdb adb,String pkg)throws Exception{String s=command(adb,"dumpsys package "+pkg);Matcher m=Pattern.compile("User 0:.*?enabled=(\\d+)").matcher(s);if(!m.find())throw new Exception("Cannot read package state: "+pkg);return Integer.parseInt(m.group(1));}
 private void setState(LocalAdb adb,String pkg,int state)throws Exception{String verb=state==0?"default-state":state==1?"enable":state==2?"disable":state==3?"disable-user":state==4?"disable-until-used":null;if(verb==null)throw new Exception("Unsupported saved package state");command(adb,"pm "+verb+" --user 0 "+pkg);if(state(adb,pkg)!=state)throw new Exception("Package change could not be verified: "+pkg);}
 private void restoreLegacy(LocalAdb adb)throws Exception{
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
    OptimisationEngine engine=engine(adb);
    if(store){if(!"uiw4068pxm".equals(command(adb,"getprop ro.product.device")))throw new Exception("Legacy snapshot is only for the 360");restoreLegacy(adb);message="Previous optimiser settings restored and verified.";}
    else message=restore?engine.restore():engine.apply();
   } catch(Exception e) {
    needsSetup=!connected && e instanceof java.io.IOException;
    android.util.Log.w("PGOptimisation","Optimisation did not complete",e);
    message="The operation did not complete. "+e.getMessage()+"\nSaved restore settings are kept. Reconnect and retry or use Restore.";
   } finally {busy.set(false);}
   String result=message;boolean guide=needsSetup;
   activity.runOnUiThread(()->{
    if(guide)setupGuide(restore,store);
    else new AlertDialog.Builder(activity).setTitle("Projector optimisation").setMessage(result).setPositiveButton("OK",null).show();
   });
  },"ProjectorOptimisation").start();
 }
}
