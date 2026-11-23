package com.wlaz;

public class Pkg {
    public String dev = "";
    public String ver = "";
    public String size = "";
    public String file = "";

    public String url() {
        return C.PKG_BASE + file;
    }

    public String label() {
        return C.devName(dev) + " · v" + ver + " · " + size;
    }
}
