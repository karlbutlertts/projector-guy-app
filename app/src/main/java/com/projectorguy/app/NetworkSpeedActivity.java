package com.projectorguy.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.net.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.*;

/** Remote-friendly speed check; runs only while the screen is open. */
public class NetworkSpeedActivity extends Activity {
 private TextView download,latency,jitter,heading,detail,network,lastResult;
 private ProgressBar progress;
 private Button start,stop;
 private volatile NetworkSpeedProbe probe;
 private volatile int generation;
 private volatile boolean running;
 private final int bg=0xff0b1527,card=0xff17283f,ink=0xfff1f7ff,muted=0xffb5c8df,accent=0xffa9e8f5;
 private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
 private GradientDrawable shape(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
 private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextSize(17);b.setAllCaps(false);b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{}},new int[]{bg,ink}));StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},shape(accent,ink));states.addState(new int[]{},shape(card,0xff58718b));b.setBackground(states);b.setPadding(dp(24),dp(12),dp(24),dp(12));return b;}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().getDecorView().setSystemUiVisibility(5894);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
  LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(36),dp(24),dp(36),dp(24));scroll.addView(page);setContentView(scroll);
  TextView brand=text("PROJECTOR GUY  /  TOOLS",12,accent);page.addView(brand);
  TextView title=text("Network speed checker",30,ink);title.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2);titleParams.topMargin=dp(10);page.addView(title,titleParams);
  network=text(connectionLabel(),14,muted);page.addView(network);
  LinearLayout metrics=new LinearLayout(this);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(22);page.addView(metrics,mp);
  download=metric(metrics,"DOWNLOAD","—","Mbps");latency=metric(metrics,"HTTPS LATENCY","—","ms");jitter=metric(metrics,"LATENCY VARIATION","—","ms jitter");
  heading=text("Ready when you are",21,ink);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(20);page.addView(heading,hp);
  detail=text("Test this projector’s connection, not your phone’s. Uses up to 50 MB through Cloudflare. Pause streaming first for a clearer result.",15,muted);page.addView(detail);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(accent));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(8));pp.topMargin=dp(16);pp.bottomMargin=dp(16);page.addView(progress,pp);
  LinearLayout buttons=new LinearLayout(this);page.addView(buttons);
  start=button("Start test");buttons.addView(start);start.setOnClickListener(v->begin());
  stop=button("Stop test");LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=dp(14);buttons.addView(stop,bp);stop.setVisibility(View.GONE);stop.setOnClickListener(v->cancelTest(true));
  Button settings=button("Network settings");LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.leftMargin=dp(14);buttons.addView(settings,np);settings.setOnClickListener(v->{cancelTest(false);try{startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));}catch(Exception e){try{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}catch(Exception ignored){}}});
  Button back=button("Back");LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(-2,-2);backParams.leftMargin=dp(14);buttons.addView(back,backParams);back.setOnClickListener(v->finish());
  lastResult=text("",13,muted);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);page.addView(lastResult,lp);showSaved();start.requestFocus();
 }
 private TextView metric(LinearLayout row,String label,String value,String unit){
  LinearLayout tile=new LinearLayout(this);tile.setOrientation(1);tile.setPadding(dp(20),dp(14),dp(20),dp(14));tile.setBackground(shape(card,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(12);row.addView(tile,p);tile.addView(text(label,11,muted));TextView n=text(value,36,ink);n.setTypeface(null,Typeface.BOLD);tile.addView(n);tile.addView(text(unit,13,accent));return n;
 }
 private String connectionLabel(){
  try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(android.os.Build.VERSION.SDK_INT<23){NetworkInfo old=cm.getActiveNetworkInfo();return old!=null&&old.isConnected()?old.getTypeName()+" connected · measured on this projector":"No active network";}Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c==null)return "No active network — connect Wi-Fi or Ethernet to test";
   String transport=c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Mobile data":"Network";
   return transport+" connected · measured on this projector";
  }catch(Exception e){return "Measured on this projector";}
 }
 private boolean connected(){try{ConnectivityManager c=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);return android.os.Build.VERSION.SDK_INT>=23?c.getActiveNetwork()!=null:c.getActiveNetworkInfo()!=null&&c.getActiveNetworkInfo().isConnected();}catch(Exception e){return true;}}
 private void showSaved(){
  try{String raw=getSharedPreferences("network_speed",0).getString("last",null);if(raw==null)return;JSONObject j=new JSONObject(raw);lastResult.setText(String.format(Locale.UK,"Last completed test: %.1f Mbps · %.0f ms HTTPS · %s",j.getDouble("mbps"),j.getDouble("latency"),DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(j.getLong("time")))));}catch(Exception ignored){}
 }
 private void begin(){if(running)return;network.setText(connectionLabel());if(!connected()){heading.setText("No network connection");detail.setText("Open Network settings, connect Wi-Fi or Ethernet, then try again. Your last completed result is kept below.");return;}
  running=true;int token=++generation;NetworkSpeedProbe current=new NetworkSpeedProbe();probe=current;start.setEnabled(false);stop.setVisibility(View.VISIBLE);stop.requestFocus();progress.setProgress(0);download.setText("—");latency.setText("—");jitter.setText("—");heading.setText("Checking your connection…");detail.setText("Measuring HTTPS latency, then download speed. You can stop at any time.");
  new Thread(()->{
   try{NetworkSpeedProbe.Result result=current.run((phase,bytes,mbps,percent)->runOnUiThread(()->{if(token!=generation||isFinishing()||isDestroyed())return;heading.setText(phase);progress.setProgress(percent);if(bytes>0){download.setText(String.format(Locale.UK,"%.1f",mbps));detail.setText(String.format(Locale.UK,"%.1f MB received · testing this projector’s internet connection",bytes/1_000_000.0));}}));
    runOnUiThread(()->{if(token!=generation||isFinishing()||isDestroyed())return;finished();progress.setProgress(100);download.setText(String.format(Locale.UK,"%.1f",result.mbps));latency.setText(String.format(Locale.UK,"%.0f",result.latencyMs));jitter.setText(String.format(Locale.UK,"%.0f",result.jitterMs));heading.setText(result.mbps>=20?"Download headroom for 4K streaming":result.mbps>=5?"Download headroom for HD streaming":"Connection may struggle with HD streaming");detail.setText(String.format(Locale.UK,"%.1f MB tested in %.1f seconds. Speed guide only: app support, Wi-Fi stability and other devices also affect playback. HTTPS latency includes server response time; this is not a gaming ping test.",result.bytes/1_000_000.0,result.durationMs/1000.0));
     try{JSONObject j=new JSONObject().put("mbps",result.mbps).put("latency",result.latencyMs).put("jitter",result.jitterMs).put("time",System.currentTimeMillis());getSharedPreferences("network_speed",0).edit().putString("last",j.toString()).apply();showSaved();}catch(Exception ignored){}
    });
   }catch(Exception e){runOnUiThread(()->{if(token!=generation||isFinishing()||isDestroyed())return;finished();heading.setText("Test couldn’t finish");download.setText("—");latency.setText("—");jitter.setText("—");progress.setProgress(0);detail.setText("Check Wi-Fi, captive-portal sign-in or VPN, then try again. "+(e.getMessage()==null?"":e.getMessage()));});}
  },"ProjectorNetworkTest").start();
 }
 private void finished(){running=false;probe=null;start.setEnabled(true);start.setText("Test again");stop.setVisibility(View.GONE);start.requestFocus();}
 private void cancelTest(boolean show){if(!running)return;generation++;NetworkSpeedProbe p=probe;if(p!=null)p.cancel();finished();if(show){heading.setText("Test stopped");detail.setText("No incomplete result was saved. Start again whenever you’re ready.");download.setText("—");latency.setText("—");jitter.setText("—");progress.setProgress(0);}}
 @Override protected void onStop(){cancelTest(true);super.onStop();}
 @Override protected void onDestroy(){cancelTest(false);super.onDestroy();}
}
