package com.renpy.translator;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

@CapacitorPlugin(name = "DownloadSaver")
public class DownloadSaverPlugin extends Plugin {
    @PluginMethod
    public void save(PluginCall call) {
        String name = call.getString("filename", "file.zip");
        String folder = call.getString("folder", "renpy translate");
        String data = call.getString("data", "");
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
                Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("MediaStore menolak");
                OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                os.write(bytes);
                os.close();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder);
                dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(new File(dir, name));
                fo.write(bytes);
                fo.close();
            }
            JSObject r = new JSObject();
            r.put("path", "Download/" + folder + "/" + name);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void startTranslate(PluginCall call) {
        try {
            Context c = getContext();
            JSArray arr = call.getArray("texts");
            String[] t = new String[arr.length()];
            for (int i = 0; i < t.length; i++) t[i] = arr.getString(i);
            if (TranslateService.running) { call.reject("Terjemahan lain masih berjalan"); return; }
            TranslateService.prepare(t, call.getString("lang", "id"));
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                getActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            }
            ContextCompat.startForegroundService(c, new Intent(c, TranslateService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("done", TranslateService.doneCnt.get());
        r.put("total", TranslateService.total);
        r.put("failed", TranslateService.failed);
        r.put("prefix", TranslateService.prefix);
        r.put("finished", TranslateService.finished);
        call.resolve(r);
    }

    @PluginMethod
    public void results(PluginCall call) {
        int from = call.getInt("from", 0), to = call.getInt("to", 0);
        String[] res = TranslateService.results;
        JSArray out = new JSArray();
        for (int i = from; i < to && i < res.length; i++) out.put(res[i] == null ? JSONObject.NULL : res[i]);
        JSObject r = new JSObject();
        r.put("items", out);
        call.resolve(r);
    }

    @PluginMethod
    public void stopTranslate(PluginCall call) {
        TranslateService.stop = true;
        call.resolve();
    }
}
