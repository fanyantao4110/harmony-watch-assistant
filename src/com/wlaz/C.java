package com.wlaz;

import java.util.HashMap;
import java.util.Map;

public class C {
    public static final String SITE = "https://fanyantao4110.github.io/watchapp/";
    public static final String APPS_URL = SITE + "apps.json";
    public static final String PKGS_URL = SITE + "pkgs.json";
    public static final String PKG_BASE = "https://fanyantao4110.github.io/watchapp-app/";
    public static final String UA = "WLAssistant/1.0 (Android)";
    public static final String DIR = "腕连助手";

    public static final Map<String, String> DEV = new HashMap<String, String>();
    public static final Map<String, String> CAT = new HashMap<String, String>();

    static {
        DEV.put("fit2", "HUAWEI FIT 2");
        DEV.put("fit3", "HUAWEI FIT 3");
        DEV.put("fit4", "HUAWEI FIT 4");
        DEV.put("fit5", "HUAWEI FIT 5");
        DEV.put("WatchD", "HUAWEI Watch D");
        DEV.put("WatchD2", "HUAWEI Watch D 2");
        DEV.put("WatchD3", "HUAWEI Watch D 3");
        DEV.put("gt2", "HUAWEI WATCH GT 2");
        DEV.put("gt3", "HUAWEI WATCH GT 3");
        DEV.put("gt4", "HUAWEI WATCH GT 4");
        DEV.put("gt5", "HUAWEI WATCH GT 5");
        DEV.put("gt6", "HUAWEI WATCH GT 6");
        DEV.put("GT7", "HUAWEI WATCH GT 7");
        DEV.put("GTCyber", "HUAWEI GT Cyber");
        DEV.put("GTRunner", "HUAWEI GT Runner");
        DEV.put("GT2022DC", "HUAWEI GT 2022 典藏版");
        DEV.put("GS3", "HONOR GS 3");
        DEV.put("GSPRO", "HONOR GS PRO");
        DEV.put("Buds", "HUAWEI Watch Buds");
        DEV.put("ultimate", "HUAWEI WATCH Ultimate");
        CAT.put("game", "游戏");
        CAT.put("tools", "工具");
        CAT.put("health", "健康");
        CAT.put("entertainment", "娱乐");
        CAT.put("self_sign_upload", "自助上传");
    }

    public static String devName(String code) {
        if (code == null) return "";
        String n = DEV.get(code);
        return n == null ? code : n;
    }

    public static String catName(String code) {
        if (code == null) return "未分类";
        String n = CAT.get(code);
        return n == null ? code : n;
    }
}
