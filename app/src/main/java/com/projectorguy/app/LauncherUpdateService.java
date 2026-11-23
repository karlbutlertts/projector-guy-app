package com.projectorguy.app;
import android.accessibilityservice.AccessibilityService;import android.content.*;import android.content.pm.*;import android.view.accessibility.AccessibilityEvent;
/** Observes window package changes only. Does not retrieve screen content or intercept keys. */
public final class LauncherUpdateService extends AccessibilityService {
 private static volatile String foreground="";private static volatile boolean connected;
 @Override protected void onServiceConnected(){connected=true;UpdateNotifications.prefs(this).edit().putBoolean("launcher_configured",true).apply();}
 @Override public void onAccessibilityEvent(AccessibilityEvent event){if(event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||event.getPackageName()==null)return;String pkg=event.getPackageName().toString(),cls=String.valueOf(event.getClassName());if(pkg.equals(getPackageName())&&UpdateBanner.isShowing()&&!cls.endsWith("Activity"))return;foreground=pkg;if(isHome(this))UpdateNotifications.deliverPending(this);else UpdateBanner.hide();}
 static boolean isHome(Context c){if(!connected)return false;Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);ResolveInfo r=c.getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);return r!=null&&r.activityInfo!=null&&foreground.equals(r.activityInfo.packageName);}
 @Override public void onInterrupt(){foreground="";UpdateBanner.hide();}
 @Override public void onDestroy(){connected=false;foreground="";UpdateBanner.hide();super.onDestroy();}
}
