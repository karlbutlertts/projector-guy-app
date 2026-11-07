package com.projectorguy.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.json.*;

public class OptimisationEngineTest {
 static final String CONFIG="{\"schemaVersion\":1,\"profiles\":[{\"id\":\"360\",\"name\":\"360\",\"device\":\"uiw4068pxm\",\"hardware\":\"sun50iw15p1\",\"sdk\":34,\"launcher\":\"com.mylove.ui\",\"actions\":[{\"type\":\"animation\",\"target\":\"animator_duration_scale\",\"value\":\"0\"},{\"type\":\"package\",\"target\":\"com.android.bips\",\"value\":\"3\"}]}]}";
 static class Fake implements OptimisationEngine.Shell,OptimisationEngine.Store {
  Map<String,String> saved=new HashMap<>();String animation="0.5",pkg="0",device="uiw4068pxm",hardware="sun50iw15p1",sdk="34",launcher="com.mylove.ui/.MainActivity",fingerprint="build-a",serial="unit-1";
  boolean absent=false,failSave=false,failDisable=false;int writes=0;
  public String get(String k){return saved.get(k);}
  public void put(String k,String v)throws Exception{if(failSave)throw new Exception("disk full");saved.put(k,v);}
  public void remove(String k){saved.remove(k);}
  public String run(String c)throws Exception{
   if(c.equals("getprop ro.product.device"))return device;
   if(c.equals("getprop ro.hardware"))return hardware;
   if(c.equals("getprop ro.build.version.sdk"))return sdk;
   if(c.equals("getprop ro.build.fingerprint"))return fingerprint;
   if(c.equals("getprop ro.serialno"))return serial;
   if(c.equals("settings get secure android_id"))return "android-id";
   if(c.startsWith("cmd package resolve-activity"))return launcher;
   if(c.startsWith("settings get global"))return animation;
   if(c.startsWith("settings put global")){animation=c.substring(c.lastIndexOf(' ')+1);writes++;return "";}
   if(c.startsWith("settings delete global")){animation="null";writes++;return "";}
   if(c.startsWith("pm path"))return absent?"":"package:/system/app/Print.apk";
   if(c.startsWith("dumpsys package"))return "    User 0: installed=true enabled="+pkg+"\n";
   if(c.startsWith("pm ")){
    if(c.startsWith("pm disable-user")&&failDisable)throw new Exception("permission denied");
    String verb=c.split(" ")[1];pkg=Arrays.asList("default-state","enable","disable","disable-user","disable-until-used").indexOf(verb)+"";writes++;return "";
   }
   throw new AssertionError("Unexpected shell command: "+c);
  }
  OptimisationEngine engine()throws Exception{return new OptimisationEngine(CONFIG,this,this);}
 }
 @Test public void applyIsIdempotentAndRestoresOriginals()throws Exception{
  Fake f=new Fake();assertTrue(f.engine().apply().contains("2 changed"));assertEquals("0",f.animation);assertEquals("3",f.pkg);
  String snapshot=f.saved.values().iterator().next();int writes=f.writes;
  assertTrue(f.engine().apply().contains("0 changed"));assertEquals(writes,f.writes);assertEquals(snapshot,f.saved.values().iterator().next());
  f.engine().restore();assertEquals("0.5",f.animation);assertEquals("0",f.pkg);assertTrue(f.saved.isEmpty());
 }
 @Test public void unknownDeviceDoesNotWrite()throws Exception{Fake f=new Fake();f.device="other";assertThrows(Exception.class,()->f.engine().apply());assertEquals(0,f.writes);assertTrue(f.saved.isEmpty());}
 @Test public void marketingModelCannotMatchWrongHardware()throws Exception{Fake f=new Fake();f.hardware="bigfish";assertThrows(Exception.class,()->f.engine().apply());assertEquals(0,f.writes);}
 @Test public void replacementLauncherDoesNotMatch()throws Exception{Fake f=new Fake();f.launcher="com.other.launcher/.Main";assertThrows(Exception.class,()->f.engine().apply());assertEquals(0,f.writes);}
 @Test public void noMutationsWithoutDurableSnapshot()throws Exception{Fake f=new Fake();f.failSave=true;assertThrows(Exception.class,()->f.engine().apply());assertEquals(0,f.writes);}
 @Test public void failedApplyRollsBackAndKeepsRestore()throws Exception{Fake f=new Fake();f.failDisable=true;assertThrows(Exception.class,()->f.engine().apply());assertEquals("0.5",f.animation);assertEquals("0",f.pkg);assertFalse(f.saved.isEmpty());}
 @Test public void unavailablePackageIsSkipped()throws Exception{Fake f=new Fake();f.absent=true;assertTrue(f.engine().apply().contains("1 unavailable"));f.engine().restore();assertEquals("0.5",f.animation);assertEquals("0",f.pkg);}
 @Test public void customerNullAndDisabledStatesAreRestored()throws Exception{Fake f=new Fake();f.animation="null";f.pkg="2";f.engine().apply();f.engine().restore();assertEquals("null",f.animation);assertEquals("2",f.pkg);}
 @Test public void firmwareChangeCannotUseAnotherSnapshot()throws Exception{Fake f=new Fake();f.engine().apply();f.fingerprint="build-b";int w=f.writes;assertThrows(Exception.class,()->f.engine().restore());assertEquals(w,f.writes);}
 @Test public void numericalZeroIsAlreadyOptimised()throws Exception{Fake f=new Fake();f.animation="0.0";f.pkg="3";assertTrue(f.engine().apply().contains("0 changed"));assertEquals(0,f.writes);}
 @Test public void profilesCannotTargetStockLauncher()throws Exception{Fake f=new Fake();String bad=CONFIG.replace("com.android.bips","com.mylove.ui");OptimisationEngine e=new OptimisationEngine(bad,f,f);assertThrows(Exception.class,e::apply);assertEquals(0,f.writes);}
 @Test public void anotherPhysicalDeviceCannotRestore()throws Exception{Fake f=new Fake();f.engine().apply();f.serial="unit-2";int w=f.writes;assertThrows(Exception.class,()->f.engine().restore());assertEquals(w,f.writes);}
}
