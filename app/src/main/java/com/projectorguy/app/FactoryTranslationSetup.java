package com.projectorguy.app;
import android.content.*;import android.accessibilityservice.*;import android.view.accessibility.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;

/** First-use English default; preserves explicit OFF and all unrelated services. */
final class FactoryTranslationSetup {
 private static final AtomicBoolean working=new AtomicBoolean();
 private static long lastAttempt;
 private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("factory_translation",0);}
 static boolean wanted(Context c){return prefs(c).getBoolean("english",true);}
 static boolean enabled(Context c){AccessibilityManager a=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);if(a!=null)for(AccessibilityServiceInfo s:a.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))if(new ComponentName(c,FactoryMenuEnglishService.class).equals(ComponentName.unflattenFromString(s.getId())))return true;return false;}
 static void remember(Context c,boolean value){prefs(c).edit().putBoolean("english",value).apply();}
 static synchronized void apply(Context c,boolean on)throws Exception{
  try(LocalAdb adb=new LocalAdb(c)){
   String raw=TouchOverlayControl.run(adb,"settings get secure enabled_accessibility_services").trim();if(raw.equals("null"))raw="";if(!raw.matches("[A-Za-z0-9_.$/:-]*"))throw new Exception("Could not read existing accessibility settings");
   ComponentName own=new ComponentName(c,FactoryMenuEnglishService.class);LinkedHashSet<String> services=new LinkedHashSet<>();for(String id:raw.split(":"))if(!id.isEmpty()&&!own.equals(ComponentName.unflattenFromString(id)))services.add(id);if(on)services.add(own.flattenToString());
   TouchOverlayControl.run(adb,"settings put secure enabled_accessibility_services '"+String.join(":",services)+"'");if(on)TouchOverlayControl.run(adb,"settings put secure accessibility_enabled 1");
   prefs(c).edit().putBoolean("configured",true).apply();
  }
 }
 static void ensureDefault(Context context){
  Context c=context.getApplicationContext();if(!wanted(c)||prefs(c).getBoolean("configured",false))return;
  try{c.getPackageManager().getPackageInfo(FactoryMenuEnglishService.TARGET,0);}catch(Exception e){return;}
  if(enabled(c)){prefs(c).edit().putBoolean("configured",true).apply();return;}
  synchronized(FactoryTranslationSetup.class){if(android.os.SystemClock.elapsedRealtime()-lastAttempt<30000&&lastAttempt!=0)return;lastAttempt=android.os.SystemClock.elapsedRealtime();}
  if(!working.compareAndSet(false,true))return;
  new Thread(()->{try{if(wanted(c))apply(c,true);}catch(Exception ignored){/* Manual Android approval remains available. */}finally{working.set(false);}},"DefaultEnglishMenus").start();
 }
}
