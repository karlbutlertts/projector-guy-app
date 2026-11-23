package com.projectorguy.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private A5 setup test. Native actions accept catalogue IDs, never web commands. */
public class A5SetupActivity extends Activity {
 private WebView web;
 private final AtomicBoolean busy=new AtomicBoolean();
 private final Map<String,JSONObject> catalogue=new LinkedHashMap<>();
 private volatile String snapshot="{\"state\":\"scanning\",\"message\":\"Checking the A5 and app library…\"}";
 private volatile boolean stop;
 private static final String FEED="https://raw.githubusercontent.com/karlbutlertts/xbj-apk-store/main/apps.json";
 private static final String BASE="https://raw.githubusercontent.com/karlbutlertts/xbj-apk-store/main/apks/";
 @Override public void onCreate(Bundle saved){super.onCreate(saved);
  if(!android.os.Build.DEVICE.startsWith("pioneer5") || (!getIntent().getBooleanExtra("setup_after_selection",false) && !(BuildConfig.DEBUG && getIntent().getBooleanExtra("care_preview",false)))) { startActivity(new Intent(this,MainActivity.class));finish();return; }
  if(getSharedPreferences("a5_setup",0).getBoolean("complete",false) && !getIntent().getBooleanExtra("setup_endcard",false) && !(BuildConfig.DEBUG && getIntent().getBooleanExtra("care_preview",false))) {
   startActivity(new Intent(this,MainActivity.class).putExtra("setup_destination","home"));finish();return;
  }
  getWindow().getDecorView().setSystemUiVisibility(5894);
  web=new WebView(this);setContentView(web);
  web.setBackgroundColor(0xff0b1527);
  web.getSettings().setDefaultTextEncodingName("UTF-8");
  web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
  web.getSettings().setAllowFileAccess(true);web.getSettings().setAllowFileAccessFromFileURLs(false);
  web.addJavascriptInterface(new SetupBridge(),"A5Setup");
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !url.startsWith("file:///android_asset/setup/");}});
  WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
  if(BuildConfig.DEBUG && getIntent().getBooleanExtra("setup_endcard",false)) {
   web.loadUrl("file:///android_asset/setup/endcard.html");
  } else {
   web.loadUrl("file:///android_asset/setup/index.html"+(BuildConfig.DEBUG && getIntent().getBooleanExtra("care_preview",false)?"#care-options":""));scan();
  }
 }
 private void publish(String state,String message,JSONArray apps,int index,int total)throws Exception{
  JSONObject j=new JSONObject().put("state",state).put("message",message).put("index",index).put("total",total);
  if(apps!=null)j.put("apps",apps);snapshot=j.toString();
 }
 private long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
 private PackageInfo installed(String pkg){try{return getPackageManager().getPackageInfo(pkg,PackageManager.GET_SIGNATURES);}catch(Exception e){return null;}}
 private static boolean allowed(String u){try{URI x=new URI(u);return "https".equals(x.getScheme())&&Arrays.asList("raw.githubusercontent.com","media.githubusercontent.com").contains(x.getHost())&&x.getPath().startsWith((x.getHost().equals("media.githubusercontent.com")?"/media":"")+"/karlbutlertts/xbj-apk-store/main/");}catch(Exception e){return false;}}
 private HttpURLConnection connection(String u)throws Exception{
  if(!allowed(u))throw new IOException("Untrusted library URL");
  HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
  if(c.getResponseCode()!=200){c.disconnect();throw new IOException("Library download returned HTTP "+c.getResponseCode());}return c;
 }
 private void scan(){if(!busy.compareAndSet(false,true))return;new Thread(()->{
  try{
   if(!Build.DEVICE.startsWith("pioneer5"))throw new IOException("This setup test supports the A5 Pro only.");
   HttpURLConnection c=connection(FEED);ByteArrayOutputStream b=new ByteArrayOutputStream();
   try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>2000000)throw new IOException("Library response too large");}}finally{c.disconnect();}
   JSONArray feed=new JSONArray(b.toString("UTF-8")),apps=new JSONArray();catalogue.clear();
   for(int i=0;i<feed.length();i++){
    JSONObject a=feed.getJSONObject(i);JSONArray models=a.optJSONArray("models"),tags=a.optJSONArray("tags");
    String file=a.optString("file"),pkg=a.optString("packageName");
    if(models==null||tags==null||file.startsWith("entertainment/")||pkg.equals("com.projectorguy.app"))continue;
    boolean model=false;for(int n=0;n<models.length();n++)model|=Arrays.asList("all","a5pro").contains(models.getString(n));
    boolean stream=false,live=false;for(int n=0;n<tags.length();n++){stream|=tags.getString(n).equals("streaming");live|=tags.getString(n).equals("livetv");}
    if(!model||(!stream&&!live)||pkg.isEmpty()||catalogue.containsKey(pkg)||a.optInt("minSdk",21)>Build.VERSION.SDK_INT)continue;
    PackageInfo current=installed(pkg);long version=current==null?0:code(current),target=a.optLong("versionCode");
    JSONObject item=new JSONObject(a.toString());item.put("id",pkg).put("installedCode",version).put("kind",current==null?"install":version<target?"update":"keep");
    item.put("detail",current==null?"Not installed · add to your A5":version<target?"Update available · "+current.versionName+" → "+a.optString("versionName"):"Already installed · "+current.versionName);
    if(pkg.equals("org.beebplayer.android"))item.put("detail",item.getString("detail")+" · installs Beeb, independent BBC player");
    item.put("group",live?"live":"streaming");catalogue.put(pkg,item);apps.put(item);
   }
   publish("scanned","Installed apps checked against the current library",apps,0,apps.length());
  }catch(Exception e){fail(e);}finally{busy.set(false);}
 },"A5SetupScan").start();}
 private void fail(Exception e){try{String message=e.getMessage()==null?e.toString():e.getMessage();
  if(message.contains("127.0.0.1")||message.contains("ADB authorisation")||message.contains("Approve Projector Guy")) message="One-time projector connection needed. Enable USB debugging in Developer options, then approve Projector Guy on this projector and retry. No app data has been removed.\n\n"+message;
  publish("error",message,null,0,0);}catch(Exception ignored){}}
 private String command(LocalAdb adb,String cmd)throws Exception{return TouchOverlayControl.run(adb,cmd);}
 private void install(String ids){if(!busy.compareAndSet(false,true))return;stop=false;new Thread(()->{
  JSONArray results=new JSONArray();
  try(LocalAdb adb=new LocalAdb(this)){
   JSONArray selected=new JSONArray(ids);if(selected.length()>50)throw new IOException("Too many selected apps");
   for(int i=0;i<selected.length();i++){
    String id=selected.getString(i);JSONObject a=catalogue.get(id);if(a==null)throw new IOException("Unknown app selection");
    JSONObject r=new JSONObject().put("id",id).put("name",a.getString("name"));
    if(stop){r.put("status","skipped");results.put(r);continue;}
    PackageInfo existing=installed(id);
    if(existing!=null&&code(existing)>=a.getLong("versionCode")){r.put("status","kept");results.put(r);continue;}
    File folder=new File(getExternalFilesDir(null),"setup-"+i);folder.mkdirs();String session=null;
    try{
     JSONArray parts=a.optJSONArray("splits");if(parts==null)parts=new JSONArray().put(new JSONObject().put("file",a.getString("file")).put("url",a.optString("url","")));
     List<File> files=new ArrayList<>();long total=0;android.content.pm.Signature[] signer=null;
     for(int n=0;n<parts.length();n++){
      JSONObject part=parts.getJSONObject(n);String url=part.optString("url");if(url.isEmpty())url=BASE+part.getString("file");
      publish("installing","Downloading "+a.getString("name")+" · file "+(n+1)+"/"+parts.length(),results,i,selected.length());
      File f=new File(folder,n==0?"base.apk":"split_"+n+".apk");HttpURLConnection c=connection(url);long expected=c.getContentLengthLong(),got=0;
      try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(f)){byte[] buf=new byte[65536];int count;long last=0;
       while((count=in.read(buf))!=-1){out.write(buf,0,count);got+=count;if(getExternalFilesDir(null).getUsableSpace()<100000000)throw new IOException("Not enough free storage");
        if(got-last>1048576){publish("installing","Downloading "+a.getString("name")+" · "+(got/1048576)+" MB"+(expected>0?" / "+(expected/1048576)+" MB":""),results,i,selected.length());last=got;}}
      }finally{c.disconnect();}
      if(got==0||(expected>0&&got!=expected))throw new IOException("Incomplete download");
      PackageInfo archive=getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNATURES);
      if(archive==null||!id.equals(archive.packageName)||code(archive)!=a.getLong("versionCode"))throw new IOException("APK package/version does not match the library");
      if(archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("Requires a newer Android version");
      if(n==0)signer=archive.signatures;
      if(signer==null||signer.length==0||!Arrays.equals(signer,archive.signatures))throw new IOException("APK signature check failed");
      if(existing!=null&&!Arrays.equals(existing.signatures,archive.signatures))throw new IOException("Needs a fresh install: signature differs. Kept your existing app and login.");
      files.add(f);total+=f.length();
     }
     publish("installing","Installing "+a.getString("name"),results,i,selected.length());
     String made=command(adb,"pm install-create -r -S "+total);java.util.regex.Matcher match=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(made);
     if(!match.find())throw new IOException(made);session=match.group(1);
     for(File f:files){String written=command(adb,"cat '"+f.getAbsolutePath()+"' | pm install-write -S "+f.length()+" "+session+" "+f.getName()+" -");if(!written.contains("Success"))throw new IOException(written);}
     String committed=command(adb,"pm install-commit "+session);session=null;if(!committed.contains("Success"))throw new IOException(committed);
     PackageInfo after=installed(id);if(after==null||code(after)!=a.getLong("versionCode"))throw new IOException("Installation could not be verified");
     r.put("status","ready");
    }catch(Exception e){r.put("status","attention").put("message",String.valueOf(e.getMessage()));}
    finally{if(session!=null)try{command(adb,"pm install-abandon "+session);}catch(Exception ignored){}File[] fs=folder.listFiles();if(fs!=null)for(File f:fs)f.delete();folder.delete();}
    results.put(r);getSharedPreferences("a5_setup",0).edit().putString("results",results.toString()).commit();
   }
   publish("installed","App setup finished. Check any apps needing attention.",results,selected.length(),selected.length());
  }catch(Exception e){fail(e);}finally{busy.set(false);}
 },"A5SetupInstall").start();}
 private class SetupBridge{
  @JavascriptInterface public void openDeveloperOptions(){runOnUiThread(()->{try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}});}
  @JavascriptInterface public String status(){return snapshot;}
  @JavascriptInterface public long freeMiB(){return new android.os.StatFs(android.os.Environment.getDataDirectory().getAbsolutePath()).getAvailableBytes()/(1024*1024);}
  @JavascriptInterface public void retryScan(){scan();}
  @JavascriptInterface public void installSelected(String ids){install(ids);}
  @JavascriptInterface public void stopAfterApp(){stop=true;}
  @JavascriptInterface public void optimise(String json){if(!busy.compareAndSet(false,true))return;new Thread(()->{
   try{JSONObject choices=new JSONObject(json);new ProjectorOptimisation(A5SetupActivity.this).applySetup(choices,(message,done,total)->{try{publish("optimising",message,null,done,total);}catch(Exception ignored){}});
    String cleanupMessage="";
    if(choices.optBoolean("closeBackgroundOnExit",false)) {
      publish("optimising","Closing eligible background apps…",null,99,100);
      try (ProjectorCare cleanup=new ProjectorCare(A5SetupActivity.this)) { cleanupMessage=" "+cleanup.freeRam(); }
      catch(Exception cleanupError) { cleanupMessage=" Background cleanup could not finish; your optimisation settings are saved. You can retry from Tools."; }
    }
    getSharedPreferences("a5_setup",0).edit().putString("optimise",json).putBoolean("complete",true).commit();publish("optimised","Your optimisation choices are saved."+cleanupMessage,null,1,1);
   }catch(Exception e){fail(e);}finally{busy.set(false);}
  },"A5SetupOptimise").start();}
  @JavascriptInterface public void markComplete(){LauncherUpdateSetup.ensure(A5SetupActivity.this);if(!busy.get())getSharedPreferences("a5_setup",0).edit().putBoolean("complete",true).commit();}
  @JavascriptInterface public void endCard(){getSharedPreferences("a5_setup",0).edit().putBoolean("complete",true).commit();runOnUiThread(()->web.loadUrl("file:///android_asset/setup/endcard.html"));}
  @JavascriptInterface public String username(){return getIntent().getStringExtra("setup_username")==null?"":getIntent().getStringExtra("setup_username");}
  @JavascriptInterface public void openDestination(String destination){if(busy.get())return;getSharedPreferences("a5_setup",0).edit().putBoolean("complete",true).commit();runOnUiThread(()->{Intent intent=new Intent(A5SetupActivity.this,MainActivity.class);intent.putExtra("setup_destination","entertainment".equals(destination)?"entertainment":"home");intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(intent);finish();});}
 }
 @Override public void onBackPressed(){if(busy.get())return;web.evaluateJavascript("window.pgSetupBack?.()",null);}
}
