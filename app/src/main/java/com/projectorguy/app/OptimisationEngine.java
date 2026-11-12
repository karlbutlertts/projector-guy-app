package com.projectorguy.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

/** Fixed local profiles only. No web supplied commands, models or package names. */
final class OptimisationEngine {
 interface Shell { String run(String command) throws Exception; }
 interface Store { String get(String key); void put(String key,String value) throws Exception; void remove(String key) throws Exception; }
 private final Shell shell; private final Store store; private final JSONArray profiles;
 OptimisationEngine(String config,Shell shell,Store store) throws Exception {
  JSONObject root=new JSONObject(config);
  if(root.getInt("schemaVersion")!=1)throw new Exception("Unsupported profile format");
  profiles=root.getJSONArray("profiles");this.shell=shell;this.store=store;
 }
 JSONObject detect() throws Exception {
  String device=shell.run("getprop ro.product.device"),hardware=shell.run("getprop ro.hardware"),sdk=shell.run("getprop ro.build.version.sdk");
  String home=shell.run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
  JSONObject match=null;
  for(int i=0;i<profiles.length();i++){
   JSONObject p=profiles.getJSONObject(i);
   if(device.equals(p.getString("device"))&&hardware.equals(p.getString("hardware"))&&sdk.equals(String.valueOf(p.getInt("sdk")))&&home.contains(p.getString("launcher")+"/")){
    if(match!=null)throw new Exception("Ambiguous device profile");match=p;
   }
  }
  if(match==null)throw new Exception("No verified profile matches this hardware, Android version and stock launcher. No changes made.");
  return match;
 }
 private String key(JSONObject p,String fingerprint)throws Exception{return "profile-v1:"+p.getString("id")+":"+fingerprint;}
 private String identity() throws Exception {
  String f=shell.run("getprop ro.build.fingerprint");
  String serial=shell.run("getprop ro.serialno");
  String androidId=shell.run("settings get secure android_id");
  if(f.isEmpty()||((serial.isEmpty()||serial.equals("unknown"))&&(androidId.isEmpty()||androidId.equals("null"))))throw new Exception("Cannot read stable device and firmware identity");return f+"|"+serial+"|"+androidId;
 }
 private String actionKey(JSONObject a)throws Exception{return a.getString("type")+":"+a.getString("target");}
 private void validate(JSONObject a)throws Exception {
  String t=a.getString("type"),n=a.getString("target"),v=a.getString("value");
  if("animation".equals(t)&&Arrays.asList("window_animation_scale","transition_animation_scale","animator_duration_scale").contains(n)&&"0".equals(v))return;
  if("package".equals(t)&&Arrays.asList("com.android.bips","com.android.traceur","org.chromium.webview_shell").contains(n)&&"3".equals(v))return;
  throw new Exception("Profile contains an unsupported action");
 }
 private String read(JSONObject a)throws Exception {
  validate(a);String n=a.getString("target");
  if("animation".equals(a.getString("type"))){
   String v=shell.run("settings get global "+n);
   if(!v.equals("null")&&!v.matches("[0-9]+(?:\\.[0-9]+)?"))throw new Exception("Cannot read animation setting: "+n);return v;
  }
  if(shell.run("pm path "+n+" || true").isEmpty())return "absent";
  Matcher m=Pattern.compile("User 0:[^\\n]*\\benabled=(\\d+)").matcher(shell.run("dumpsys package "+n));
  if(!m.find())throw new Exception("Cannot read package state: "+n);return m.group(1);
 }
 private boolean equal(JSONObject a,String x,String y)throws Exception {
  if("animation".equals(a.getString("type"))&&!x.equals("null")&&!y.equals("null"))return Double.compare(Double.parseDouble(x),Double.parseDouble(y))==0;
  return x.equals(y);
 }
 private void write(JSONObject a,String value)throws Exception {
  validate(a);String target=a.getString("target");
  if("animation".equals(a.getString("type"))){
   if(!value.equals("null")&&!value.matches("[0-9]+(?:\\.[0-9]+)?"))throw new Exception("Invalid saved animation");
   shell.run(value.equals("null")?"settings delete global "+target:"settings put global "+target+" "+value);
  }else{
   if(value.equals("absent"))return;
   String[] verbs={"default-state","enable","disable","disable-user","disable-until-used"};
   if(!value.matches("[0-4]"))throw new Exception("Invalid saved package state");
   shell.run("pm "+verbs[Integer.parseInt(value)]+" --user 0 "+target);
  }
  if(!equal(a,read(a),value))throw new Exception("Change failed verification: "+target);
 }
 String apply()throws Exception {
  JSONObject p=detect();String id=identity(),key=key(p,id);JSONArray actions=p.getJSONArray("actions");
  JSONObject before=new JSONObject();
  // Complete preflight and persist original values before the first mutation.
  for(int i=0;i<actions.length();i++){JSONObject a=actions.getJSONObject(i);before.put(actionKey(a),read(a));}
  JSONObject snapshot;
  if(store.get(key)==null){snapshot=new JSONObject().put("identity",id).put("profile",p.getString("id")).put("actions",new JSONArray(actions.toString())).put("before",before);store.put(key,snapshot.toString());}
  else {snapshot=new JSONObject(store.get(key));if(!id.equals(snapshot.getString("identity")))throw new Exception("Saved settings belong to another firmware");}
  int changed=0,already=0,skipped=0;List<JSONObject> applied=new ArrayList<>();
  try{
   for(int i=0;i<actions.length();i++){
    JSONObject a=actions.getJSONObject(i);String current=before.getString(actionKey(a));
    if(current.equals("absent")){skipped++;continue;}
    // Do not mutate a package installed after the original snapshot was taken.
    if(!snapshot.getJSONObject("before").has(actionKey(a))||snapshot.getJSONObject("before").getString(actionKey(a)).equals("absent")){skipped++;continue;}
    if(equal(a,current,a.getString("value"))){already++;continue;}
    applied.add(a);write(a,a.getString("value"));changed++;
   }
   JSONObject after=detect();if(!after.getString("id").equals(p.getString("id"))||!identity().equals(id))throw new Exception("Device identity changed during optimisation");
   return p.getString("name")+"\n"+changed+" changed and verified; "+already+" already optimised; "+skipped+" unavailable/skipped.\nStock launcher, stores, casting and navigation helpers were preserved.\nSaved Restore returns to this app's first observed settings, not factory defaults. Speed and picture gains have not been measured.";
  }catch(Exception failure){
   boolean rolledBack=true;
   Collections.reverse(applied);
   for(JSONObject a:applied)try{write(a,before.getString(actionKey(a)));}catch(Exception e){rolledBack=false;}
   throw new Exception(failure.getMessage()+ (rolledBack?" Changes from this attempt were rolled back.":" Some changes could not be rolled back; reconnect and use Restore."),failure);
  }
 }
 String restore()throws Exception {
  JSONObject p=detect();String id=identity(),key=key(p,id),raw=store.get(key);
  if(raw==null)throw new Exception("No saved settings for this device and firmware on this app installation.");
  JSONObject s=new JSONObject(raw);if(!id.equals(s.getString("identity")))throw new Exception("Saved identity mismatch");
  JSONArray actions=s.getJSONArray("actions");JSONObject before=s.getJSONObject("before");
  for(int i=actions.length()-1;i>=0;i--){JSONObject a=actions.getJSONObject(i);String value=before.getString(actionKey(a));if(!value.equals("absent"))write(a,value);}
  if(!identity().equals(id))throw new Exception("Device identity changed during restore");
  store.remove(key);return "Saved settings restored and verified. Stock launcher retained.";
 }
}
