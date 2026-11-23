package com.wlaz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片加载：内存 LruCache + 磁盘缓存。
 *
 * 之前只有内存缓存，App 一被杀 / 每次冷启动都要把所有图标重新下一遍，
 * 列表滚动一片白块。现在用磁盘缓存兜住冷启动：
 *   1) 内存命中直接显示
 *   2) 磁盘命中：后台解码，不占网络
 *   3) 都没有才走 Http，成功后写回磁盘
 * 磁盘目录 cacheDir/imgcache/，上限 32MB，超出按 mtime LRU 淘汰。
 */
public class Img {

    private static final int MEM_BYTES = 8 * 1024 * 1024;
    private static final long DISK_BYTES = 32L * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MEM_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** 同一 URL 正在下载的标记，防止快速滑动时重复请求 */
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<String, Boolean>();

    private static File diskDir;

    /** 启动时调用一次，传 Application context */
    public static void init(Context c) {
        if (c == null || diskDir != null) return;
        try {
            diskDir = new File(c.getCacheDir(), "imgcache");
            if (!diskDir.exists()) diskDir.mkdirs();
        } catch (Throwable ignore) {
        }
    }

    private static File diskFile(String url) {
        if (diskDir == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(diskDir, sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void load(final ImageView iv, final String url) {
        if (iv == null) return;
        iv.setTag(url);
        if (url == null || url.length() == 0) {
            iv.setImageDrawable(null);
            return;
        }

        Bitmap b = CACHE.get(url);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }

        iv.setImageDrawable(null);
        if (LOADING.containsKey(url)) return;
        LOADING.put(url, Boolean.TRUE);

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    // 1) 磁盘
                    bmp = readDisk(url);
                    // 2) 网络，并写回磁盘
                    if (bmp == null) {
                        bmp = Api.bitmap(url);
                        if (bmp != null) writeDisk(url, bmp);
                    }
                } finally {
                    LOADING.remove(url);
                }
                if (bmp == null) return;
                final Bitmap out = bmp;
                CACHE.put(url, out);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(out);
                    }
                });
            }
        });
    }

    private static Bitmap readDisk(String url) {
        File f = diskFile(url);
        if (f == null || !f.exists() || f.length() == 0) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) {
                f.delete();
                return null;
            }
            f.setLastModified(System.currentTimeMillis());  // 命中刷新，配合 LRU
            return b;
        } catch (Throwable t) {
            try {
                f.delete();
            } catch (Throwable ignore) {
            }
            return null;
        }
    }

    private static void writeDisk(String url, Bitmap bmp) {
        File f = diskFile(url);
        if (f == null) return;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream fo = null;
        try {
            fo = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.flush();
            fo.close();
            fo = null;
            if (!tmp.renameTo(f)) {
                // rename 失败就直接写目标（少数文件系统会这样）
                tmp.delete();
                FileOutputStream d = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, d);
                d.close();
            }
        } catch (Throwable t) {
            if (fo != null) try {
                fo.close();
            } catch (Throwable ignore) {
            }
            tmp.delete();
            return;
        }
        trimDisk();
    }

    /** 超过上限按 mtime 从旧到新删，腾到 75% 以下 */
    private static void trimDisk() {
        if (diskDir == null) return;
        try {
            File[] fs = diskDir.listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            List<File> files = new ArrayList<File>();
            for (File f : fs) {
                total += f.length();
                files.add(f);
            }
            if (total <= DISK_BYTES) return;

            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.valueOf(a.lastModified()).compareTo(b.lastModified());
                }
            });
            for (File f : files) {
                if (total <= DISK_BYTES * 3 / 4) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignore) {
        }
    }

    /** 清空缓存（内存 + 磁盘） */
    public static void clearDisk() {
        if (diskDir == null) return;
        try {
            File[] fs = diskDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Throwable ignore) {
        }
        CACHE.evictAll();
    }
}