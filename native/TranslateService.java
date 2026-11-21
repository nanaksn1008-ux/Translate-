package com.renpy.translator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

public class TranslateService extends Service {
    static TranslateService inst;
    static final String CH = "renpy_translate";
    PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Terjemahan", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        String text = i != null && i.getStringExtra("text") != null ? i.getStringExtra("text") : "Menerjemahkan…";
        Notification n = build(text, 0, 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renpy:translate");
        wl.acquire(6 * 60 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    Notification build(String text, int p, int max, boolean ongoing) {
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setContentTitle("Ren'Py Translator")
            .setContentText(text)
            .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing);
        if (ongoing && max > 0) b.setProgress(max, p, false);
        return b.build();
    }

    static void update(String text, int p, int max) {
        TranslateService s = inst;
        if (s == null) return;
        ((NotificationManager) s.getSystemService(NOTIFICATION_SERVICE)).notify(1, s.build(text, p, max, true));
    }

    static void finish(String text) {
        TranslateService s = inst;
        if (s == null) return;
        NotificationManager nm = (NotificationManager) s.getSystemService(NOTIFICATION_SERVICE);
        s.stopForeground(true);
        nm.notify(2, s.build(text, 0, 0, false));
        s.stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        inst = null;
        super.onDestroy();
    }
}
