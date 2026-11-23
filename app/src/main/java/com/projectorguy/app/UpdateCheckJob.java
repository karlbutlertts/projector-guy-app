package com.projectorguy.app;
import android.app.job.*;import android.os.Handler;import android.os.Looper;
public final class UpdateCheckJob extends JobService {
 private volatile Thread worker;
 @Override public boolean onStartJob(JobParameters p){worker=new Thread(()->{boolean retry=false;try{String result=UpdateNotifications.check(this);if("notified".equals(result)&&UpdateBanner.allowed(this))Thread.sleep(16000);}catch(Exception e){retry=true;android.util.Log.w("UpdateCheckJob","Update check unavailable",e);}final boolean again=retry;if(!Thread.currentThread().isInterrupted())new Handler(Looper.getMainLooper()).post(()->jobFinished(p,again));},"BackgroundUpdateCheck");worker.start();return true;}
 @Override public boolean onStopJob(JobParameters p){if(worker!=null)worker.interrupt();UpdateBanner.hide();return true;}
}
