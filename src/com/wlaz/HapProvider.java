package com.wlaz;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 给原版安装引擎喂 content:// Uri 用的最小 ContentProvider。
 *
 * 为什么必须自己写：
 *  - 原版 HuaweiInstallPackageInspector.readUri 用的是
 *    context.getContentResolver().openInputStream(uri)，
 *    传 file:// 在 targetSdk>=24 上会直接被 FileUriExposedException 干掉；
 *  - androidx.core.content.FileProvider 只存在于 assets 里的原版 dex，
 *    只有 DexClassLoader 能加载它，manifest 里的 provider 由系统用自己的
 *    ClassLoader 实例化，找不到类，provider 根本不会注册。
 *
 * 所以这里只用系统核心 API 实现：openFile 直接返回只读 fd。
 * Uri 形如 content://com.wlaz.hapfiles/wl_signed.hap
 */
public class HapProvider extends ContentProvider {

    public static final String AUTHORITY = "com.wlaz.hapfiles";

    /** 允许对外暴露的目录（签名产物都在这里）
     *  ★ 必须和 Dl.signedDir() 的所有候选目录保持一致，
     *    否则包签好了但 Provider 解析不到，安装会报「找不到文件」。 */
    private static File[] roots(android.content.Context c) {
        File signed = null;
        try {
            signed = Dl.signedDir(c);
        } catch (Throwable ignore) {
        }
        java.util.ArrayList<File> list = new java.util.ArrayList<File>();
        if (signed != null) list.add(signed);
        list.add(new File("/storage/emulated/0/腕连助手/签名包"));
        list.add(new File("/sdcard/腕连助手/签名包"));
        list.add(new File(c.getCacheDir(), "hap"));
        try {
            File ext = c.getExternalFilesDir(null);
            if (ext != null) list.add(new File(ext, "签名包"));
            File extFiles = ext;
            if (extFiles != null) list.add(new File(extFiles, "signed"));
        } catch (Throwable ignore) {
        }
        list.add(new File(c.getFilesDir(), "signed"));
        list.add(new File(c.getFilesDir(), "签名包"));
        File extCache = c.getExternalCacheDir();
        if (extCache != null) list.add(new File(extCache, "hap"));
        File[] out = new File[list.size()];
        return list.toArray(out);
    }

    /** 把文件名转成一个能被本 Provider 解析的 Uri */
    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) throw new FileNotFoundException("empty name");
        for (File root : roots(getContext())) {
            if (root == null) continue;
            File f = new File(root, name);
            try {
                if (f.exists() && f.isFile()
                        && f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                    return f;
                }
            } catch (Exception ignore) {
            }
        }
        throw new FileNotFoundException("not found: " + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}