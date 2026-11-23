package com.projectorguy.app;
import android.app.*;import android.os.*;import android.webkit.*;import android.view.*;import org.json.*;import java.util.concurrent.atomic.AtomicBoolean;

/** Only bundled local UI receives this bridge; no web content or web-supplied commands. */
public final class ProjectorCareActivity extends Activity {
 private WebView web;private final AtomicBoolean busy=new AtomicBoolean();private volatile String snapshot="{\"busy\":true,\"message\":\"Reading this projector…\"}";private volatile boolean destroyed;
 private ProjectorCare care;
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(5894);care=new ProjectorCare(this);web=new WebView(this);web.setBackgroundColor(0xff0c1424);setContentView(web);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDefaultTextEncodingName("UTF-8");web.getSettings().setAllowFileAccessFromFileURLs(false);web.getSettings().setAllowUniversalAccessFromFileURLs(false);web.addJavascriptInterface(new CareBridge(),"ProjectorCare");web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,String u){return !u.equals("file:///android_asset/care/index.html");}@Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r){return !r.getUrl().toString().equals("file:///android_asset/care/index.html");}});web.loadUrl("file:///android_asset/care/index.html");work("refresh",false,false);}
 private void work(String action,boolean installers,boolean cache){if(destroyed||!busy.compareAndSet(false,true))return;new Thread(()->{
  try{JSONObject j=new JSONObject(snapshot);j.put("busy",true).put("message",action.equals("ram")?"Closing eligible background apps…":action.equals("clean")?"Clearing selected temporary files…":"Reading this projector…");snapshot=j.toString();
   String message;
   if(action.equals("ram"))message=care.freeRam();
   else if(action.equals("clean")){JSONObject result=care.clean(installers,cache);message=android.text.format.Formatter.formatFileSize(this,result.getLong("removedBytes"))+" of temporary files removed. "+result.getInt("skippedRunning")+" running apps skipped. Logins and settings kept.";}
   else message="Refreshed just now. RAM is available memory, not an amount you need to maximise.";
   j=care.scan();j.put("busy",false).put("message",message);snapshot=j.toString();
  }catch(Exception e){try{JSONObject j=care.readings();j.put("busy",false).put("error",true).put("message",e.getMessage()==null?"Cleanup could not finish. No app data was cleared.":e.getMessage());snapshot=j.toString();}catch(Exception ignored){snapshot="{\"busy\":false,\"error\":true,\"message\":\"Unable to read this projector.\"}";}}
  finally{care.close();busy.set(false);}
 },"ProjectorCare").start();}
 private class CareBridge{
  @JavascriptInterface public String status(){return snapshot;}
  @JavascriptInterface public void refresh(){work("refresh",false,false);}
  @JavascriptInterface public void freeRam(){work("ram",false,false);}
  @JavascriptInterface public void clean(boolean installers,boolean cache){if(installers||cache)work("clean",installers,cache);}
  @JavascriptInterface public void back(){runOnUiThread(()->{if(!busy.get())finish();});}
 }
 @Override public void onBackPressed(){if(busy.get())return;web.evaluateJavascript("window.careBack()",null);}
 @Override protected void onDestroy(){destroyed=true;if(web!=null){web.removeJavascriptInterface("ProjectorCare");web.destroy();}super.onDestroy();}
}
