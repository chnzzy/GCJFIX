package com.gcjfix.xposed;

import android.location.Location;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 把系统定位输出的 WGS-84 换算为 GCJ-02（火星坐标）。
 *
 * 目标机型：海外原生系统 Sharp A301SH（Android 16，KernelSU + LSPosed），
 * 在中国大陆使用。其 Google Maps 是中国区 GCJ-02 底图（证据：GPS 芯片给
 * WGS 121.391305,28.626305，转 GCJ 后 121.3958,28.6233；而地图点击
 * fromScreenLocation 返回的原始值就是 121.395660,28.623347，与 GCJ 值几乎
 * 重合——说明底图已是 GCJ，点击坐标天然对齐底图，不能再转）。
 *
 * ── 最终定案（v2.0）────────────────────────────────────────────
 * 坐标有两种来源，方向相反，必须分开处理：
 *
 *   A. 定位数据（Location 对象，GPS/网络芯片）＝ WGS-84，画到 GCJ 底图需转。
 *   B. 地图交互/业务缓存（点击、已保存坐标）＝ 已是 GCJ，不能再转。
 *
 * 因此两条链角色相反：
 *
 *   1) Location 链（getLatitude/getLongitude）＝ 全局开。
 *      GPS 芯片永远给 WGS，转一次 GCJ 才对得上底图。覆盖所有「读定位」的
 *      App（emopa 语音助手报位置、查找设备 Find My Device 显示设备位置等）。
 *
 *   2) LatLng 链（Maps SDK 构造器）＝ 按包名白名单，只对「往地图塞 WGS 业务
 *      坐标」的 App 转。目前仅 Google 相册（照片 EXIF 是 WGS）。
 *      emopa 地图选点、查找设备等地图交互 App 的坐标已是 GCJ，不转。
 *
 * ── 二次偏移与"来回跳"的根治 ────────────────────────────────────
 * 根因：App 会把「已保存/已读到的 GCJ 坐标」缓存下来，下次冷启动再读出来
 * 重新塞进 Location/LatLng。若这条缓存值又经过一次转换，就形成二次偏移；
 * 若缓存值时而被拦、时而不拦，就表现为图钉来回跳。
 *
 * 对策（两重幂等保护）：
 *   1) recentOutput 环形缓冲（256 槽）：精确比对，拦截"同一 double 原值
 *      回读再构造"的自反馈。
 *   2) setVals 缓冲 + 容差匹配：hook setLatitude/setLongitude 记录 App 写入
 *      的值，getter 命中（容差 ±0.0001 度≈11 米）即视为"App 用 GCJ 值自造
 *      的对象"，原样返回不转。容差匹配让定位刷新时的微小抖动也被统一识别，
 *      消除冷启动"偏移后 ↔ 二次偏移后"来回跳。
 *
 * 约束：
 * 1) 只改返回值/入参，不改写对象内部字段。
 * 2) 只在中国经纬度框内生效。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[GCJFix] ";

    /** 0 = GCJ-02（高德/腾讯/Google 中国底图）；1 = BD-09（百度地图） */
    private static final int OUT_CRS = 0;

    /**
     * v1.6 定案：这台手机的 Google Maps 是中国区 GCJ-02 底图（证据：GPS 芯片
     * WGS 值 121.391305,28.626305 转 GCJ 后是 121.3958,28.6233，而地图点击
     * fromScreenLocation 返回的原始值就是 121.395660,28.623347，与 GCJ 值几乎
     * 重合——说明底图已是 GCJ，点击坐标天然对齐底图，不能再转）。
     *
     * 但「LatLng 入口进来的坐标到底是 WGS 还是 GCJ」取决于 App 的用途：
     *   - Google 相册：照片 EXIF 是 WGS-84，画到 GCJ 底图需转 → 要转。
     *   - emopa / 查找设备等地图交互：点击 fromScreenLocation 已是 GCJ → 不转。
     *
     * 因此 Location 链全局开（GPS 芯片永远给 WGS），LatLng 链改为「按包名
     * 白名单」——只对塞 WGS 业务坐标的 App 转，其余一律不转。
     */
    private static final boolean ENABLE_LOCATION_HOOK = true;

    /**
     * LatLng 链白名单：这些 App 往 Maps SDK 里塞的是 WGS-84 业务坐标
     * （典型：相册把照片 EXIF 的 WGS 坐标画到 GCJ 底图），需要转 GCJ。
     * 其余 App（地图选点、查找设备等）的坐标已是 GCJ，不在白名单则不转。
     */
    private static final String[] LATLNG_WHITELIST = {
            "com.google.android.apps.photos",   // Google 相册：照片 EXIF 是 WGS
    };

    /**
     * Google Maps 本体自带 WGS→GCJ 换算，hook 它必然双偏移；
     * 其余几个自带坐标转换或需要真实 WGS-84 的也排除。按包名前缀匹配。
     */
    private static final String[] BLACKLIST = {
            "com.google.android.apps.maps",
            "com.autonavi",                // 高德
            "com.baidu.BaiduMap",          // 百度地图
            "com.tencent.map",             // 腾讯地图
            "com.tencent.mm",              // 微信（共享位置会变成偏移值）
            "com.tencent.mobileqq",        // QQ
            "com.eg.android.AlipayGphone", // 支付宝
            "com.sdu.didi",                // 滴滴
            "com.meituan",                 // 美团
    };

    private static Field fLat;
    private static Field fLng;
    private static Method mGetLat;
    private static Method mGetLng;
    private static boolean accessorsReady;

    private static boolean cacheValid;
    private static double cInLng, cInLat, cOutLng, cOutLat;
    private static boolean loggedFirstShift;
    private static boolean loggedFirstSkip;

    /**
     * 最近产出过的火星坐标环形缓冲，用于拦截"回读再构造"的自反馈。
     *
     * 典型场景：App 把 map.getCameraPosition().target（已被我们改成 GCJ）
     * 读出来再 new LatLng(...)，值会再偏一次。这种传的是同一个 double 原值，
     * 精确比较就能命中，所以不需要 epsilon（用容差反而会把 1 米内两个真实
     * 不同的点误判成同一个，误差直接放大成 500 米）。
     * 容量从 16 提到 256：SDK 一帧会构造大量 LatLng（视口四角、bounds、
     * 每个 marker），16 个槽几帧就被冲掉，这是"时而拦住时而不拦住"的根源。
     */
    private static final int RECENT = 256;
    private static final double[] recentLng = new double[RECENT];
    private static final double[] recentLat = new double[RECENT];
    private static int recentIdx;
    private static int recentCount;

    /**
     * App 通过 setLatitude/setLongitude 写入的原始值缓冲。
     *
     * emopa 这类 App 会把「已保存的 GCJ 坐标」从数据库读出来，重新 set 进新
     * Location 对象。这些值是 GCJ，get 时不能再转（否则二次偏移）。这里记录
     * 每次 set 进来的值，get 时若字段值命中此缓冲，说明该 Location 是 App 用
     * GCJ 值自造的，直接原样返回，不转换。
     */
    private static final int SET_RECENT = 256;
    private static final double[] setVals = new double[SET_RECENT];
    private static int setIdx;
    private static int setCount;

    /**
     * set 值命中的容差（度）。约 0.0001 度 ≈ 11 米。
     *
     * 定位刷新时坐标本身有微小抖动（同一物理位置前后两次读数差几米），
     * 若用精确比较，adm 这类"冷启动读缓存 GCJ 值"的 App 会时而命中、时而
     * 不命中，表现为图钉在「偏移后 ↔ 二次偏移后」之间来回跳。用容差匹配可
     * 把"同一个点"的抖动统一识别，稳定跳过，消除来回跳。
     */
    private static final double SET_EPS = 0.0001;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        final String pkg = lp.packageName;
        if (pkg == null || pkg.isEmpty() || isBlacklisted(pkg)) {
            return;
        }
        if (isLatLngWhitelisted(pkg)) {
            hookMapsSdk(lp.classLoader);
        }
        if (ENABLE_LOCATION_HOOK) {
            hookLocationGetters(lp);
        }
    }

    private static boolean isLatLngWhitelisted(String pkg) {
        for (String p : LATLNG_WHITELIST) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static void hookLocationGetters(XC_LoadPackage.LoadPackageParam lp) {
        try {
            initAccessors();
            if (mGetLat == null || mGetLng == null) {
                XposedBridge.log(TAG + "Location.getLatitude 不可用，跳过 " + lp.packageName);
                return;
            }
            XposedHelpers.findAndHookMethod("android.location.Location", lp.classLoader,
                    "getLatitude", new ShiftHook(true));
            XposedHelpers.findAndHookMethod("android.location.Location", lp.classLoader,
                    "getLongitude", new ShiftHook(false));

            // 关键：同时 hook setter，把写入的坐标反向还原成 WGS-84 存字段，
            // 保证「Location 对象字段永远存 WGS」，getter 每次正向转 GCJ 恰好一次。
            // 否则 emopa 会把 getter 返回的 GCJ 值 set 回对象/缓存，下次 get 再转
            // 一次，形成二次偏移（选点大头钉刚打开时偏、点定位后回正的现象）。
            XposedHelpers.findAndHookMethod("android.location.Location", lp.classLoader,
                    "setLatitude", double.class, new UnshiftSetterHook(true));
            XposedHelpers.findAndHookMethod("android.location.Location", lp.classLoader,
                    "setLongitude", double.class, new UnshiftSetterHook(false));

            XposedBridge.log(TAG + "hooked " + lp.packageName
                    + " raw=" + (fLat != null ? fLat.getName() + "/" + fLng.getName() : "reflection")
                    + " crs=" + (OUT_CRS == 0 ? "GCJ-02" : "BD-09"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookLocationGetters failed in " + lp.packageName + ": " + t);
        }
    }

    /**
     * setter 记录钩子：把 App 写入的值记进 setVals 缓冲，供 getter 识别
     * "这是 App 用 GCJ 值自造的 Location"从而跳过二次转换。
     * 不再做反向还原（set 分步执行、新建对象时另一维为 0，反算不可靠）。
     */
    private static final class UnshiftSetterHook extends XC_MethodHook {
        private final boolean wantLat;

        UnshiftSetterHook(boolean wantLat) {
            this.wantLat = wantLat;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object obj = param.thisObject;
            if (!(obj instanceof Location)) {
                return;
            }
            double val = (Double) param.args[0];
            if (!Double.isFinite(val)) {
                return;
            }
            rememberSet(val);
        }
    }

    private static void rememberSet(double val) {
        setVals[setIdx] = val;
        setIdx = (setIdx + 1) % SET_RECENT;
        if (setCount < SET_RECENT) {
            setCount++;
        }
    }

    private static boolean isRecentlySet(double val) {
        for (int i = 0; i < setCount; i++) {
            if (Math.abs(setVals[i] - val) <= SET_EPS) {
                return true;
            }
        }
        return false;
    }

    private static final class ShiftHook extends XC_MethodHook {
        private final boolean wantLat;

        ShiftHook(boolean wantLat) {
            this.wantLat = wantLat;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object obj = param.thisObject;
            if (!(obj instanceof Location)) {
                return;
            }
            Double rawLat = readRaw(obj, true);
            Double rawLng = readRaw(obj, false);
            if (rawLat == null || rawLng == null) {
                return;
            }
            double lat = rawLat;
            double lng = rawLng;
            // 该字段值若来自 App 自己 set（GCJ 自造对象），不再转，避免二次偏移
            if (isRecentlySet(wantLat ? lat : lng)) {
                return;
            }
            if (!CoordTransform.inChina(lng, lat)) {
                return;
            }
            double[] out = shift(lng, lat);
            logFirstShift(lng, lat, out, "Location");
            param.setResult(wantLat ? out[1] : out[0]);
        }
    }

    /**
     * 第三方 App 内嵌的 Google Maps SDK 不替调用方换坐标：它拿到的数值直接按 GCJ
     * 底图投影，所以照片的 WGS 坐标画出来就偏几百米。LatLng 是 SDK 唯一的坐标入口，
     * 标记、镜头、多边形、图层位置全部经由它，改这一处即可。
     */
    private static void hookMapsSdk(final ClassLoader cl) {
        try {
            Class<?> latLng = XposedHelpers.findClass(
                    "com.google.android.gms.maps.model.LatLng", cl);
            XposedHelpers.findAndHookConstructor(latLng, double.class, double.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            double lat = (Double) param.args[0];
                            double lng = (Double) param.args[1];
                            if (!CoordTransform.inChina(lng, lat)) {
                                return;
                            }
                            double[] out = shift(lng, lat);
                            logFirstShift(lng, lat, out, "MapsLatLng");
                            param.args[0] = out[1];
                            param.args[1] = out[0];
                        }
                    });
            XposedBridge.log(TAG + "hooked Maps SDK LatLng");
        } catch (Throwable ignored) {
            // 该进程没加载 Maps SDK，属正常情况
        }
    }

    private static void initAccessors() {
        if (accessorsReady) {
            return;
        }
        accessorsReady = true;
        try {
            mGetLat = Location.class.getMethod("getLatitude");
            mGetLng = Location.class.getMethod("getLongitude");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Location getter lookup failed: " + t);
            return;
        }

        Field lat = null;
        Field lng = null;
        // 精确匹配已知字段名（旧版 mLatitude；Android 16 起 mLatitudeDegrees）
        for (Field f : Location.class.getDeclaredFields()) {
            if (f.getType() != double.class) {
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.equals("mlatitude") || n.equals("mlatitudedegrees")) {
                lat = f;
            } else if (n.equals("mlongitude") || n.equals("mlongitudedegrees")) {
                lng = f;
            }
        }
        // 再退回模糊匹配，应对未来改名
        if (lat == null || lng == null) {
            for (Field f : Location.class.getDeclaredFields()) {
                if (f.getType() != double.class) {
                    continue;
                }
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (lat == null && n.contains("latitude")) {
                    lat = f;
                }
                if (lng == null && n.contains("longitude")) {
                    lng = f;
                }
            }
        }
        if (lat != null && lng != null) {
            try {
                lat.setAccessible(true);
                lng.setAccessible(true);
                fLat = lat;
                fLng = lng;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "setAccessible failed: " + t);
            }
        }
    }

    private static Double readRaw(Object loc, boolean wantLat) {
        Field f = wantLat ? fLat : fLng;
        if (f != null) {
            try {
                return f.getDouble(loc);
            } catch (Throwable ignored) {
                // 掉到反射兜底
            }
        }
        try {
            Method m = wantLat ? mGetLat : mGetLng;
            Object v = XposedBridge.invokeOriginalMethod(m, loc, new Object[0]);
            return v instanceof Double ? (Double) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 换算一次即幂等。
     *
     * 同一个坐标常常连续穿过两组 hook：先被 Location.getLatitude() 偏移，
     * 再被原样交给 Maps SDK 的 LatLng 构造器。若不做保护就会被偏两次，
     * 图钉落在"既不是原始位置、也不是真实位置"的第三种地方。
     * 这里把最近产出过的火星坐标记下来，再次遇到相同数值直接放行。
     */
    private static synchronized double[] shift(double lng, double lat) {
        if (isRecentOutput(lng, lat)) {
            logFirstSkip(lng, lat);
            return new double[]{lng, lat};
        }
        if (cacheValid && cInLng == lng && cInLat == lat) {
            return new double[]{cOutLng, cOutLat};
        }
        double[] g = CoordTransform.wgs2gcj(lng, lat);
        double outLng = g[0];
        double outLat = g[1];
        if (OUT_CRS == 1) {
            double[] b = CoordTransform.gcj2bd09(outLng, outLat);
            outLng = b[0];
            outLat = b[1];
        }
        cInLng = lng;
        cInLat = lat;
        cOutLng = outLng;
        cOutLat = outLat;
        cacheValid = true;
        rememberOutput(outLng, outLat);
        return new double[]{outLng, outLat};
    }

    private static boolean isRecentOutput(double lng, double lat) {
        for (int i = 0; i < recentCount; i++) {
            if (recentLng[i] == lng && recentLat[i] == lat) {
                return true;
            }
        }
        return false;
    }

    private static void rememberOutput(double lng, double lat) {
        recentLng[recentIdx] = lng;
        recentLat[recentIdx] = lat;
        recentIdx = (recentIdx + 1) % RECENT;
        if (recentCount < RECENT) {
            recentCount++;
        }
    }

    /** 每个进程只打印一次真实换算，用来确认 hook 确实在跑 */
    private static void logFirstShift(double lng, double lat, double[] out, String src) {
        if (loggedFirstShift) {
            return;
        }
        loggedFirstShift = true;
        XposedBridge.log(TAG + src + " shift " + lng + "," + lat + " -> " + out[0] + "," + out[1]);
    }

    /** 每进程打印一次"已换算过、拦下第二次"的记录 */
    private static void logFirstSkip(double lng, double lat) {
        if (loggedFirstSkip) {
            return;
        }
        loggedFirstSkip = true;
        XposedBridge.log(TAG + "skip re-shift " + lng + "," + lat);
    }

    private static boolean isBlacklisted(String pkg) {
        for (String p : BLACKLIST) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
