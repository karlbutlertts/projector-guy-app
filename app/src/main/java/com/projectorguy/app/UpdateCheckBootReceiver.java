package com.projectorguy.app;
import android.content.*;
public final class UpdateCheckBootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){String a=i.getAction();if(Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)){UpdateNotifications.schedule(c);if(UpdateNotifications.prefs(c).getBoolean("launcher_configured",false))LauncherUpdateSetup.ensure(c);}}
}
