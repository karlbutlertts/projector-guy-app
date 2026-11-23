package com.projectorguy.app;
import android.app.*;import android.os.*;import android.content.*;import android.provider.Settings;import android.view.*;import android.widget.*;import android.accessibilityservice.*;import android.view.accessibility.*;import java.util.*;

public final class FactoryTranslationActivity extends Activity {
 private Switch toggle;private TextView status;private boolean updating;
 private boolean enabled(){return FactoryTranslationSetup.enabled(this);}
 @Override public void onCreate(Bundle b){super.onCreate(b);FactoryTranslationSetup.ensureDefault(this);getWindow().getDecorView().setSystemUiVisibility(5894);LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(40,32,40,24);page.setBackgroundColor(0xff0b1527);setContentView(page);
  TextView title=new TextView(this);title.setText("English factory menus");title.setTextSize(30);title.setTextColor(0xfff1f7ff);page.addView(title);
  TextView copy=new TextView(this);copy.setText("Offline English labels for supported Newlink menus. Original remote and touch controls keep working. Unknown labels stay unchanged. This translates controls; it does not run tests or repair the focus motor.");copy.setTextColor(0xffb5c8df);copy.setTextSize(17);page.addView(copy);
  toggle=new Switch(this);toggle.setText("English factory menus");toggle.setTextColor(0xfff1f7ff);toggle.setTextSize(22);toggle.setPadding(12,28,12,28);toggle.setShowText(true);toggle.setTextOn("ON");toggle.setTextOff("OFF");page.addView(toggle);
  status=new TextView(this);status.setTextColor(0xffa9e8f5);status.setTextSize(16);page.addView(status);
  toggle.setOnCheckedChangeListener((v,on)->{if(!updating)setEnabled(on);});
  LinearLayout buttons=new LinearLayout(this);page.addView(buttons);
  Button open=new Button(this);open.setText("Open Newlink tools");buttons.addView(open);open.setOnClickListener(v->{try{startActivity(new Intent().setClassName(FactoryMenuEnglishService.TARGET,FactoryMenuEnglishService.TARGET+".ToolsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}catch(Exception e){status.setText("Newlink tools could not be opened.");}});
  Button settings=new Button(this);settings.setText("Accessibility settings");buttons.addView(settings);settings.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});
  Button back=new Button(this);back.setText("Back");buttons.addView(back);back.setOnClickListener(v->finish());toggle.requestFocus();
 }
 @Override public void onResume(){super.onResume();refresh();}
 private void refresh(){updating=true;boolean desired=FactoryTranslationSetup.wanted(this);toggle.setChecked(desired);updating=false;status.setText(!desired?"OFF · The original Chinese labels are shown.":enabled()?"Automatic English is ON for supported Newlink menus.":"English is selected automatically. If labels stay Chinese, enable Projector Guy English factory menus in Accessibility settings.");}
 private void setEnabled(boolean on){FactoryTranslationSetup.remember(this,on);toggle.setEnabled(false);status.setText(on?"Enabling English labels…":"Restoring original labels…");new Thread(()->{
  String problem=null;
  try{FactoryTranslationSetup.apply(this,on);
  }catch(Exception e){problem=e.getMessage();}
  final String failure=problem;runOnUiThread(()->new Handler(Looper.getMainLooper()).postDelayed(()->{if(isFinishing()||isDestroyed())return;toggle.setEnabled(true);if(failure==null){updating=true;toggle.setChecked(on);updating=false;status.setText(on?"ON · English labels appear only in supported Newlink factory menus.":"OFF · The original Chinese labels are shown.");}else{refresh();status.setText("Open Accessibility settings and enable Projector Guy English factory menus. The projector connection was unavailable; existing services were kept.");}},650));
 },"FactoryTranslationSwitch").start();}
}
