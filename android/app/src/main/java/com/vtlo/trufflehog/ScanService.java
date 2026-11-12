package com.vtlo.trufflehog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service that keeps the app process alive while a scan runs. */
public class ScanService extends Service {
    private static final String CHANNEL = "scans";
    private static final String EXTRA_TITLE = "title";
    private static Context appContext;
    private PowerManager.WakeLock wakeLock;

    static void start(Context ctx, String title) {
        appContext = ctx.getApplicationContext();
        Intent i = new Intent(appContext, ScanService.class).putExtra(EXTRA_TITLE, title);
        try {
            if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(i);
            else appContext.startService(i);
        } catch (Exception ignored) {
            // Background start restrictions: the scan still runs while the app is visible.
        }
    }

    static void stop() {
        if (appContext != null) appContext.stopService(new Intent(appContext, ScanService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Running scans",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle(getString(R.string.app_name))
                .setContentText((title != null ? title : "trufflehog") + " is running")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vtlos3c:scan");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
