package com.wlaz;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class Api {

    public static class Store {
        public final List<AppItem> apps = new ArrayList<AppItem>();

        public AppItem byId(int id) {
            for (AppItem a : apps) if (a.id == id) return a;
            return null;
        }

        public List<AppItem> downloadable() {
            List<AppItem> r = new ArrayList<AppItem>();
            for (AppItem a : apps) if (a.downloadable()) r.add(a);
            return r;
        }
    }

    private static Store cache;

    public static Store cached() {
        return cache;
    }

    public static Store load() throws Exception {
        String appsJson = getText(C.APPS_URL);
        Store s = new Store();
        JSONArray arr = new JSONArray(appsJson);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            AppItem a = new AppItem();
            a.id = o.optInt("id");
            a.name = o.optString("name", "");
            a.icon = o.optString("icon", "");
            a.desc = o.optString("desc", "");
            a.ver = o.optString("ver", "");
            a.size = o.optString("size", "");
            a.cat = o.optString("cat", "");
            a.devName = o.optString("dev_name", "");
            a.dl = o.optInt("dl", 0);
            a.rating = o.optInt("rating", 0);
            a.up = o.optString("up", "");
            JSONArray sh = o.optJSONArray("shots");
            if (sh != null) for (int j = 0; j < sh.length(); j++) a.shots.add(sh.optString(j));
            JSONArray dv = o.optJSONArray("dev");
            if (dv != null) for (int j = 0; j < dv.length(); j++) a.dev.add(dv.optString(j));
            s.apps.add(a);
        }

        try {
            String pkgsJson = getText(C.PKGS_URL);
            JSONObject root = new JSONObject(pkgsJson);
            JSONArray pa = root.optJSONArray("apps");
            if (pa != null) for (int i = 0; i < pa.length(); i++) {
                JSONObject o = pa.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                AppItem t = null;
                for (AppItem a : s.apps) if (a.name.equals(name)) { t = a; break; }
                if (t == null) continue;
                Pkg p = new Pkg();
                p.dev = o.optString("dev", "");
                p.ver = o.optString("ver", "");
                p.size = o.optString("size", "");
                p.file = o.optString("file", "");
                if (p.file.length() > 0) t.pkgs.add(p);
            }
        } catch (Exception ignore) {
        }

        cache = s;
        return s;
    }

    public static String getText(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", C.UA);
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static Bitmap bitmap(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", C.UA);
            // 图标基本不变，让系统级 HTTP 缓存也帮上忙
            c.setRequestProperty("Cache-Control", "max-age=86400");
            c.setUseCaches(true);
            if (c.getResponseCode() != 200) return null;
            InputStream in = new BufferedInputStream(c.getInputStream());
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}