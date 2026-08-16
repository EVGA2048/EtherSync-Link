package com.etherstories.link;

/** 节点序列号：6 位字母+数字，由数据库 id 稳定算出。 */
public final class Units {

    private static final String L = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String D = "23456789";

    private Units() {}

    public static String code(int id) {
        int n = Math.max(1, id);
        char[] c = new char[6];
        for (int i = 5; i >= 0; i--) {
            if ((i & 1) == 1) {
                c[i] = D.charAt(n % D.length());
                n /= D.length();
            } else {
                c[i] = L.charAt(n % L.length());
                n /= L.length();
            }
        }
        return new String(c);
    }

    public static String or(String serial, int id) {
        if (serial != null && serial.trim().length() == 6) return serial.trim().toUpperCase();
        return code(id);
    }
}
