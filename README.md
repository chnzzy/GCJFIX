# GCJFIX — 海外原生系统在国内的坐标纠偏模块

## 所有内容由DEEPSEEK生成 
AI神力啊我说

# 简介
一个 **LSPosed / Vector** 模块：把系统定位输出的 **WGS-84**（GPS 原始坐标）换算为
**GCJ-02 火星坐标**，让「用 Google Maps SDK 渲染」的 App 图钉、相册照片定位、语音助手
报位置等，都落在**中国区 GCJ-02 底图**的正确位置上。

> 本人场景：买了一台海外原生系统的手机 日版夏普R8Pro
> 人在中国大陆用。GPS 芯片吐的是 WGS-84，而 Google Maps 在国内是 GCJ-02 加密底图，
> 两者差约 400–500 米。本模块把这套坐标生态对齐。

---

## 目录

- [背景：坐标到底偏在哪](#背景坐标到底偏在哪)
- [核心设计](#核心设计)
- [安装](#安装)
- [作用域怎么勾](#作用域怎么勾)
- [验证](#验证)
- [已知限制](#已知限制)
- [回滚](#回滚)
- [编译](#编译)
- [License](#license)

---

## 背景：坐标到底偏在哪

中国大陆要求地图服务使用 GCJ-02（火星坐标）加密底图。于是一台手机上的坐标有
**两种来源、方向相反**：

| 数据源 | 坐标系 | 在 GCJ 底图上 |
|--------|--------|--------------|
| GPS / 网络定位（`Location` 对象） | **WGS-84** | 偏 400–500 米 ❌ |
| 地图点击 / 已保存的业务坐标 | **GCJ-02**（已对齐底图） | 准 ✅ |

所以「该不该转」取决于坐标来源，不能一刀切。

---

## 核心设计

### 两条 hook 链，角色相反

| 链 | 触发点 | 处理 | 覆盖 |
|----|--------|------|------|
| **Location 链** | `Location.getLatitude()/getLongitude()` | 全局开，WGS→GCJ | 所有「读定位」的 App（语音助手报位置、查找设备等）|
| **LatLng 链** | `com.google.android.gms.maps.model.LatLng(double,double)` 构造器 | 按包名白名单，仅相册转 | Google 相册（照片 EXIF 是 WGS）|

- `HookEntry.LATLNG_WHITELIST`：白名单，默认只有 `com.google.android.apps.photos`
- `HookEntry.BLACKLIST`：黑名单（Google Maps、高德、百度、腾讯、微信、支付宝、滴滴、美团）
  这些 App 自带换算或需要真实 WGS-84，hook 会双重偏移，一律排除。
- `HookEntry.OUT_CRS`：`0` = GCJ-02（默认），`1` = BD-09（百度）

### 二次偏移 / 「来回跳」的根治

根因：App 会把「已保存 / 已读到的 GCJ 坐标」缓存下来，下次冷启动再读出来重新塞进
`Location` / `LatLng`。若这条缓存值又经过一次转换，就形成二次偏移；若缓存值
「时而被拦、时而不拦」，就表现为图钉来回跳。

对策（两重幂等保护）：

1. **`recentOutput` 环形缓冲（256 槽，精确比对）**：拦截「同一 double 原值回读再构造」
   的自反馈（典型：把 `camera.target` 读出来再 `new LatLng(...)`）。
2. **`setVals` 缓冲 + 容差匹配（±0.0001° ≈ 11 米）**：hook `setLatitude/setLongitude`
   记录 App 写入的值，`getter` 命中即视为「App 用 GCJ 值自造的对象」，原样返回不转。
   容差匹配让定位刷新时的微小抖动也被统一识别，消除冷启动
   「偏移后 ↔ 二次偏移后」来回跳（emopa 大头钉、查找设备等）。

### 约束

- 只改返回值 / 入参，**不改写**对象内部字段。
- 只在**中国经纬度框内**生效（`73.4–135.1°E, 3.8–53.6°N`），境外坐标原样返回。

---

## 安装

前提：已 root，已装 **Vector**（LSPosed 维护分支，支持 Android 8.1–17）或
**LSPosed 2.x**，Zygisk 已开启。

```bash
adb devices
adb -s <序列号> install -r GCJFIX-2.0.apk
```

也可以直接把 APK 传到手机点安装。

1. 打开 LSPosed / Vector → 模块 → 启用 **GCJFix**
2. 进「作用域」，按下一节勾选
3. **强制停止**作用域内的 App（或重启手机）。hook 在进程启动时安装，不重启不生效
4. 验证：`adb logcat | grep GCJFix`

---

## 作用域怎么勾

| App | 勾不勾 | 原因 |
|-----|-------|------|
| Google 相册 `com.google.android.apps.photos` | ✅ 勾 | 照片 EXIF 是 WGS，需要 LatLng 链转 |
| 语音助手（如 Sharp emopa）| ✅ 勾 | 报位置走 Location 链；地图选点已对齐，不转 |
| 查找设备 `com.google.android.apps.adm` | ✅ 勾 | 显示设备位置走 Location 链 |
| Google Maps `com.google.android.apps.maps` | ❌ 别勾 | 自带换算，勾了双重偏移（黑名单已兜底）|
| 高德 / 百度 / 腾讯地图 | ❌ 别勾 | 原生 GCJ，无需转换 |

> 临时想关掉某个 App 的修正：作用域里**取消勾选**即可，不用改代码。

---

## 验证

**卫星图对拍法**（最可靠）：Google Maps 切到卫星图层（卫星影像是 WGS-84 对齐的），
用 PC 端查一下目标位置的卫星图，再对比手机上的图钉：

- 图钉 ≈ 真实位置 → 方向正确 ✅
- 图钉比真实位置又偏了几百米 → 双偏移，说明该 App 其实已换算过，把它从作用域去掉

也可以直接看日志：

```
[GCJFix] Location shift 121.3913,28.6263 -> 121.3957,28.6234   ← 干净转一次
[GCJFix] skip re-shift ...                                    ← 幂等保护拦住二次
```

---

## 已知限制

- `Location.distanceTo()/distanceBetween()` 走原生字段，hook 不到，距离/方位有轻微不一致
- 被 hook 进程里，凡消费 `getLatitude()` 的非显示逻辑（距离计算、位置上报）拿到的都是 GCJ 值；
  Google Play Services / 位置记录仍走 WGS-84 原始链路
- 相机若由 HAL 直接写 GNSS 坐标进 JPEG（少数 OEM），框架层 hook 拦不到
- 港澳台在框内但换算精度一般（公开算法的固有局限）
- `setVals` 容差匹配依赖「App 先 set 再 get」的行为模式，对不走 setter 直接构造 `Location`
  的 App 无效（这类一般也不会有二次偏移问题）

---

## 回滚

LSPosed 里关掉模块 → 重启。**没有任何系统文件被改动。**

---

## 编译

### 方式 A：Android Studio

打开项目根目录 → 等 Gradle Sync → `Build > Build APK(s)`。
产物：`app/build/outputs/apk/...`。

### 方式 B：GitHub Actions

推到 GitHub 后，Actions 里的 `build-apk` 会自动构建，下载 artifact 即可。

### 签名

签名密钥**不在仓库里**（已 `.gitignore`）。自行构建时用你自己的密钥；若想覆盖安装
已有版本，需保持同一密钥。

---

## License

[MIT](./LICENSE)
