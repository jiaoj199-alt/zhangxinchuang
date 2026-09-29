package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 应用门禁：用无障碍监听前台 App，打开被锁 App 时弹出锁定页。 */
public class AppGate {
    public static final String KEY_STATE = "app_gate_state_v1";
    public static final String KEY_ENABLED = "app_gate_enabled";
    public static final String KEY_GATE_APPS = "app_gate_apps_lines";

    private static final String SELF_PACKAGE = "dev.linjian.peek";
    private static volatile String lastForegroundPackage = "";
    private static volatile long lastForegroundSince = 0;
    private static volatile long lastGateAt = 0;
    private static volatile String lastGatePackage = "";
    private static volatile View overlayView = null;
    private static volatile WindowManager overlayWindowManager = null;
    private static volatile String visibleLockActivityPackage = "";
    private static volatile long visibleLockActivityAt = 0;

    public static boolean enabled(Context ctx) { return AppPrefs.get(ctx).getBoolean(KEY_ENABLED, true); }

    private static JSONObject state(Context ctx) {
        try {
            String raw = AppPrefs.get(ctx).getString(KEY_STATE, "");
            if (raw != null && raw.trim().length() > 0) return new JSONObject(raw);
        } catch (Exception ignored) { }
        JSONObject s = new JSONObject();
        try { s.put("locks", new JSONObject()); s.put("requests", new JSONArray()); s.put("logs", new JSONArray()); } catch (Exception ignored) { }
        return s;
    }

    private static void save(Context ctx, JSONObject s) { AppPrefs.get(ctx).edit().putString(KEY_STATE, s.toString()).apply(); }

    private static JSONObject locks(JSONObject s) {
        JSONObject locks = s.optJSONObject("locks");
        if (locks == null) { locks = new JSONObject(); try { s.put("locks", locks); } catch (Exception ignored) { } }
        return locks;
    }

    public static void addGateApp(Context ctx, String alias, String pkg) {
        alias = alias == null ? "" : alias.trim(); pkg = pkg == null ? "" : pkg.trim();
        if (alias.length() == 0 || !AppPrefs.isPackageLike(pkg) || isProtectedPackage(ctx, pkg)) return;
        LinkedHashMap<String, String> apps = new LinkedHashMap<>();
        String old = AppPrefs.get(ctx).getString(KEY_GATE_APPS, "");
        for (String line : old.split("\\n")) {
            if (line == null || !line.contains("|")) continue;
            String[] p = line.split("\\|", 2);
            if (p.length == 2 && p[0].trim().length() > 0 && AppPrefs.isPackageLike(p[1].trim())) apps.put(p[0].trim(), p[1].trim());
        }
        apps.put(alias, pkg);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : apps.entrySet()) sb.append(e.getKey()).append("|").append(e.getValue()).append("\n");
        AppPrefs.get(ctx).edit().putString(KEY_GATE_APPS, sb.toString()).apply();
        AppPrefs.saveCustomApp(ctx, alias, pkg);
    }

    public static Map<String, String> gateApps(Context ctx) {
        LinkedHashMap<String, String> apps = new LinkedHashMap<>();
        apps.put("小红书", "com.xingin.xhs");
        apps.put("抖音", "com.ss.android.ugc.aweme");
        String raw = AppPrefs.get(ctx).getString(KEY_GATE_APPS, "");
        for (String line : raw.split("\\n")) {
            if (line == null || !line.contains("|")) continue;
            String[] p = line.trim().split("\\|", 2);
            if (p.length == 2 && p[0].trim().length() > 0 && AppPrefs.isPackageLike(p[1].trim())) apps.put(p[0].trim(), p[1].trim());
        }
        return apps;
    }

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        String action = cmd.optString("action", "");
        try {
            if ("add_locked_app".equals(action)) {
                String app = cmd.optString("app", cmd.optString("alias", ""));
                String pkg = resolvePackage(ctx, cmd);
                if (!AppPrefs.isPackageLike(pkg)) return put(out, false, "package_invalid");
                if (isProtectedPackage(ctx, pkg)) return put(out, false, "protected_package:" + pkg);
                addGateApp(ctx, app.length() == 0 ? labelOf(ctx, pkg) : app, pkg);
                log(ctx, "添加门禁 App：" + (app.length() == 0 ? labelOf(ctx, pkg) : app) + " → " + pkg);
                return put(out, true, "added_locked_app:" + pkg);
            }
            if ("remove_locked_app".equals(action)) {
                String pkg = resolvePackage(ctx, cmd);
                JSONObject s = state(ctx); locks(s).remove(pkg); save(ctx, s);
                log(ctx, "移除/解除门禁 App：" + pkg);
                return put(out, true, "removed_locked_app:" + pkg);
            }
            if ("lock_app".equals(action)) return lockApp(ctx, cmd);
            if ("unlock_app".equals(action)) return unlockApp(ctx, cmd, "remote_unlock");
            if ("temporary_unlock_app".equals(action)) return temporaryUnlock(ctx, cmd);
            if ("extend_lock".equals(action)) return extendLock(ctx, cmd);
            if ("deny_unlock_request".equals(action)) return denyUnlock(ctx, cmd);
            if ("get_lock_state".equals(action)) return put(out, true, config(ctx).toString());
            if ("set_emergency_passphrase".equals(action)) return setEmergencyPassphrase(ctx, cmd);
            if ("list_lockable_apps".equals(action)) return put(out, true, lockableAppsJson(ctx, cmd.optInt("max", 80)).toString());
        } catch (Exception e) { return put(out, false, ScreenshotService.shortMsg(e)); }
        return put(out, false, "unknown_app_gate_action:" + action);
    }

    private static double commandMinutes(JSONObject cmd, double fallback) {
        try {
            // 远程 MCP 的通用 send_phone_command 会把未填写的字段也带上，例如 minutes=0。
            // 旧逻辑只要看到 minutes 字段就立刻返回 fallback，导致 duration_minutes=1/2/60 被忽略，统一落到默认 30 分钟。
            // 这里改成“只有正数才采用”，0 或缺省继续往后看其它字段。
            double v = 0;
            if (cmd.has("duration_minutes")) { v = cmd.optDouble("duration_minutes", 0); if (v > 0) return v; }
            if (cmd.has("minutes")) { v = cmd.optDouble("minutes", 0); if (v > 0) return v; }
            if (cmd.has("extend_minutes")) { v = cmd.optDouble("extend_minutes", 0); if (v > 0) return v; }
            if (cmd.has("locked_minutes")) { v = cmd.optDouble("locked_minutes", 0); if (v > 0) return v; }
            if (cmd.has("duration_ms")) { v = cmd.optDouble("duration_ms", 0) / 60000.0; if (v > 0) return v; }
            if (cmd.has("duration_millis")) { v = cmd.optDouble("duration_millis", 0) / 60000.0; if (v > 0) return v; }
            if (cmd.has("duration_seconds")) { v = cmd.optDouble("duration_seconds", 0) / 60.0; if (v > 0) return v; }
            if (cmd.has("locked_until_ms")) {
                long until = cmd.optLong("locked_until_ms", 0);
                if (until > System.currentTimeMillis()) return Math.max(0.1, (until - System.currentTimeMillis()) / 60000.0);
            }
            if (cmd.has("duration")) {
                double d = cmd.optDouble("duration", -1);
                // 350 是点击/滑动命令的默认 duration，不应该被门禁误当成 350 分钟或 350 秒。
                if (d > 0 && Math.abs(d - 350.0) > 0.001) {
                    if (d >= 600000) return d / 60000.0; // 毫秒：7200000 = 2 小时
                    if (d >= 600) return d / 60.0;      // 秒：7200 = 2 小时
                    return d;                           // 小数字按分钟
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static double positive(double value, double fallback) {
        return value > 0 ? value : fallback;
    }

    private static JSONObject lockApp(Context ctx, JSONObject cmd) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        if (!AppPrefs.isPackageLike(pkg)) return put(new JSONObject(), false, "package_invalid");
        if (isProtectedPackage(ctx, pkg)) return put(new JSONObject(), false, "protected_package:" + pkg);
        String appName = cmd.optString("appName", cmd.optString("app_name", cmd.optString("app", labelOf(ctx, pkg))));
        long until = cmd.optLong("locked_until_ms", 0);
        if (until <= 0) {
            double minutes = commandMinutes(cmd, 30);
            until = System.currentTimeMillis() + Math.round(minutes * 60000.0);
        }
        JSONObject lock = new JSONObject();
        lock.put("package", pkg);
        lock.put("app_name", appName.length() == 0 ? labelOf(ctx, pkg) : appName);
        lock.put("active", true);
        lock.put("locked_until_ms", until);
        lock.put("locked_until_local", formatLocal(until));
        lock.put("mode", normalizeMode(cmd.optString("mode", "medium")));
        lock.put("reason", cmd.optString("reason", "").trim());
        lock.put("message", cmd.optString("message", "").trim());
        lock.put("created_at_ms", System.currentTimeMillis());
        lock.put("emergency_unlock_minutes", Math.max(1, cmd.optInt("emergencyUnlockMinutes", cmd.optInt("emergency_unlock_minutes", 5))));
        String pass = cmd.optString("emergencyPassphrase", cmd.optString("emergency_passphrase", ""));
        if (pass.length() > 0) lock.put("emergency_hash", hash(pass));
        clearTemp(lock);
        JSONObject s = state(ctx); locks(s).put(pkg, lock); save(ctx, s);
        addGateApp(ctx, lock.optString("app_name", labelOf(ctx, pkg)), pkg);
        log(ctx, "锁定 " + lock.optString("app_name") + " 到 " + lock.optString("locked_until_local") + "：" + lock.optString("reason"));
        triggerCurrentForegroundIfNeeded(ctx, pkg);
        return put(new JSONObject(), true, "locked_app:" + pkg + " until " + lock.optString("locked_until_local"));
    }

    private static JSONObject unlockApp(Context ctx, JSONObject cmd, String why) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l != null) { l.put("active", false); l.put("unlocked_at_ms", System.currentTimeMillis()); l.put("unlock_reason", why); }
        save(ctx, s); log(ctx, "解除门禁：" + pkg + "（" + why + "）");
        return put(new JSONObject(), true, "unlocked_app:" + pkg);
    }

    private static JSONObject temporaryUnlock(Context ctx, JSONObject cmd) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l == null) return put(new JSONObject(), false, "lock_not_found:" + pkg);
        long now = System.currentTimeMillis();
        String type = cmd.optString("allow_type", cmd.optString("type", "real_time"));
        if (!"foreground_usage".equals(type) && !"one_time".equals(type)) type = "real_time";
        double minutes = cmd.optDouble("allowed_minutes", cmd.optDouble("minutes", 10));
        if (minutes <= 0) minutes = 10;
        double maxWindow = cmd.optDouble("max_window_minutes", Math.max(minutes, 30));
        l.put("temporary_active", true);
        l.put("temporary_type", type);
        l.put("temporary_started_at_ms", now);
        l.put("temporary_until_ms", now + Math.round(minutes * 60000.0));
        l.put("temporary_window_until_ms", now + Math.round(maxWindow * 60000.0));
        l.put("temporary_allowed_ms", Math.round(minutes * 60000.0));
        l.put("temporary_used_ms", 0);
        l.put("temporary_session_started_ms", 0);
        l.put("temporary_one_time_used", false);
        save(ctx, s);
        log(ctx, "临时放行 " + l.optString("app_name", pkg) + "：" + minutes + " 分钟，type=" + type);
        return put(new JSONObject(), true, "temporary_unlocked:" + pkg + " " + minutes + "min type=" + type);
    }

    private static JSONObject extendLock(Context ctx, JSONObject cmd) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l == null) return put(new JSONObject(), false, "lock_not_found:" + pkg);
        long base = Math.max(System.currentTimeMillis(), l.optLong("locked_until_ms", System.currentTimeMillis()));
        long until = cmd.optLong("locked_until_ms", 0);
        if (until <= 0) until = base + Math.round(commandMinutes(cmd, 10) * 60000.0);
        l.put("locked_until_ms", until); l.put("locked_until_local", formatLocal(until)); l.put("active", true);
        if (cmd.optString("reason", "").length() > 0) l.put("reason", cmd.optString("reason"));
        if (cmd.optString("message", "").length() > 0) l.put("message", cmd.optString("message"));
        save(ctx, s); log(ctx, "延长门禁 " + pkg + " 到 " + formatLocal(until));
        return put(new JSONObject(), true, "extended_lock:" + pkg + " until " + formatLocal(until));
    }

    private static JSONObject denyUnlock(Context ctx, JSONObject cmd) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        String msg = cmd.optString("message", cmd.optString("reason", AppPrefs.companionName(ctx) + "拒绝了这次解锁申请。"));
        log(ctx, "拒绝解锁申请：" + pkg + "；" + msg);
        return put(new JSONObject(), true, "denied_unlock_request:" + pkg + ":" + msg);
    }

    private static JSONObject setEmergencyPassphrase(Context ctx, JSONObject cmd) throws Exception {
        String pkg = resolvePackage(ctx, cmd);
        String pass = cmd.optString("emergencyPassphrase", cmd.optString("emergency_passphrase", cmd.optString("passphrase", "")));
        if (pass.length() == 0) return put(new JSONObject(), false, "passphrase_empty");
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l == null) return put(new JSONObject(), false, "lock_not_found:" + pkg);
        l.put("emergency_hash", hash(pass)); save(ctx, s); log(ctx, "已更新紧急口令：" + pkg);
        return put(new JSONObject(), true, "emergency_passphrase_set:" + pkg);
    }

    public static void onForegroundPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        pkg = pkg.trim();
        if (!enabled(ctx)) return;
        long now = System.currentTimeMillis();
        try { accountUsageSwitch(ctx, pkg, now); } catch (Exception ignored) { }
        if (isProtectedPackage(ctx, pkg)) return;
        if (SELF_PACKAGE.equals(pkg)) return;
        try {
            JSONObject lock = activeLockFor(ctx, pkg, now);
            if (lock == null) return;
            if (isTemporarilyAllowed(ctx, lock, now, true)) return;
            if (pkg.equals(lastGatePackage) && now - lastGateAt < 1800) return;
            lastGatePackage = pkg; lastGateAt = now;
            showGateByPriority(ctx, pkg, lock);
            log(ctx, "门禁拦截：" + lock.optString("app_name", pkg) + "（全屏页优先，遮罩兜底，Home 最后兜底）");
            ActivityEventStore.recordPhone(ctx, "screen_break_trigger", "应用门禁触发", lock.optString("app_name", pkg));
        } catch (Exception e) { DebugState.append(ctx, "门禁检查异常：" + ScreenshotService.shortMsg(e)); }
    }

    private static boolean canDrawOverlay(Context ctx) {
        try { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx); }
        catch (Exception e) { return false; }
    }

    public static void markLockActivityVisible(String pkg, boolean visible) {
        if (visible) {
            visibleLockActivityPackage = pkg == null ? "" : pkg;
            visibleLockActivityAt = System.currentTimeMillis();
        } else {
            visibleLockActivityPackage = "";
            visibleLockActivityAt = 0;
        }
    }

    private static boolean isLockActivityVisibleFor(String pkg) {
        return pkg != null
                && pkg.equals(visibleLockActivityPackage)
                && System.currentTimeMillis() - visibleLockActivityAt < 3000;
    }

    private static void showGateByPriority(final Context ctx, final String pkg, final JSONObject lock) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());

        // v0.3.9.0：应用门禁优先级调整为「全屏锁定页 > 全屏悬浮遮罩 > 回到桌面」。
        // 这样 OPPO/ColorOS 上即使悬浮窗或后台弹层被系统限制，也会先尝试最强的 Activity 拦截。
        showLockActivity(app, pkg);

        main.postDelayed(() -> {
            if (isLockActivityVisibleFor(pkg)) return;
            if (canDrawOverlay(app)) {
                DebugState.append(app, "应用门禁：全屏锁定页未确认显示，改用全屏悬浮遮罩兜底；目标=" + pkg);
                showOverlayLock(app, pkg, lock);
            } else {
                goHome(app, "全屏锁定页未确认显示，且没有悬浮窗权限");
            }
        }, 700);

        main.postDelayed(() -> {
            if (isLockActivityVisibleFor(pkg)) return;
            if (overlayView != null) return;
            goHome(app, "全屏锁定页与悬浮遮罩均未确认显示");
        }, 1400);
    }

    private static void triggerCurrentForegroundIfNeeded(final Context ctx, final String lockedPkg) {
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String current = ScreenshotService.currentPackage();
                if (lockedPkg != null && lockedPkg.equals(current)) {
                    DebugState.append(app, "应用门禁：锁定后发现目标已在前台，立即触发拦截：" + lockedPkg);
                    onForegroundPackage(app, lockedPkg);
                }
            } catch (Exception e) {
                DebugState.append(app, "应用门禁：锁定后前台检查失败：" + ScreenshotService.shortMsg(e));
            }
        }, 200);
    }

    private static void goHome(Context ctx, String reason) {
        try {
            ScreenshotService svc = ScreenshotService.getInstance();
            if (svc != null) {
                svc.doHome();
                DebugState.append(ctx, "应用门禁兜底 Home：" + reason);
            } else {
                DebugState.append(ctx, "应用门禁兜底 Home 失败：无障碍服务未连接；" + reason);
            }
        } catch (Exception e) {
            DebugState.append(ctx, "应用门禁兜底 Home 异常：" + ScreenshotService.shortMsg(e));
        }
    }

    private static void showLockActivity(Context ctx, String pkg) {
        try {
            markLockActivityVisible(pkg, false);
            Intent i = new Intent(ctx, LockActivity.class);
            i.putExtra("package", pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            DebugState.append(ctx, "门禁启动全屏锁定页：" + pkg);
        } catch (Exception e) { DebugState.append(ctx, "门禁启动全屏锁定页失败：" + ScreenshotService.shortMsg(e)); }
    }

    private static void showOverlayLock(final Context ctx, final String pkg, final JSONObject lock) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                final Context app = ctx.getApplicationContext();
                final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) { goHome(ctx, "WindowManager 为空，悬浮遮罩无法显示"); return; }
                removeOverlay();

                // 悬浮窗兜底必须是「全屏触摸拦截层」，不能只是中间一张卡片。
                // 这样即使全屏 Activity 被系统限制弹不出来，底下的小红书/抖音也不会继续接到点击和滑动。
                LinearLayout root = new LinearLayout(app);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);
                root.setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22));
                root.setClickable(true);
                root.setFocusable(true);
                root.setBackgroundColor(0x88F2EAFB);
                root.setOnClickListener(v -> { /* 空白遮罩吃掉点击，不关闭也不透传 */ });

                LinearLayout card = new LinearLayout(app);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(ctx, 22), dp(ctx, 20), dp(ctx, 22), dp(ctx, 20));
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFDF6F8, 0xFFEAF6F1});
                bg.setCornerRadius(dp(ctx, 24));
                bg.setStroke(dp(ctx, 1), 0x66B8A8D8);
                card.setBackground(bg);
                card.setClickable(true);
                card.setOnClickListener(v -> { /* 卡片区域也不向下透传 */ });

                TextView title = new TextView(app);
                title.setText(lock.optString("app_name", labelOf(ctx, pkg)) + " 已被锁定");
                title.setTextColor(0xFF2D3E39);
                title.setTextSize(20);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setGravity(Gravity.CENTER);
                card.addView(title, new LinearLayout.LayoutParams(-1, -2));

                TextView msg = new TextView(app);
                String text = lock.optString("message", "先休息一下，等会儿再回来。");
                if (text == null || text.trim().isEmpty()) text = lock.optString("reason", "先休息一下，等会儿再回来。");
                msg.setText(text + "\n到 " + lock.optString("locked_until_local", "稍后") + " 自动解除。");
                msg.setTextColor(0xFF596D66);
                msg.setTextSize(13);
                msg.setGravity(Gravity.CENTER);
                msg.setLineSpacing(dp(ctx, 3), 1f);
                LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(-1, -2);
                msgLp.topMargin = dp(ctx, 10);
                card.addView(msg, msgLp);

                LinearLayout row = new LinearLayout(app);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(ctx, 38));
                rowLp.topMargin = dp(ctx, 16);

                Button home = overlayButton(app, "回到桌面", true);
                home.setOnClickListener(v -> { ScreenshotService svc = ScreenshotService.getInstance(); if (svc != null) svc.doHome(); removeOverlay(); });
                row.addView(home, new LinearLayout.LayoutParams(0, -1, 1f));

                Button detail = overlayButton(app, "查看详情", false);
                LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(0, -1, 1f);
                detailLp.leftMargin = dp(ctx, 8);
                detail.setOnClickListener(v -> {
                    removeOverlay();
                    showLockActivity(app, pkg);
                });
                row.addView(detail, detailLp);
                card.addView(row, rowLp);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        Math.max(dp(ctx, 280), app.getResources().getDisplayMetrics().widthPixels - dp(ctx, 34)),
                        -2);
                root.addView(card, cardLp);

                int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                wm.addView(root, lp);
                overlayView = root;
                overlayWindowManager = wm;
                DebugState.append(app, "门禁悬浮层已启动：touch_blocking=true；目标=" + pkg);
            } catch (Exception e) {
                DebugState.append(ctx, "门禁悬浮层失败，回到桌面兜底：" + ScreenshotService.shortMsg(e));
                goHome(ctx, "悬浮遮罩显示失败");
            }
        });
    }

    private static Button overlayButton(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : 0xFF596D66);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xFF8E74C8 : 0x22B8A8D8);
        bg.setCornerRadius(dp(ctx, 18));
        b.setBackground(bg);
        return b;
    }

    private static int dp(Context ctx, float value) { return Math.round(value * ctx.getResources().getDisplayMetrics().density); }

    private static void removeOverlay() {
        try {
            if (overlayWindowManager != null && overlayView != null) overlayWindowManager.removeView(overlayView);
        } catch (Exception ignored) { }
        overlayView = null;
        overlayWindowManager = null;
    }

    private static void accountUsageSwitch(Context ctx, String nextPkg, long now) throws Exception {
        String prev = lastForegroundPackage;
        long since = lastForegroundSince;
        if (prev != null && prev.length() > 0 && since > 0 && !prev.equals(nextPkg)) addForegroundUsage(ctx, prev, now - since);
        if (!nextPkg.equals(prev)) { lastForegroundPackage = nextPkg; lastForegroundSince = now; }
    }

    private static void addForegroundUsage(Context ctx, String pkg, long deltaMs) throws Exception {
        if (deltaMs <= 0 || deltaMs > 60 * 60 * 1000) return;
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l == null || !l.optBoolean("temporary_active", false)) return;
        if (!"foreground_usage".equals(l.optString("temporary_type", ""))) return;
        long used = Math.max(0, l.optLong("temporary_used_ms", 0)) + deltaMs;
        l.put("temporary_used_ms", used); l.put("temporary_session_started_ms", 0); save(ctx, s);
    }

    public static JSONObject activeLockFor(Context ctx, String pkg, long now) throws Exception {
        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l == null || !l.optBoolean("active", false)) return null;
        if (now >= l.optLong("locked_until_ms", 0)) { l.put("active", false); save(ctx, s); log(ctx, "门禁到时自动解除：" + pkg); return null; }
        return l;
    }

    public static JSONObject currentLock(Context ctx, String pkg) {
        try { return activeLockFor(ctx, pkg, System.currentTimeMillis()); } catch (Exception e) { return null; }
    }

    private static boolean isTemporarilyAllowed(Context ctx, JSONObject l, long now, boolean updateSession) throws Exception {
        if (!l.optBoolean("temporary_active", false)) return false;
        String type = l.optString("temporary_type", "real_time");
        if (now > l.optLong("temporary_window_until_ms", l.optLong("temporary_until_ms", 0))) { clearTemp(l); JSONObject ss = state(ctx); locks(ss).put(l.optString("package"), l); save(ctx, ss); return false; }
        if ("foreground_usage".equals(type)) {
            long used = l.optLong("temporary_used_ms", 0);
            long start = l.optLong("temporary_session_started_ms", 0);
            boolean changed = false;
            if (start <= 0 && updateSession) { l.put("temporary_session_started_ms", now); start = now; changed = true; }
            long live = start > 0 ? Math.max(0, now - start) : 0;
            if (used + live >= l.optLong("temporary_allowed_ms", 0)) { clearTemp(l); JSONObject ss = state(ctx); locks(ss).put(l.optString("package"), l); save(ctx, ss); return false; }
            if (changed) {
                JSONObject s = state(ctx); locks(s).put(l.optString("package"), l); save(ctx, s);
            }
            return true;
        }
        if ("one_time".equals(type)) {
            if (l.optBoolean("temporary_one_time_used", false)) return false;
            if (updateSession) { l.put("temporary_one_time_used", true); JSONObject s = state(ctx); locks(s).put(l.optString("package"), l); save(ctx, s); }
            return true;
        }
        if (now < l.optLong("temporary_until_ms", 0)) return true;
        clearTemp(l); JSONObject s = state(ctx); locks(s).put(l.optString("package"), l); save(ctx, s); return false;
    }

    private static void clearTemp(JSONObject l) throws Exception {
        l.put("temporary_active", false); l.put("temporary_type", ""); l.put("temporary_started_at_ms", 0); l.put("temporary_until_ms", 0);
        l.put("temporary_window_until_ms", 0); l.put("temporary_allowed_ms", 0); l.put("temporary_used_ms", 0); l.put("temporary_session_started_ms", 0); l.put("temporary_one_time_used", false);
    }

    public static boolean tryEmergencyUnlock(Context ctx, String pkg, String passphrase) {
        try {
            JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
            if (l == null) return false;
            String stored = l.optString("emergency_hash", "");
            if (stored.length() == 0 || !stored.equals(hash(passphrase == null ? "" : passphrase))) return false;
            JSONObject cmd = new JSONObject(); cmd.put("package", pkg); cmd.put("minutes", Math.max(1, l.optInt("emergency_unlock_minutes", 5))); cmd.put("allow_type", "real_time");
            temporaryUnlock(ctx, cmd); log(ctx, "紧急口令解锁成功：" + pkg);
            return true;
        } catch (Exception e) { return false; }
    }

    public static void submitUnlockRequest(final Context ctx, final String pkg, final String reason) {
        try {
            JSONObject s = state(ctx); JSONObject req = new JSONObject();
            req.put("id", String.valueOf(System.currentTimeMillis())); req.put("device_id", AppPrefs.device(ctx)); req.put("package", pkg);
            req.put("app_name", labelOf(ctx, pkg)); req.put("reason", reason == null ? "" : reason.trim()); req.put("created_at_ms", System.currentTimeMillis()); req.put("created_at_local", formatLocal(System.currentTimeMillis()));
            JSONArray arr = s.optJSONArray("requests"); if (arr == null) arr = new JSONArray(); arr.put(req); while (arr.length() > 20) arr.remove(0); s.put("requests", arr); save(ctx, s);
            log(ctx, "提交解锁申请：" + pkg + "；理由：" + reason);
            final String url = ScreenshotService.normalizeUrl(AppPrefs.server(ctx)); final String token = AppPrefs.token(ctx); final String body = req.toString();
            if (url.length() > 0 && token.length() > 0) new Thread(() -> postUnlockRequest(url, token, body)).start();
        } catch (Exception e) { DebugState.append(ctx, "解锁申请保存失败：" + ScreenshotService.shortMsg(e)); }
    }

    private static void postUnlockRequest(String serverUrl, String token, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection)new URL(serverUrl + "/api/appgate/unlock_request").openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.setRequestProperty("X-Auth-Token", token); conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(12000);
            byte[] data = body.getBytes(StandardCharsets.UTF_8); try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode(); InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream(); if (is != null) { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[256]; while (is.read(buf) > 0) {} }
            conn.disconnect();
        } catch (Exception ignored) { }
    }

    public static JSONObject config(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled(ctx)); out.put("gate_apps", gateAppsJson(ctx)); out.put("state", state(ctx)); out.put("protected_packages", protectedJson(ctx));
        } catch (Exception ignored) { }
        return out;
    }

    public static String summaryLine(Context ctx) {
        try {
            boolean on = enabled(ctx);
            JSONObject s = state(ctx); JSONObject ls = locks(s); Iterator<String> it = ls.keys(); boolean any = false; String app = ""; long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String pkg = it.next(); JSONObject l = ls.optJSONObject(pkg); if (l == null || !l.optBoolean("active", false) || now >= l.optLong("locked_until_ms", 0)) continue;
                any = true; app = l.optString("app_name", pkg); break;
            }
            return "应用门禁 · " + (on ? "已开启" : "已关闭") + " · " + (any ? (app + "锁定中") : "暂无锁定");
        } catch (Exception e) { return "应用门禁 · 读取中"; }
    }

    public static String prettyClean(Context ctx) {
        try {
            JSONObject s = state(ctx); StringBuilder sb = new StringBuilder();
            sb.append("应用门禁：").append(enabled(ctx) ? "已开启" : "已关闭").append("\n");
            sb.append("可锁 App：");
            boolean first = true;
            for (Map.Entry<String, String> e : gateApps(ctx).entrySet()) { if (!first) sb.append("、"); sb.append(e.getKey()); first = false; }
            sb.append("\n\n当前锁定：\n");
            JSONObject ls = locks(s); Iterator<String> it = ls.keys(); boolean any = false; long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String pkg = it.next(); JSONObject l = ls.optJSONObject(pkg); if (l == null || !l.optBoolean("active", false) || now >= l.optLong("locked_until_ms", 0)) continue;
                any = true; sb.append("- ").append(l.optString("app_name", pkg)).append(" 到 ").append(l.optString("locked_until_local", "-")).append("｜").append(l.optString("mode", "medium")).append("\n  ").append(l.optString("reason", "")).append("\n");
            }
            if (!any) sb.append("暂无正在锁定的 App。\n");
            JSONArray req = s.optJSONArray("requests");
            if (req != null && req.length() > 0) sb.append("\n最近解锁申请：").append(req.length()).append(" 条");
            return sb.toString().trim();
        } catch (Exception e) { return "应用门禁读取失败：" + ScreenshotService.shortMsg(e); }
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject s = state(ctx); StringBuilder sb = new StringBuilder();
            sb.append("应用门禁：").append(enabled(ctx) ? "已开启" : "已关闭").append("\n");
            sb.append("可选门禁 App：\n");
            for (Map.Entry<String, String> e : gateApps(ctx).entrySet()) sb.append("- ").append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
            sb.append("\n当前锁定：\n");
            JSONObject ls = locks(s); Iterator<String> it = ls.keys(); boolean any = false; long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String pkg = it.next(); JSONObject l = ls.optJSONObject(pkg); if (l == null || !l.optBoolean("active", false) || now >= l.optLong("locked_until_ms", 0)) continue;
                any = true; sb.append("- ").append(l.optString("app_name", pkg)).append(" 到 ").append(l.optString("locked_until_local", "-")).append("｜").append(l.optString("mode", "medium")).append("\n  ").append(l.optString("reason", "")).append("\n");
            }
            if (!any) sb.append("暂无正在锁定的 App。\n");
            JSONArray req = s.optJSONArray("requests");
            if (req != null && req.length() > 0) sb.append("\n最近解锁申请：").append(req.length()).append(" 条");
            return sb.toString().trim();
        } catch (Exception e) { return "应用门禁读取失败：" + ScreenshotService.shortMsg(e); }
    }

    private static JSONObject gateAppsJson(Context ctx) throws Exception { JSONObject o = new JSONObject(); for (Map.Entry<String, String> e : gateApps(ctx).entrySet()) o.put(e.getKey(), e.getValue()); return o; }

    private static JSONArray protectedJson(Context ctx) throws Exception {
        JSONArray arr = new JSONArray(); String[] p = protectedPackages(ctx); for (String one : p) arr.put(one); return arr;
    }

    private static String[] protectedPackages(Context ctx) {
        ArrayList<String> packages = new ArrayList<>();
        packages.add(SELF_PACKAGE);
        String companionTarget = AppPrefs.homeTargetPackage(ctx);
        if (!companionTarget.isEmpty()) packages.add(companionTarget);
        packages.add("com.android.settings");
        packages.add("com.android.phone");
        packages.add("com.google.android.dialer");
        packages.add("com.android.contacts");
        packages.add("com.android.mms");
        packages.add("com.eg.android.AlipayGphone");
        return packages.toArray(new String[0]);
    }

    private static boolean isProtectedPackage(Context ctx, String pkg) {
        if (pkg == null) return true; String p = pkg.trim(); if (p.length() == 0) return true;
        for (String one : protectedPackages(ctx)) if (p.equals(one)) return true;
        return false;
    }

    private static JSONArray lockableAppsJson(Context ctx, int max) throws Exception {
        JSONArray arr = new JSONArray(); PackageManager pm = ctx.getPackageManager();
        int limit = max <= 0 ? 80 : Math.min(max, 200); int count = 0;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai == null || ai.packageName == null || isProtectedPackage(ctx, ai.packageName)) continue;
            Intent launch = pm.getLaunchIntentForPackage(ai.packageName); if (launch == null) continue;
            JSONObject o = new JSONObject(); CharSequence label = pm.getApplicationLabel(ai);
            o.put("app", label == null ? ai.packageName : label.toString()); o.put("package", ai.packageName); arr.put(o);
            if (++count >= limit) break;
        }
        return arr;
    }

    private static String resolvePackage(Context ctx, JSONObject cmd) {
        String pkg = cmd.optString("package", cmd.optString("pkg", ""));
        if (AppPrefs.isPackageLike(pkg)) return pkg.trim();
        return AppPrefs.packageForApp(ctx, cmd.optString("app", cmd.optString("appName", cmd.optString("app_name", ""))));
    }

    public static String labelOf(Context ctx, String pkg) {
        try { PackageManager pm = ctx.getPackageManager(); ApplicationInfo ai = pm.getApplicationInfo(pkg, 0); CharSequence label = pm.getApplicationLabel(ai); return label == null ? pkg : label.toString(); }
        catch (Exception e) { return pkg == null ? "" : pkg; }
    }

    private static String normalizeMode(String m) { if ("light".equals(m) || "strict".equals(m)) return m; return "medium"; }

    private static JSONObject put(JSONObject out, boolean ok, String result) { try { out.put("ok", ok); out.put("result", result); } catch (Exception ignored) { } return out; }

    private static void log(Context ctx, String msg) {
        DebugState.append(ctx, "应用门禁：" + msg);
        try {
            JSONObject s = state(ctx); JSONArray logs = s.optJSONArray("logs"); if (logs == null) logs = new JSONArray();
            JSONObject o = new JSONObject(); o.put("time_ms", System.currentTimeMillis()); o.put("time", formatLocal(System.currentTimeMillis())); o.put("message", msg); logs.put(o); while (logs.length() > 60) logs.remove(0); s.put("logs", logs); save(ctx, s);
        } catch (Exception ignored) { }
    }

    private static String hash(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff)); return sb.toString();
    }

    private static String formatLocal(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms)); }
}
