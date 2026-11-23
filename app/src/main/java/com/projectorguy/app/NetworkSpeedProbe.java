package com.projectorguy.app;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded HTTPS download test. No root, disk payloads, uploads or device identifiers. */
final class NetworkSpeedProbe {
 static final long MAX_BYTES=50_000_000L;
 static final long DOWNLOAD_NANOS=12_000_000_000L;
 interface Connections { HttpURLConnection open(URL url) throws IOException; }
 interface Progress { void update(String phase,long bytes,double mbps,int percent); }
 static final class Result {
  final double mbps,latencyMs,jitterMs; final long bytes,durationMs;
  Result(double speed,double latency,double jitter,long count,long duration){mbps=speed;latencyMs=latency;jitterMs=jitter;bytes=count;durationMs=duration;}
 }
 private final Connections factory;
 private final Set<HttpURLConnection> active=Collections.synchronizedSet(new HashSet<>());
 private final AtomicBoolean cancelled=new AtomicBoolean();
 NetworkSpeedProbe(){this(url->(HttpURLConnection)url.openConnection());}
 NetworkSpeedProbe(Connections connections){factory=connections;}
 void cancel(){cancelled.set(true);HttpURLConnection[] connections;synchronized(active){connections=active.toArray(new HttpURLConnection[0]);}if(connections.length>0)new Thread(()->{for(HttpURLConnection c:connections)c.disconnect();},"SpeedTestCancel").start();}
 private void check() throws InterruptedIOException {if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Test stopped");}
 private HttpURLConnection connect(long bytes) throws IOException {
  check();HttpURLConnection c=factory.open(new URL("https://speed.cloudflare.com/__down?bytes="+bytes+"&nonce="+System.nanoTime()));
  c.setConnectTimeout(4500);c.setReadTimeout(2500);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
  c.setRequestProperty("Cache-Control","no-store");c.setRequestProperty("Accept-Encoding","identity");
  active.add(c);
  try {check();int status=c.getResponseCode();if(status!=200)throw new IOException("Speed-test server returned HTTP "+status);
   String type=c.getContentType();if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))throw new IOException("The connection returned a sign-in page instead of test data");
   return c;
  }catch(IOException e){close(c);throw e;}
 }
 private void close(HttpURLConnection c){active.remove(c);c.disconnect();}
 static double mbps(long bytes,long nanos){return nanos>0?bytes*8000.0/nanos:0;}
 static double median(double[] samples){double[] a=samples.clone();Arrays.sort(a);return a.length%2==0?(a[a.length/2-1]+a[a.length/2])/2:a[a.length/2];}
 static double jitter(double[] a){double n=0;for(int i=1;i<a.length;i++)n+=Math.abs(a[i]-a[i-1]);return a.length>1?n/(a.length-1):0;}
 Result run(Progress progress) throws IOException {
  double[] latency=new double[5];
  for(int i=-1;i<latency.length;i++){
   check();progress.update("Measuring HTTPS latency",0,0,Math.max(0,(i+1)*100/6));long start=System.nanoTime();HttpURLConnection c=connect(0);
   try(InputStream in=c.getInputStream()){in.read();if(i>=0)latency[i]=(System.nanoTime()-start)/1_000_000.0;}finally{close(c);}
  }
  check();progress.update("Testing download speed",0,0,0);
  AtomicLong bytes=new AtomicLong(),first=new AtomicLong(),last=new AtomicLong();
  long deadline=System.nanoTime()+DOWNLOAD_NANOS;
  ExecutorService workers=Executors.newFixedThreadPool(2);
  List<Future<?>> jobs=new ArrayList<>();
  for(int i=0;i<2;i++)jobs.add(workers.submit(()->{
   HttpURLConnection c=null;long received=0,reported=0;
   try {c=connect(MAX_BYTES/2);try(InputStream in=c.getInputStream()){
    byte[] buffer=new byte[32768];
    while(received<MAX_BYTES/2&&System.nanoTime()<deadline){check();int n=in.read(buffer,0,(int)Math.min(buffer.length,MAX_BYTES/2-received));
     if(n<0){if(received<MAX_BYTES/2&&System.nanoTime()<deadline)throw new IOException("Incomplete test response");break;}if(n==0)continue;long now=System.nanoTime();first.compareAndSet(0,now);last.accumulateAndGet(now,Math::max);received+=n;long total=bytes.addAndGet(n);
     if(now-reported>250_000_000){reported=now;long elapsed=now-first.get();double speed=mbps(total,elapsed);int time=(int)(100*(DOWNLOAD_NANOS-Math.max(0,deadline-now))/DOWNLOAD_NANOS);progress.update("Testing download speed",total,speed,Math.min(99,Math.max(time,(int)(total*100/MAX_BYTES))));}
    }
   }}catch(IOException e){if(!cancelled.get())throw new CompletionException(e);}finally{if(c!=null)close(c);}
  }));
  try {
   for(Future<?> job:jobs)job.get(19,TimeUnit.SECONDS);
   check();long count=bytes.get(),elapsed=last.get()-first.get();if(count<262144||elapsed<=0)throw new IOException("Too little test data arrived. Check the connection and try again.");
   return new Result(mbps(count,elapsed),median(latency),jitter(latency),count,Math.max(1,elapsed/1_000_000));
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Test stopped");}
  catch(TimeoutException e){throw new IOException("The connection timed out. Try again closer to your router.",e);}
  catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof CompletionException)cause=cause.getCause();throw new IOException(cause==null?"Download test failed":cause.getMessage(),cause);}
  finally{cancel();for(Future<?> job:jobs)job.cancel(true);workers.shutdownNow();}
 }
}
