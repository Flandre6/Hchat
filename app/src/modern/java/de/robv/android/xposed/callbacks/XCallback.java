package de.robv.android.xposed.callbacks;

public abstract class XCallback {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = Integer.MIN_VALUE;
    public static final int PRIORITY_HIGHEST = Integer.MAX_VALUE;
}
