package com.projectorguy.app;
import android.content.*;import android.accessibilityservice.*;import android.view.accessibility.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
final class LauncherUpdateSetup {
 private static final AtomicBoolean busy=new AtomicBoolean();
 static boolean enabled(Context c){AccessibilityManager manager=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);for(AccessibilityServiceInfo s:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))if(new ComponentName(c,LauncherUpdateService.class).equals(ComponentName.unflattenFromString(s.getId())))return true;return false;}
 static void ensure(Context context){Context c=context.getApplicationContext();if(!UpdateNotifications.enabled(c)||enabled(c)||!android.os.Build.DEVICE.startsWith("pioneer5")||!busy.compareAndSet(false,true))return;
  new Thread(()->{try{synchronized(FactoryTranslationSetup.class){try(LocalAdb adb=new LocalAdb(c)){
   String raw=TouchOverlayControl.run(adb,"settings get secure enabled_accessibility_services").trim();if(raw.equals("null"))raw="";if(!raw.matches("[A-Za-z0-9_.$/:-]*"))return;
   LinkedHashSet<String> services=new LinkedHashSet<>();for(String id:raw.split(":"))if(!id.isEmpty())services.add(id);String own=new ComponentName(c,LauncherUpdateService.class).flattenToString();if(services.remove(own)){TouchOverlayControl.run(adb,"settings put secure enabled_accessibility_services '"+String.join(":",services)+"'");Thread.sleep(250);}services.add(own);
   TouchOverlayControl.run(adb,"settings put secure enabled_accessibility_services '"+String.join(":",services)+"'");TouchOverlayControl.run(adb,"settings put secure accessibility_enabled 1");
  }}}catch(Exception e){android.util.Log.i("LauncherUpdateSetup","Normal accessibility approval remains available");}finally{busy.set(false);}},"LauncherUpdateSetup").start();
 }
}
