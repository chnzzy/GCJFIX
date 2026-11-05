package com.gcjfix.xposed;

/**
 * WGS-84 / GCJ-02(火星) / BD-09(百度) 坐标互转。
 * 纯 Java，无 Android 依赖，便于单独测试。
 */
final class CoordTransform {

    private static final double A = 6378245.0;                    // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323;      // 偏心率平方
    private static final double PI = Math.PI;
    private static final double X_PI = PI * 3000.0 / 180.0;

    private CoordTransform() {
    }

    /** 中国大致范围（含港澳台），境外不做偏移，避免把国外照片坐标改坏 */
    static boolean inChina(double lng, double lat) {
        return lng > 73.4 && lng < 135.1 && lat > 3.8 && lat < 53.6;
    }

    private static double tLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static double tLng(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return r;
    }

    /** WGS-84 -> GCJ-02，返回 {lng, lat} */
    static double[] wgs2gcj(double lng, double lat) {
        if (!inChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLat = tLat(lng - 105.0, lat - 35.0);
        double dLng = tLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    /** GCJ-02 -> WGS-84，迭代逼近（用于回滚/校验） */
    static double[] gcj2wgs(double lng, double lat) {
        if (!inChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double wLng = lng;
        double wLat = lat;
        for (int i = 0; i < 8; i++) {
            double[] g = wgs2gcj(wLng, wLat);
            double dLng = g[0] - lng;
            double dLat = g[1] - lat;
            if (Math.abs(dLng) < 1e-9 && Math.abs(dLat) < 1e-9) {
                break;
            }
            wLng -= dLng;
            wLat -= dLat;
        }
        return new double[]{wLng, wLat};
    }

    /** GCJ-02 -> BD-09，返回 {lng, lat} */
    static double[] gcj2bd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }
}
