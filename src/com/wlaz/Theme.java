package com.wlaz;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

public class Theme {
    public static final int SYSTEM = 0, DAY = 1, NIGHT = 2;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("wl", Context.MODE_PRIVATE);
    }

    public static int mode(Context c) {
        return sp(c).getInt("mode", SYSTEM);
    }

    public static void setMode(Context c, int m) {
        sp(c).edit().putInt("mode", m).apply();
    }

    /** 手动把 uiMode 写进 Configuration，再 recreate 生效 */
    public static void apply(Activity a) {
        int m = mode(a);
        if (m == SYSTEM) return;
        Configuration cfg = new Configuration(a.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (m == NIGHT ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        a.getResources().updateConfiguration(cfg, a.getResources().getDisplayMetrics());
    }
}
