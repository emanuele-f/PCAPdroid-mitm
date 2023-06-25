package com.pcapdroid.mitm;

public class Addon {
    public String fname;
    String description;
    boolean enabled;
    AddonType type;

    public enum AddonType {
        UserAddon,
        JsInjector
    }

    public Addon(String fname, String description, boolean enabled, AddonType type) {
        this.fname = fname;
        this.description = description;
        this.enabled = enabled;
        this.type = type;
    }

    public Addon(String fname, String description, boolean enabled) {
        this(fname, description, enabled, AddonType.UserAddon);
    }
}
