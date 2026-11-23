package com.projectorguy.app;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, device-local housekeeping. Never clears app data or force-stops a package. */
final class ProjectorCare implements AutoCloseable {
 private LocalAdb adb;
 static final long MIB=1048576L;
 static final String[] APPS={"com.netflix.mediaclient","com.google.android.youtube.tv","com.disney.disneyplus","com.amazon.amazonvideo.livingroom","air.ITVMobilePlayer","tv.stv.android.player","com.channel4.ondemand","com.channel5.my5","com.five.demand","org.beebplayer.android","com.bskyb.nowtv.beta","io.github.lozza.tellygrid","io.github.lozza.tellygrid.speedtest","com.linkdrop.tvapp","com.roblox.client"};
 final Context context;
 ProjectorCare(Context c){context=c.getApplicationContext();}
 static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
 synchronized String root(String cmd)throws Exception {
  if(adb==null)adb=new LocalAdb(context);
  String output=adb.shell("su 0 sh -c "+quote(cmd)+"; printf '\\nCARE_EXIT=%s\\n' $?");
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("CARE_EXIT=(\\d+)").matcher(output);
  if(!m.find()||!"0".equals(m.group(1)))throw new IOException("Projector cleanup command could not finish. Your app data is kept.");
  return output.substring(0,m.start()).trim();
 }
 @Override public synchronized void close(){if(adb!=null)try{adb.close();}catch(Exception ignored){}adb=null;}
 Set<String> protectedPackages(){
  Set<String> result=new HashSet<>(Arrays.asList(context.getPackageName(),"com.projectorguy.app","com.projectorguy.app.localtest","com.android.systemui","com.android.settings","com.android.tv.settings","com.esaba.downloader"));
  PackageManager pm=context.getPackageManager();
  Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
  for(ResolveInfo r:pm.queryIntentActivities(home,PackageManager.MATCH_DEFAULT_ONLY))if(r.activityInfo!=null)result.add(r.activityInfo.packageName);
  String enabled=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
  if(enabled!=null)for(String part:enabled.split(":")){ComponentName c=ComponentName.unflattenFromString(part);if(c!=null)result.add(c.getPackageName());}
  return result;
 }
 List<String> eligible(){
  List<String> out=new ArrayList<>();Set<String> protectedSet=protectedPackages();
  for(String pkg:APPS)try{ApplicationInfo a=context.getPackageManager().getApplicationInfo(pkg,0);if(a.uid>=10000&&(a.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0&&!protectedSet.contains(pkg))out.add(pkg);}catch(PackageManager.NameNotFoundException ignored){}
  return out;
 }
 static boolean hasProcess(String ps,String pkg){for(String line:ps.split("\\n")){String n=line.trim();if(n.equals(pkg)||n.startsWith(pkg+":"))return true;}return false;}
 String processes()throws Exception{return root("ps -A -o NAME");}
 JSONObject readings()throws Exception{
  ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);
  StatFs f=new StatFs(Environment.getDataDirectory().getAbsolutePath());
  return new JSONObject().put("ramAvailable",m.availMem).put("ramTotal",m.totalMem).put("lowMemory",m.lowMemory).put("storageAvailable",f.getAvailableBytes()).put("storageTotal",Environment.getDataDirectory().getTotalSpace());
 }
 /** Only app-owned APKs whose package/version is already installed; never an active install. */
 List<File> completedInstallers(){
  List<File> found=new ArrayList<>();walkInstallers(new File(context.getCacheDir(),"updates"),found,0);
  File external=context.getExternalFilesDir(null);if(external!=null){File[] dirs=external.listFiles();if(dirs!=null)for(File d:dirs)if(d.getName().matches("setup-[0-9]+"))walkInstallers(d,found,0);}
  return found;
 }
 private void walkInstallers(File dir,List<File> out,int depth){
  if(depth>3||Files.isSymbolicLink(dir.toPath()))return;File[] files=dir.listFiles();if(files==null)return;
  for(File f:files){if(Files.isSymbolicLink(f.toPath()))continue;if(f.isDirectory()){walkInstallers(f,out,depth+1);continue;}if(!f.getName().endsWith(".apk")||System.currentTimeMillis()-f.lastModified()<3600000L)continue;
   try{PackageInfo apk=context.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(),0);if(apk==null)continue;PackageInfo installed=context.getPackageManager().getPackageInfo(apk.packageName,0);long av=Build.VERSION.SDK_INT>=28?apk.getLongVersionCode():apk.versionCode,iv=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;if(iv>=av)out.add(f);}catch(Exception ignored){}
  }
 }
 static long bytes(List<File> files){long n=0;for(File f:files)n+=f.length();return n;}
 /** Scan/delete ordinary cache files only, skipping all packages with any running process. */
 String cacheCommand(String pkg,boolean delete){
  if(!Arrays.asList(APPS).contains(pkg)||!eligible().contains(pkg))throw new IllegalArgumentException("Package is not eligible");
  String dir="/data/data/"+pkg+"/cache";
  return filesCommand(dir,delete);
 }
 static String filesCommand(String dir,boolean delete){
  String q=quote(dir);
  return "d="+q+"; if [ -d \"$d\" ] && [ ! -L \"$d\" ] && [ \"$(readlink -f \"$d\")\" = \"$d\" ]; then "+
   (delete?"find \"$d\" -xdev -type f -delete":"du -sk \"$d\"")+"; fi";
 }
 long cacheBytes(List<String> eligible,String ps)throws Exception{
  long total=0;for(String pkg:eligible){if(hasProcess(ps,pkg))continue;String output=root(cacheCommand(pkg,false));if(!output.isEmpty())try{total+=Long.parseLong(output.split("\\s+")[0])*1024;}catch(NumberFormatException ignored){}}
  return total;
 }
 JSONObject scan()throws Exception{
  JSONObject j=readings();j.put("installerBytes",bytes(completedInstallers()));
  try{if(!root("id").contains("uid=0"))throw new IOException("Root unavailable");j.put("cacheBytes",cacheBytes(eligible(),processes())).put("cacheSupported",true);}catch(Exception e){j.put("cacheBytes",0).put("cacheSupported",false).put("cacheNote","App-cache cleanup is unavailable on this unit. Your app data is kept.");}
  return j;
 }
 interface Progress { void update(String message,int done,int total); }
 String freeRam()throws Exception{return freeRam((m,d,t)->{});}
 String freeRam(Progress progress)throws Exception{
  if(!Build.DEVICE.startsWith("pioneer5"))throw new IOException("Background cleanup is supported on A5 Pro only");
  if(!root("id").contains("uid=0"))throw new IOException("Root access is unavailable");
  String before=processes();List<String> candidates=eligible();int attempted=0;
  int index=0;progress.update("Checking background apps…",0,candidates.size());
  for(String pkg:candidates){if(hasProcess(before,pkg)){root("am kill --user 0 "+quote(pkg));attempted++;}progress.update("Checking background apps",++index,candidates.size());}
  Thread.sleep(600);String after=processes();int closed=0;
  for(String pkg:candidates)if(hasProcess(before,pkg)&&!hasProcess(after,pkg))closed++;
  return closed+" app process"+(closed==1?"":"es")+" closed. "+(attempted-closed)+" app"+(attempted-closed==1?" was":"s were")+" kept by Android. RAM availability changes as apps run.";
 }
 JSONObject clean(boolean installers,boolean cache)throws Exception{return clean(installers,cache,(m,d,t)->{});}
 JSONObject clean(boolean installers,boolean cache,Progress progress)throws Exception{
  long before=new StatFs(Environment.getDataDirectory().getAbsolutePath()).getAvailableBytes(),removed=0;int skipped=0;
  List<File> files=installers?completedInstallers():Collections.emptyList();List<String> packages=cache?eligible():Collections.emptyList();int done=0,total=Math.max(1,files.size()+packages.size());progress.update("Checking temporary files…",0,total);
  if(installers){for(File f:files){long n=f.length();if(!Files.isSymbolicLink(f.toPath())&&f.delete())removed+=n;progress.update("Removing completed downloads",++done,total);}}
  if(cache){String ps=processes();for(String pkg:packages){if(hasProcess(processes(),pkg)){skipped++;progress.update("Skipping running app",++done,total);continue;}long n=cacheBytes(Collections.singletonList(pkg),ps);root(cacheCommand(pkg,true));long left=cacheBytes(Collections.singletonList(pkg),processes());removed+=Math.max(0,n-left);progress.update("Clearing inactive app cache",++done,total);}}
  long after=new StatFs(Environment.getDataDirectory().getAbsolutePath()).getAvailableBytes();
  return new JSONObject().put("removedBytes",removed).put("freeSpaceChange",after-before).put("skippedRunning",skipped);
 }
}
