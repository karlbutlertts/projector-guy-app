package com.projectorguy.app;
import android.app.*;import androidx.core.app.NotificationCompat;import android.app.job.*;import android.content.*;import android.os.*;import android.content.pm.PackageInfo;import org.json.*;import java.net.*;import java.io.*;

/** Periodic public-feed checks; never downloads or installs an APK in the background. */
final class UpdateNotifications {
 static final int PERIODIC=73410,ONCE=73411,NOTICE=73412;
 static final String CHANNEL="projector_updates",PREFS="update_notifications";
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}
 static boolean enabled(Context c){return prefs(c).getBoolean("activated",false)&&prefs(c).getBoolean("enabled",true);}
 static void activate(Context c){prefs(c).edit().putBoolean("activated",true).apply();schedule(c);LauncherUpdateSetup.ensure(c);}
 static void setEnabled(Context c,boolean on){prefs(c).edit().putBoolean("enabled",on).apply();if(on){schedule(c);LauncherUpdateSetup.ensure(c);}else{UpdateBanner.hide();JobScheduler j=((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE));j.cancel(PERIODIC);j.cancel(ONCE);((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);}}
 static void channel(Context c){if(Build.VERSION.SDK_INT>=26){NotificationChannel n=new NotificationChannel(CHANNEL,"Projector Guy updates",NotificationManager.IMPORTANCE_HIGH);n.setDescription("New Projector Guy versions. Select to review and install.");((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(n);}}
 static void schedule(Context c){if(!enabled(c))return;channel(c);JobScheduler j=((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE));ComponentName service=new ComponentName(c,UpdateCheckJob.class);
  if(j.getPendingJob(PERIODIC)==null)j.schedule(new JobInfo.Builder(PERIODIC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6*60*60*1000L).setPersisted(true).build());
  if(j.getPendingJob(ONCE)==null)j.schedule(new JobInfo.Builder(ONCE,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(60000).setOverrideDeadline(15*60*1000).build());
 }
 static String check(Context c)throws Exception{
  if(!enabled(c))return "disabled";
  if(prefs(c).getBoolean("launcher_configured",false))LauncherUpdateSetup.ensure(c);
  HttpURLConnection connection=(HttpURLConnection)new URL("https://raw.githubusercontent.com/karlbutlertts/xbj-apk-store/main/apps.json").openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setUseCaches(false);
  String body;try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Update feed too large");out.write(b,0,n);}body=out.toString("UTF-8");}finally{connection.disconnect();}
  if(Build.DEVICE.startsWith("pioneer5")&&System.currentTimeMillis()<=1796083199000L&&!prefs(c).getBoolean(TEST_NOTICE,false)){queueTestNotice(c);return "queued";}
  JSONArray candidates=findUpdates(c,new JSONArray(body));if(candidates.length()==0){prefs(c).edit().remove("pending").apply();queueTestNotice(c);return "up-to-date";}return queue(c,candidates,false);
 }
 static final String TEST_NOTICE="pg-update-test-20261123";
 static void queueTestNotice(Context c)throws Exception{
  if(!enabled(c)||!Build.DEVICE.startsWith("pioneer5")||System.currentTimeMillis()>1796083199000L||prefs(c).getBoolean(TEST_NOTICE,false))return;
  JSONArray notice=new JSONArray().put(new JSONObject().put("packageName","com.projectorguy.app").put("name","Projector Guy").put("versionCode",52).put("noticeId",TEST_NOTICE).put("noticeTitle","Projector Guy app has a new update").put("noticeBody","Test notification"));
  prefs(c).edit().putString("pending",notice.toString()).apply();deliverPending(c);
 }
 static JSONArray findUpdates(Context c,JSONArray apps)throws Exception{
  java.util.LinkedHashMap<String,JSONObject> found=new java.util.LinkedHashMap<>();String model=android.os.Build.DEVICE.startsWith("pioneer5")?"a5pro":"360";
  for(int i=0;i<apps.length();i++){JSONObject a=apps.getJSONObject(i);String pkg=a.optString("packageName");if(pkg.isEmpty())continue;JSONArray models=a.optJSONArray("models");boolean compatible=models==null;for(int k=0;models!=null&&k<models.length();k++)compatible|="all".equals(models.optString(k))||model.equals(models.optString(k));if(!compatible)continue;
   try{PackageInfo installed=c.getPackageManager().getPackageInfo("com.projectorguy.app".equals(pkg)?c.getPackageName():pkg,0);long local=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode,remote=a.optLong("versionCode",0);if(remote<=local)continue;JSONObject old=found.get(pkg);if(old==null||remote>old.optLong("versionCode"))found.put(pkg,new JSONObject().put("packageName",pkg).put("name",a.optString("name",pkg)).put("versionCode",remote));}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
  }return new JSONArray(found.values());
 }
 static String fingerprint(JSONArray updates){java.util.ArrayList<String> keys=new java.util.ArrayList<>();for(int i=0;i<updates.length();i++){JSONObject a=updates.optJSONObject(i);keys.add(a.has("noticeId")?"notice:"+a.optString("noticeId"):a.optString("packageName")+":"+a.optLong("versionCode"));}java.util.Collections.sort(keys);return android.text.TextUtils.join("|",keys);}
 static String queue(Context c,JSONArray updates,boolean test)throws Exception{
  if(test&&!BuildConfig.DEBUG)throw new SecurityException("Debug preview only");if(!enabled(c))return "disabled";
  JSONObject seen=new JSONObject(prefs(c).getString("seen_versions","{}"));JSONArray fresh=new JSONArray();for(int i=0;i<updates.length();i++){JSONObject a=updates.getJSONObject(i);if(a.optLong("versionCode")>seen.optLong(a.optString("packageName"),0))fresh.put(a);}if(fresh.length()==0)return "already-notified";updates=fresh;
  String id=fingerprint(updates);if(id.equals(prefs(c).getString("last_batch","")))return "already-notified";
  prefs(c).edit().putString("pending",updates.toString()).putBoolean("pending_test",test).apply();deliverPending(c);return "queued";
 }
 static void deliverPending(Context context){Context c=context.getApplicationContext();new Handler(Looper.getMainLooper()).post(()->{if(!enabled(c)||!LauncherUpdateService.isHome(c))return;try{JSONArray updates=new JSONArray(prefs(c).getString("pending","[]"));if(updates.length()==0||fingerprint(updates).equals(prefs(c).getString("last_batch","")))return;UpdateBanner.showBatch(c,updates,prefs(c).getBoolean("pending_test",false));}catch(Exception ignored){}});}

 static String candidate(Context c,long version,String name,boolean test)throws Exception{
  if(test&&!BuildConfig.DEBUG)throw new SecurityException("Test updates are debug only");
  if(!enabled(c))return "disabled";
  PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);long installed=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
  if(version<=installed){((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);return "up-to-date";}
  if(version<=prefs(c).getLong("last_notified",0))return "already-notified";
  channel(c);NotificationManager manager=((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE));
  if(Build.VERSION.SDK_INT>=24&&!manager.areNotificationsEnabled())return "notifications-blocked";
  if(Build.VERSION.SDK_INT>=26&&manager.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)return "channel-blocked";
  Intent intent=new Intent(c,MainActivity.class).putExtra("update_notification",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent open=PendingIntent.getActivity(c,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  NotificationCompat.Builder b=new NotificationCompat.Builder(c,CHANNEL);
  b.setSmallIcon(R.drawable.ic_update_notice).setContentTitle(test?"Projector Guy update — TEST":"Projector Guy update available").setContentText("Version "+name+" is ready. Open to review and install.").setCategory(Notification.CATEGORY_RECOMMENDATION).setPriority(Notification.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).addAction(new NotificationCompat.Action.Builder(R.drawable.ic_update_notice,"View update",open).build());
  if(Build.VERSION.SDK_INT>=26)b.setTimeoutAfter(7*24*60*60*1000L);
  android.os.Bundle tv=new android.os.Bundle();tv.putParcelable("content_intent",open);android.os.Bundle extras=new android.os.Bundle();extras.putBundle("android.tv.EXTENSIONS",tv);b.addExtras(extras);manager.notify(NOTICE,b.build());queue(c,new JSONArray().put(new JSONObject().put("packageName","com.projectorguy.app").put("name","Projector Guy").put("versionCode",version)),test);prefs(c).edit().putLong("last_notified",version).apply();return "notified";
 }
}
