package com.projectorguy.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Keeps system install confirmation independent of the store's singleTask activity. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirmation); }
                catch (RuntimeException e) { Log.e("PGInstaller", "Could not open install confirmation", e); }
            }
            return;
        }
        Log.i("PGInstaller", "Install result status=" + status + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        Intent result = new Intent(context, MainActivity.class);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        result.putExtra(PackageInstaller.EXTRA_STATUS, status);
        result.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        context.startActivity(result);
    }
}
