package com.projectorguy.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Vendor-SDK helpers for the Remote Control feature — same reflection
 * pattern as PictureModeBridge (com.newlink.android.projector isn't a
 * compileable dependency, only a runtime <uses-library>). Two real, non-root
 * mechanisms found by decompiling NLProjector.jar on a real A5 Pro:
 *
 *  - SystemManager.goToSleep() puts the projector to sleep exactly like the
 *    physical Power button, via a privileged vendor binder service.
 *  - LegacyCusEx.setFocusMotorForward()/Backward()/Brake() drives the lens
 *    focus motor directly — this is what the vendor's own factory-menu
 *    focus controls call.
 *
 * There is no equivalent for D-pad/menu: system-wide key injection needs
 * android.permission.INJECT_EVENTS, which on this firmware is only held by
 * the vendor's own system-signed NewLinkAccessibilityService (confirmed by
 * decompiling its APK — sharedUserId="android.uid.system"). A regular
 * installed app, even root via su, cannot obtain it: su itself
 * (/system/xbin/su, mode 750 root:shell) refuses to even exec for this
 * app's UID ("Permission denied"), and there is no vendor command for
 * D-pad/menu to fall back on either. See AndroidBridge.sendProjectorCommand.
 *
 * Every vendor call here runs on the main thread via runOnMainThread():
 * ProjectorManager's first-ever getInstance() call in this process binds to
 * the vendor service, and that binder plumbing needs a live Looper on the
 * calling thread. LocalRemoteServer's command handler runs on a plain
 * background thread with no Looper — confirmed on-device, that combination
 * throws "Attempt to read from field ... Looper.mQueue on a null object
 * reference" the first time this runs.
 */
final class ProjectorControlBridge {
    private static final String TAG = "ProjectorControlBridge";

    private ProjectorControlBridge() {}

    /** Puts the projector to sleep via the vendor SystemManager service. */
    static boolean sleep(Context context) {
        return runOnMainThread(() -> {
            Object systemManager = getVendorService(context, "com.newlink.android.projector.SystemManager");
            if (systemManager == null) return false;
            systemManager.getClass()
                    .getMethod("goToSleep", long.class, int.class, int.class)
                    .invoke(systemManager, SystemClock.uptimeMillis(), 0, 0);
            return true;
        }, "sleep");
    }

    /**
     * Drives the lens focus motor for a short pulse then brakes. remote.html
     * sends one command per tap with no separate "release" signal, so a
     * bounded pulse (rather than forward-until-told-to-stop) avoids leaving
     * the motor running if a follow-up command never arrives.
     */
    static boolean pulseFocusMotor(Context context, boolean forward) {
        return runOnMainThread(() -> {
            Object cusEx = getVendorService(context, "com.newlink.android.projector.legacy.LegacyCusEx");
            if (cusEx == null) return false;
            Class<?> cusExClass = cusEx.getClass();
            cusExClass.getMethod(forward ? "setFocusMotorForward" : "setFocusMotorBackward").invoke(cusEx);
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    runOnMainThread(() -> {
                        cusExClass.getMethod("setFocusMotorBrake").invoke(cusEx);
                        return true;
                    }, "focus-motor-brake");
                } catch (InterruptedException ignored) {
                }
            }, "focus-motor-brake-timer").start();
            return true;
        }, "pulseFocusMotor");
    }

    private static Object getVendorService(Context context, String serviceClassName) throws Exception {
        Class<?> managerClass = Class.forName("com.newlink.android.projector.ProjectorManager");
        Object manager = managerClass.getMethod("getInstance", Context.class)
                .invoke(null, context.getApplicationContext());
        Class<?> serviceClass = Class.forName(serviceClassName);
        Object service = managerClass.getMethod("getService", Class.class).invoke(manager, serviceClass);
        if (service == null) Log.e(TAG, serviceClassName + " unavailable");
        return service;
    }

    private static boolean runOnMainThread(Callable<Boolean> task, String label) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return task.call();
            }
            FutureTask<Boolean> future = new FutureTask<>(task);
            new Handler(Looper.getMainLooper()).post(future);
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, label + " failed: " + describe(e));
            return false;
        }
    }

    /** Reflection failures are usually InvocationTargetException, whose own getMessage() is null — the real reason is in getCause(). */
    private static String describe(Throwable t) {
        Throwable cause = t.getCause();
        return t + (cause != null ? " caused by " + cause : "");
    }
}
