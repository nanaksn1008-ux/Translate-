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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public class TranslateService extends Service {
    static TranslateService inst;
    static final String CH = "renpy_translate";
    static volatile String[] texts = new String[0];
    static volatile String[] results = new String[0];
    static volatile String lang = "id";
    static volatile int total = 0, failed = 0, prefix = 0;
    static final AtomicInteger doneCnt = new AtomicInteger();
    static volatile boolean finished = true, stop = false, running = false;
    static boolean[] batchDone;
    PowerManager.WakeLock wl;
    long lastNotif = 0;

    static synchronized void prepare(String[] t, String l) {
        texts = t; lang = l; total = t.length; results = new String[t.length];
        failed = 0; prefix = 0; doneCnt.set(0); finished = false; stop = false;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Terjemahan", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = build("Menerjemahkan 0/" + total, 0, total, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renpy:translate");
        wl.acquire(6 * 60 * 60 * 1000L);
        if (!finished && !running) {
            running = true;
            new Thread(new Runnable() { public void run() { work(); } }).start();
        }
        return START_NOT_STICKY;
    }

    void work() {
        try {
            final List<int[]> batches = new ArrayList<int[]>();
            int i = 0;
            while (i < total) {
                int j = i, len = 0;
                while (j < total && (j - i) < 25 && (j == i || len + texts[j].length() <= 1200)) { len += texts[j].length() + 1; j++; }
                batches.add(new int[]{i, j});
                i = j;
            }
            batchDone = new boolean[batches.size()];
            ExecutorService ex = Executors.newFixedThreadPool(6);
            for (int b = 0; b < batches.size(); b++) {
                final int bi = b;
                final int[] r = batches.get(b);
                ex.submit(new Runnable() { public void run() {
                    try { if (!stop) doBatch(r[0], r[1]); } catch (Exception e) { }
                    markDone(bi, batches);
                } });
            }
            ex.shutdown();
            ex.awaitTermination(6, TimeUnit.HOURS);
        } catch (Exception e) { }
        prefix = total;
        finished = true;
        running = false;
        String msg = stop ? "Terjemahan dihentikan" : (failed > 0 ? "Selesai, " + failed + " baris gagal" : "Terjemahan selesai ✅");
        finish(msg);
    }

    static synchronized void markDone(int bi, List<int[]> batches) {
        batchDone[bi] = true;
        int p = 0;
        while (p < batches.size() && batchDone[p]) p++;
        prefix = p == 0 ? 0 : batches.get(p - 1)[1];
    }

    void doBatch(int s, int e) {
        String[] out = null;
        if (e - s > 1) {
            try {
                StringBuilder sb = new StringBuilder();
                for (int k = s; k < e; k++) { if (k > s) sb.append("\n"); sb.append(texts[k]); }
                String[] parts = google(sb.toString()).split("\\r?\\n", -1);
                if (parts.length == e - s) out = parts;
            } catch (Exception x) { }
        }
        for (int k = s; k < e && !stop; k++) {
            String o = out != null ? out[k - s] : null;
            if (o == null || o.trim().isEmpty()) o = one(texts[k]);
            if (o == null) failed++; else results[k] = o;
            doneCnt.incrementAndGet();
        }
        long now = System.currentTimeMillis();
        if (now - lastNotif > 1000) {
            lastNotif = now;
            update("Menerjemahkan " + doneCnt.get() + "/" + total, doneCnt.get(), total);
        }
    }

    String one(String q) {
        for (int a = 0; a < 3 && !stop; a++) {
            try { return google(q); } catch (Exception e1) { }
            try { return mymemory(q); } catch (Exception e2) { }
            try { return lingva(q); } catch (Exception e3) { }
            try { Thread.sleep(800L * (a + 1)); } catch (Exception e) { }
        }
        return null;
    }

    static String http(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    static String google(String q) throws Exception {
        String body = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + lang + "&dt=t&q=" + URLEncoder.encode(q, "UTF-8"));
        JSONArray seg = new JSONArray(body).getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) sb.append(seg.getJSONArray(i).optString(0, ""));
        if (sb.length() == 0) throw new Exception("kosong");
        return sb.toString();
    }

    static String mymemory(String q) throws Exception {
        if (q.length() > 480) throw new Exception("panjang");
        String body = http("https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, "UTF-8") + "&langpair=en%7C" + lang);
        String t = new JSONObject(body).getJSONObject("responseData").getString("translatedText");
        if (t.isEmpty()) throw new Exception("kosong");
        return t;
    }

    static String lingva(String q) throws Exception {
        String body = http("https://lingva.ml/api/v1/auto/" + lang + "/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20"));
        String t = new JSONObject(body).getString("translation");
        if (t.isEmpty()) throw new Exception("kosong");
        return t;
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
