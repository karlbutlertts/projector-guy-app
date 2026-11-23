package com.projectorguy.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.*;

/** Offline, non-interactive labels over the unmodified Newlink menu. No motor actions. */
public final class FactoryMenuEnglishService extends AccessibilityService {
 static final String TARGET="com.newlink.factorymenu";
 static final Map<String,String> WORDS=new LinkedHashMap<>();
 static {
  String[][] pairs={{"调试工具","Debug tools"},{"自动调焦回退步数","AF back-off steps"},{"adb功能","ADB debugging"},{"log功能","Logging"},{"工厂菜单","Factory menu"},{"普通老化","Standard burn-in"},{"开机老化","Power-on burn-in"},{"镜头回缩","Retract lens"},{"摄像预览","Camera preview"},{"一键校准","One-click calibration"},{"激活清除","Clear activation"},{"MAC信息","MAC information"},{"恢复出厂","Factory reset"},{"产测工具","Production tests"},{"自动测试","Automatic test"},{"手动对焦","Manual focus"},{"开机自动化测试","Power-on auto test"},{"关","OFF"},{"开","ON"},{"机器型号","Device model"},{"系统版本","System version"},{"DDR容量(可用/总共)","RAM (free / total)"},{"EMMC容量(可用/总共)","Storage (free / total)"},{"优酷UUID","Youku UUID"},{"系统温度","System temperature"},{"环境温度","Ambient temperature"},{"屏参数","Panel parameters"},{"当前网络速度","Current network speed"},{"OTA地址","OTA address"},{"无线MAC地址","Wi-Fi MAC address"},{"有线MAC地址","Ethernet MAC address"},{"产品信息","Product information"},{"风扇设置","Fan settings"},{"硬件信息","Hardware information"},{"ini信息","INI information"},{"取消","Cancel"},{"确定","Confirm"},{"工厂菜单主页","Factory menu home"}};
  for(String[] p:pairs)WORDS.put(p[0],p[1]);
 }
 private final Handler handler=new Handler(Looper.getMainLooper());
 private WindowManager windows;private android.widget.FrameLayout overlay;private boolean attached;private String fingerprint="";
 private final Runnable refresh=()->drawLabels();
 private final Runnable poll=new Runnable(){public void run(){drawLabels();handler.postDelayed(this,attached?350:1800);}};
 @Override protected void onServiceConnected(){super.onServiceConnected();windows=(WindowManager)getSystemService(WINDOW_SERVICE);handler.post(poll);}
 @Override public void onAccessibilityEvent(AccessibilityEvent event){handler.removeCallbacks(refresh);handler.postDelayed(refresh,60);}
 @Override public void onInterrupt(){hide();}
 @Override public boolean onUnbind(Intent intent){handler.removeCallbacksAndMessages(null);hide();return super.onUnbind(intent);}
 @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);hide();super.onDestroy();}
 private static final class Label {Rect r;String text;boolean selected,title;Label(Rect r,String text,boolean selected,boolean title){this.r=r;this.text=text;this.selected=selected;this.title=title;}}
 private void visit(AccessibilityNodeInfo node,List<Label> labels,int depth){
  if(node==null||depth>24)return;
  CharSequence raw=node.getText();String english=raw==null?null:WORDS.get(raw.toString().trim());
  if(english!=null&&node.isVisibleToUser()){
   Rect r=new Rect();node.getBoundsInScreen(r);if(r.width()>0&&r.height()>0){boolean selected=node.isFocused()||node.isSelected();AccessibilityNodeInfo p=node.getParent();
    for(int i=0;p!=null&&i<3;i++){selected|=p.isFocused()||p.isSelected();AccessibilityNodeInfo next=p.getParent();p.recycle();p=next;}if(p!=null)p.recycle();
    String id=node.getViewIdResourceName();
    if(id!=null&&id.endsWith(":id/textview_setting")) {
     AccessibilityNodeInfo row=node.getParent();if(row!=null){Rect bounds=new Rect();row.getBoundsInScreen(bounds);if(bounds.width()>150)r.right=Math.max(r.right,bounds.right-82);row.recycle();}
    }
    boolean title=id!=null&&id.endsWith(":id/title");
    if(!title&&r.height()>30){r.top+=7;r.bottom-=7;}
    labels.add(new Label(r,english,selected,title));
   }
  }
  for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo c=node.getChild(i);if(c!=null){visit(c,labels,depth+1);c.recycle();}}
 }
 private void drawLabels(){
  AccessibilityNodeInfo root=getRootInActiveWindow();
  if(root==null){hide();return;}
  List<Label> labels=new ArrayList<>();
  try{if(!TARGET.contentEquals(root.getPackageName()==null?"":root.getPackageName())){hide();return;}visit(root,labels,0);}finally{root.recycle();}
  if(labels.isEmpty()){hide();return;}
  StringBuilder key=new StringBuilder();for(Label l:labels)key.append(l.r).append(l.text).append(l.selected);
  if(key.toString().equals(fingerprint)&&attached)return;fingerprint=key.toString();
  if(overlay==null){overlay=new FrameLayout(this);overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
  overlay.removeAllViews();
  for(Label label:labels){
   TextView text=new TextView(this);text.setText(label.text);text.setGravity(Gravity.CENTER_VERTICAL|(label.title?Gravity.CENTER_HORIZONTAL:Gravity.LEFT));text.setTextColor(label.selected?Color.WHITE:Color.BLACK);text.setBackgroundColor(label.selected?0xff0875ff:Color.WHITE);text.setPadding(1,0,1,0);text.setMaxLines(1);text.setTextSize(label.title?22:12);
   if(Build.VERSION.SDK_INT>=26&&label.r.width()>40)text.setAutoSizeTextTypeUniformWithConfiguration(label.title?14:10,label.title?24:14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
   Rect r=label.r;FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(r.width()+2,r.height()+2);p.leftMargin=Math.max(0,r.left-1);p.topMargin=Math.max(0,r.top-1);overlay.addView(text,p);
  }
  if(!attached){WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("Projector Guy English factory labels");try{windows.addView(overlay,p);attached=true;}catch(Exception e){fingerprint="";}}
 }
 private void hide(){fingerprint="";if(attached&&windows!=null){try{windows.removeViewImmediate(overlay);}catch(Exception ignored){}attached=false;}}
}
