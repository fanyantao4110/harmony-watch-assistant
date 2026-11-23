package com.wlaz;

import java.util.ArrayList;
import java.util.List;

public class AppItem {
    public int id;
    public String name = "";
    public String icon = "";
    public List<String> shots = new ArrayList<String>();
    public String desc = "";
    public List<String> dev = new ArrayList<String>();
    public String ver = "";
    public String size = "";
    public String cat = "";
    public String devName = "";
    public int dl;
    public int rating;
    public String up = "";
    public final List<Pkg> pkgs = new ArrayList<Pkg>();

    public List<String> haveDevs() {
        List<String> r = new ArrayList<String>();
        for (Pkg p : pkgs) if (!r.contains(p.dev)) r.add(p.dev);
        return r;
    }

    public List<String> wantedDevs() {
        List<String> have = haveDevs();
        List<String> r = new ArrayList<String>();
        for (String d : dev) if (!have.contains(d)) r.add(d);
        return r;
    }

    public boolean downloadable() {
        return !pkgs.isEmpty();
    }

    public String subText() {
        StringBuilder sb = new StringBuilder();
        if (ver != null && ver.length() > 0) sb.append("v").append(ver);
        if (size != null && size.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(size);
        }
        if (devName != null && devName.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(devName);
        }
        return sb.toString();
    }

    public String adaptLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("适配 ");
        if (dev.isEmpty()) return sb.append("-").toString();
        for (int i = 0; i < dev.size() && i < 6; i++) {
            if (i > 0) sb.append("/");
            sb.append(dev.get(i));
        }
        if (dev.size() > 6) sb.append("/...");
        return sb.toString();
    }

    public boolean match(String kw) {
        if (kw == null || kw.length() == 0) return true;
        String k = kw.toLowerCase();
        return (name + " " + desc + " " + devName + " " + ver + " " + C.catName(cat)).toLowerCase().contains(k);
    }
}
