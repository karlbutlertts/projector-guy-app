package com.projectorguy.app;
import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.provider.Settings;
/** Brief non-focusable update banner for projector firmware that hides system notifications. */
final class UpdateBanner {
 private static final Handler main=new Handler(Looper.getMainLooper());private static volatile View banner;private static WindowManager windows;
 static boolean allowed(Context c){return Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(c);}
 static void hide(){main.post(()->{if(banner!=null)try{windows.removeView(banner);}catch(Exception ignored){}banner=null;});}
 static boolean isShowing(){return banner!=null;}
 static void showBatch(Context c,org.json.JSONArray updates,boolean test){if(!allowed(c))return;Context app=c.getApplicationContext();main.post(()->{
  if(!UpdateNotifications.enabled(app)||!LauncherUpdateService.isHome(app)||banner!=null)return;
  try{org.json.JSONObject notice=updates.optJSONObject(0);boolean isNotice=notice!=null&&notice.has("noticeId");if(isNotice&&(System.currentTimeMillis()>1796083199000L||UpdateNotifications.prefs(app).getBoolean(notice.optString("noticeId"),false)))return;String batch=UpdateNotifications.fingerprint(updates);if(batch.equals(UpdateNotifications.prefs(app).getString("last_batch","")))return;
   windows=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);LinearLayout box=new LinearLayout(app);box.setOrientation(1);box.setPadding(22,18,22,18);
   GradientDrawable bg=new GradientDrawable();bg.setColor(0xff12253b);bg.setCornerRadius(12);bg.setStroke(1,0xff6ab4c3);box.setBackground(bg);box.setElevation(10);
   TextView title=new TextView(app);title.setText(isNotice?notice.optString("noticeTitle"):"Projector Guy update available");title.setTextSize(19);title.setTypeface(null,1);title.setTextColor(0xfff0f6ff);box.addView(title);
   LinearLayout logos=new LinearLayout(app);logos.setOrientation(0);logos.setPadding(0,12,0,12);box.addView(logos);
   java.util.ArrayList<String> names=new java.util.ArrayList<>();boolean features=false;
   for(int i=0;i<updates.length();i++){org.json.JSONObject a=updates.getJSONObject(i);String pkg=a.getString("packageName");features|=pkg.equals("com.projectorguy.app");if(!pkg.equals("com.projectorguy.app"))names.add(a.optString("name","App"));
    if(i<4){ImageView icon=new ImageView(app);try{icon.setImageDrawable(app.getPackageManager().getApplicationIcon(pkg.equals("com.projectorguy.app")?app.getPackageName():pkg));}catch(Exception ignored){icon.setImageResource(R.mipmap.ic_launcher);}icon.setContentDescription(a.optString("name","App update"));LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(38,38);iconParams.rightMargin=10;logos.addView(icon,iconParams);}
   }
   if(features)names.add("new features");String summary=isNotice?notice.optString("noticeBody"):android.text.TextUtils.join(", ",names);TextView body=new TextView(app);body.setText(summary);body.setTextSize(14);body.setTextColor(0xffd5e6f5);body.setMaxLines(2);body.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(body);
   TextView hint=new TextView(app);hint.setText("Open Projector Guy to see what’s new.");hint.setTextSize(12);hint.setTextColor(0xffa8c2da);hint.setPadding(0,9,0,0);box.addView(hint);
   int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;WindowManager.LayoutParams params=new WindowManager.LayoutParams(430,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT);params.gravity=Gravity.BOTTOM|Gravity.RIGHT;params.x=28;params.y=28;params.setTitle("Projector Guy launcher update banner");windows.addView(box,params);banner=box;
   org.json.JSONObject seen=new org.json.JSONObject(UpdateNotifications.prefs(app).getString("seen_versions","{}"));for(int k=0;k<updates.length();k++){org.json.JSONObject a=updates.getJSONObject(k);if(!isNotice)seen.put(a.getString("packageName"),a.getLong("versionCode"));}UpdateNotifications.prefs(app).edit().putString("last_batch",batch).putString("seen_versions",seen.toString()).apply();if(isNotice)UpdateNotifications.prefs(app).edit().putBoolean(notice.getString("noticeId"),true).apply();box.setOnClickListener(v->{hide();app.startActivity(new Intent(app,MainActivity.class).putExtra("update_notification",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));});main.postDelayed(()->{if(banner==box){try{windows.removeView(box);}catch(Exception ignored){}banner=null;}},15000);
  }catch(Exception e){android.util.Log.w("UpdateBanner","Launcher banner unavailable",e);}
 });}
 static void show(Context c,String version,boolean test){if(!allowed(c))return;Context app=c.getApplicationContext();main.post(()->{
  if(banner!=null)try{windows.removeView(banner);}catch(Exception ignored){}banner=null;
  try{windows=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);LinearLayout box=new LinearLayout(app);box.setOrientation(1);box.setPadding(24,18,24,18);
   GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff18374f,0xff101b30});bg.setCornerRadius(18);bg.setStroke(2,0xff73d8df);box.setBackground(bg);box.setElevation(12);
   TextView brand=new TextView(app);brand.setText("PROJECTOR GUY"+(test?" · TEST":""));brand.setTextSize(11);brand.setTextColor(0xff83dfe8);box.addView(brand);
   TextView title=new TextView(app);title.setText("An update is ready");title.setTextSize(22);title.setTypeface(null,1);title.setTextColor(Color.WHITE);title.setPadding(0,7,0,6);box.addView(title);
   TextView body=new TextView(app);body.setText("Version "+version+" is available.\nOpen Projector Guy to review and install.");body.setTextSize(15);body.setTextColor(0xffd6e5f5);box.addView(body);
   int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
   WindowManager.LayoutParams p=new WindowManager.LayoutParams(430,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM|Gravity.RIGHT;p.x=28;p.y=28;p.setTitle("Projector Guy update banner");windows.addView(box,p);banner=box;
   box.setOnClickListener(v->{hide();app.startActivity(new Intent(app,MainActivity.class).putExtra("update_notification",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));});main.postDelayed(()->{if(banner==box){try{windows.removeView(box);}catch(Exception ignored){}banner=null;}},15000);
  }catch(Exception e){android.util.Log.w("UpdateBanner","Banner unavailable",e);}
 });}
}
