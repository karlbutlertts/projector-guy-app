package com.projectorguy.app;

import java.io.File;

/** Short-lived root launcher: ProcessBuilder closes inherited root/ADB session descriptors. */
public final class TouchOverlayLauncher {
    public static void main(String[] arguments) throws Exception {
        File directory = new File("/data/local/tmp/pg-touch-overlay");
        new ProcessBuilder("/system/bin/setsid", "/system/bin/nohup", "/system/bin/sh", new File(directory, "agent.sh").getPath())
                .redirectInput(new File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(directory, "agent.log")))
                .redirectErrorStream(true).start();
        Thread.sleep(200);
        System.exit(0);
    }
}
