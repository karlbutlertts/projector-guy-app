package com.projectorguy.app;

import android.content.Context;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Uses the 360's built-in remote server. No ADB, accessibility or background app server. */
final class ProjectorRemote360 {
 private static final Map<String,String> ACTIONS;
 static {
  Map<String,String> actions=new HashMap<>();
  for(String a:new String[]{"up","down","left","right","home","back","menu","power","mute"})actions.put(a,a);
  actions.put("ok","confirm");actions.put("volumeUp","volume-up");actions.put("volumeDown","volume-down");
  ACTIONS=Collections.unmodifiableMap(actions);
 }
 static synchronized String send(Context context,String command) {
  String action=ACTIONS.get(command);
  boolean check="checkAccessibility".equals(command);
  if(action==null && !check)return "unsupported";
  HttpURLConnection connection=null;
  try {
   connection=(HttpURLConnection)new URL("http://127.0.0.1:8086/"+(check?"":"app/remote")).openConnection();
   connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
   if(!check) {
    byte[] data=("action="+action).getBytes(StandardCharsets.UTF_8);
    connection.setRequestMethod("POST");connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");connection.setFixedLengthStreamingMode(data.length);
    try(OutputStream out=connection.getOutputStream()){out.write(data);}
   }
   if(connection.getResponseCode()!=200)return "offline";
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();
   try(InputStream in=connection.getInputStream()) {
    byte[] buffer=new byte[2048];int n;
    while((n=in.read(buffer))!=-1){if(bytes.size()+n>65536)return "fail";bytes.write(buffer,0,n);}
   }
   String response=new String(bytes.toByteArray(),StandardCharsets.UTF_8).trim();
   return (check?response.contains("handleRemoteControl"):"successful".equals(response))?"ok":"fail";
  }catch(Exception e){Log.w("ProjectorRemote360","Built-in remote unavailable",e);return "offline";}
  finally{if(connection!=null)connection.disconnect();}
 }
}
