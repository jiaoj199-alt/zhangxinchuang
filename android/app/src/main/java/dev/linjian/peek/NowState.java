package dev.linjian.peek;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 公开版此刻状态：只读取本机授权后的姿态、光线、近距、定位与当前 App。 */
public class NowState {
    private static volatile boolean started = false;
    private static volatile float ax = 0f, ay = 0f, az = 0f;
    private static volatile boolean accelReady = false;
    private static volatile float lightLux = -1f;
    private static volatile boolean lightReady = false;
    private static volatile float proximity = -1f;
    private static volatile float proximityMax = -1f;
    private static volatile boolean proximityReady = false;
    private static volatile long sensorUpdatedAt = 0L;

    private static final SensorEventListener listener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null) return;
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER && event.values != null && event.values.length >= 3) {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]; accelReady = true; sensorUpdatedAt = System.currentTimeMillis();
            } else if (type == Sensor.TYPE_LIGHT && event.values != null && event.values.length >= 1) {
                lightLux = event.values[0]; lightReady = true; sensorUpdatedAt = System.currentTimeMillis();
            } else if (type == Sensor.TYPE_PROXIMITY && event.values != null && event.values.length >= 1) {
                proximity = event.values[0]; proximityMax = event.sensor.getMaximumRange(); proximityReady = true; sensorUpdatedAt = System.currentTimeMillis();
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    public static synchronized void start(Context ctx) {
        if (started || ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
            Sensor prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (acc != null) sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI);
            if (light != null) sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_UI);
            if (prox != null) sm.registerListener(listener, prox, SensorManager.SENSOR_DELAY_UI);
            started = true;
            DebugState.append(app, "此刻状态传感器已启动 v" + AppPrefs.APP_VERSION_NAME);
        } catch (Exception e) {
            DebugState.append(ctx, "此刻状态传感器启动失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static JSONObject collect(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            start(ctx);
            long now = System.currentTimeMillis();
            String pkg = ScreenshotService.currentPackage();
            out.put("version", AppPrefs.APP_VERSION_NAME);
            out.put("updated_at_ms", now);
            out.put("updated_at_local", formatLocal(now));
            out.put("current_package", pkg);
            out.put("current_app", LifeState.appLabelPublic(ctx, pkg));

            JSONObject posture = new JSONObject();
            posture.put("available", accelReady);
            posture.put("label", postureLabel());
            posture.put("x", ax); posture.put("y", ay); posture.put("z", az);
            posture.put("updated_at_ms", sensorUpdatedAt);
            out.put("posture", posture);

            JSONObject environment = new JSONObject();
            environment.put("available", lightReady || proximityReady);
            environment.put("light_lux", lightLux);
            environment.put("light_label", lightLabel(lightLux, lightReady));
            environment.put("proximity", proximity);
            environment.put("proximity_max", proximityMax);
            environment.put("proximity_label", proximityLabel(proximity, proximityMax, proximityReady));
            environment.put("updated_at_ms", sensorUpdatedAt);
            out.put("environment", environment);

            JSONObject media = MediaState.collect(ctx);
            out.put("media_state", media);

            JSONObject location = location(ctx);
            out.put("location", location);
            out.put("summary", summary(out));
            out.put("privacy_note", "此刻状态只在本机授权后读取；定位和媒体状态需要你在系统里单独打开权限。");
        } catch (Exception e) {
            try { out.put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject s = collect(ctx);
            JSONObject p = s.optJSONObject("posture");
            JSONObject e = s.optJSONObject("environment");
            JSONObject l = s.optJSONObject("location");
            JSONObject m = s.optJSONObject("media_state");
            StringBuilder sb = new StringBuilder();
            sb.append("此刻状态\n");
            sb.append("当前：").append(s.optString("current_app", "暂未识别")).append("\n");
            sb.append("姿态：").append(p == null ? "读取中" : p.optString("label", "读取中")).append("\n");
            sb.append("环境：").append(e == null ? "读取中" : e.optString("light_label", "读取中"));
            if (e != null) sb.append(" · ").append(e.optString("proximity_label", ""));
            sb.append("\n");
            sb.append("媒体：").append(mediaLine(m)).append("\n");
            sb.append("定位：").append(locationLine(l)).append("\n");
            sb.append(s.optString("privacy_note", ""));
            return sb.toString().trim();
        } catch (Exception ex) { return "此刻状态读取失败：" + ScreenshotService.shortMsg(ex); }
    }

    public static boolean hasLocationPermission(Context ctx) {
        if (ctx == null) return false;
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static JSONObject location(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        boolean fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        o.put("permission_granted", fine || coarse);
        o.put("fine_permission", fine);
        o.put("coarse_permission", coarse);
        if (!fine && !coarse) {
            o.put("label", "定位未授权");
            return o;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { o.put("label", "定位不可用"); return o; }
        Location best = null;
        try {
            List<String> providers = lm.getProviders(true);
            for (String provider : providers) {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            }
        } catch (SecurityException ignored) { }
        if (best == null) { o.put("label", "等待定位"); return o; }
        o.put("latitude", round(best.getLatitude(), 6));
        o.put("longitude", round(best.getLongitude(), 6));
        o.put("accuracy_m", Math.round(best.getAccuracy() * 10) / 10.0);
        o.put("provider", best.getProvider() == null ? "" : best.getProvider());
        o.put("time_ms", best.getTime());
        o.put("time_local", formatLocal(best.getTime()));
        o.put("label", "已定位 · 约 " + Math.round(best.getAccuracy()) + "m");
        return o;
    }

    private static double round(double v, int scale) {
        double base = Math.pow(10, scale);
        return Math.round(v * base) / base;
    }

    private static String postureLabel() {
        if (!accelReady) return "姿态读取中";
        float absZ = Math.abs(az), absY = Math.abs(ay), absX = Math.abs(ax);
        if (absZ > 7.5f && absZ > absX && absZ > absY) return "手机平放";
        if (absY > 7.0f && absY >= absX) return "手机竖握";
        if (absX > 7.0f) return "手机横握";
        return "手机在移动";
    }

    private static String lightLabel(float lux, boolean ready) {
        if (!ready || lux < 0) return "光线读取中";
        if (lux < 20) return "环境偏暗";
        if (lux < 120) return "室内柔光";
        if (lux < 800) return "环境明亮";
        return "强光环境";
    }

    private static String proximityLabel(float value, float max, boolean ready) {
        if (!ready || value < 0) return "近距读取中";
        if (max > 0 && value < max * 0.35f) return "屏幕前方有遮挡";
        return "屏幕前方无遮挡";
    }

    private static String summary(JSONObject s) {
        JSONObject p = s.optJSONObject("posture");
        JSONObject e = s.optJSONObject("environment");
        JSONObject l = s.optJSONObject("location");
        JSONObject m = s.optJSONObject("media_state");
        return "此刻状态：" + (p == null ? "姿态读取中" : p.optString("label", "姿态读取中"))
                + "，正在 " + s.optString("current_app", "暂未识别")
                + "，" + (e == null ? "环境读取中" : e.optString("light_label", "环境读取中"))
                + "，媒体 " + mediaLine(m)
                + "，定位 " + locationLine(l) + "。";
    }

    private static String mediaLine(JSONObject m) {
        if (m == null) return "读取中";
        if (!m.optBoolean("permission_granted", false)) return "未授权";
        if (!m.optBoolean("available", false)) return m.optString("reason", "暂无播放");
        String title = m.optString("title", "").trim();
        String artist = m.optString("artist", "").trim();
        String app = m.optString("app", "").trim();
        String state = m.optString("state_label", "").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("正在听《").append(title.length() == 0 ? "未知音频" : title).append("》");
        if (artist.length() > 0) sb.append(" - ").append(artist);
        if (state.length() > 0) sb.append(" · ").append(state);
        if (app.length() > 0) sb.append(" · ").append(app);
        return sb.toString();
    }

    private static String locationLine(JSONObject l) {
        if (l == null) return "未读取";
        if (!l.optBoolean("permission_granted", false)) return "未授权";
        return l.optString("label", "等待定位");
    }

    private static String formatLocal(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }
}
