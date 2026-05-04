package com.example.studydemo;

public class NativeHelper {
    static {
        System.loadLibrary("studydemo");
    }

    public static native String hello(String name);

    public static native int add(int a, int b);

    public static native String getSystemInfo();

    public static native byte[] getByteArray(int size);

    public static native String processIntArray(int[] array);
}