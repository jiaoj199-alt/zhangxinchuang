package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Date;
import java.util.Locale;

/** 共同窗语、陪伴对象行动摘要和今日轨迹。 */
public final class CompanionWindowState {
    public static final String KEY_CACHE = "companion_window_cache_v1";
    public static final String KEY_JOURNEY = "today_journey_v1";
    public static final String KEY_JOURNEY_DAY = "today_journey_day_v1";

    public interface Callback { void done(JSONObject state, String error); }

    private CompanionWindowState() { }

    public static JSONObject cached(Context ctx) {
        try {
            String raw = AppPrefs.get(ctx).getString(KEY_CACHE, "");
            return raw == null || raw.trim().isEmpty() ? defaultState(ctx) : new JSONObject(raw);
        } catch (Exception ignored) { return defaultState(ctx); }
    }

    public static JSONObject whisper(Context ctx) {
        JSONObject w = cached(ctx).optJSONObject("whisper");
        return w == null ? defaultState(ctx).optJSONObject("whisper") : w;
    }

    public static JSONArray actions(Context ctx) {
        JSONArray merged = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        appendUnique(merged, seen, ActivityEventStore.companionActions(ctx, 500));
        appendUnique(merged, seen, cached(ctx).optJSONArray("actions"));
        return merged;
    }

    private static void appendUnique(JSONArray out, java.util.HashSet<String> seen, JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) id = item.optString("created_at", item.optString("at", "")) + "|" + item.optString("title", "") + "|" + item.optString("action", "");
            if (seen.add(id)) out.put(item);
        }
    }

    public static void sync(Context ctx, int limit, Callback callback) {
        new Thread(() -> {
            try {
                JSONObject state = request(ctx, "GET", "/api/companion/state?limit=" + Math.max(1, Math.min(50, limit)), null);
                try {
                    JSONObject events = request(ctx, "GET", "/api/activity/events?limit=500", null);
                    ActivityEventStore.mergeRemote(ctx, events.optJSONArray("events"));
                } catch (Exception ignored) { }
                AppPrefs.get(ctx).edit().putString(KEY_CACHE, state.toString()).apply();
                callback.done(state, "");
            } catch (Exception e) {
                callback.done(cached(ctx), ScreenshotService.shortMsg(e));
            }
        }, "companion-window-sync").start();
    }

    public static void updateWhisper(Context ctx, String content, String author, Callback callback) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.isEmpty()) { callback.done(cached(ctx), "窗语不能为空"); return; }
        String editor = author == null ? "用户" : author;
        JSONObject local = cached(ctx);
        try {
            JSONObject previous = local.optJSONObject("whisper");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            local.put("whisper", new JSONObject()
                    .put("content", cleaned)
                    .put("author", editor)
                    .put("updated_at", iso.format(new Date()))
                    .put("version", (previous == null ? 0 : previous.optInt("version", 0)) + 1));
            AppPrefs.get(ctx).edit().putString(KEY_CACHE, local.toString()).apply();
            recordJourney(ctx, "修改共同窗语", "你在窗边留下了一句话");
        } catch (Exception ignored) { }
        String base = AppPrefs.server(ctx);
        final JSONObject localState = local;
        if (base == null || base.trim().isEmpty()) { callback.done(localState, ""); return; }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("content", cleaned).put("author", editor);
                JSONObject response = request(ctx, "POST", "/api/companion/whisper", body);
                JSONObject state = cached(ctx);
                state.put("whisper", response.optJSONObject("whisper"));
                AppPrefs.get(ctx).edit().putString(KEY_CACHE, state.toString()).apply();
                callback.done(state, "");
            } catch (Exception e) {
                callback.done(localState, ScreenshotService.shortMsg(e));
            }
        }, "companion-window-write").start();
    }

    public static synchronized void recordJourney(Context ctx, String title, String detail) {
        ActivityEventStore.recordPhone(ctx, eventType(title), title, detail);
        try {
            SharedPreferences p = AppPrefs.get(ctx);
            if (!p.getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            JSONArray items = today.equals(p.getString(KEY_JOURNEY_DAY, "")) ? new JSONArray(p.getString(KEY_JOURNEY, "[]")) : new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("at", System.currentTimeMillis());
            entry.put("time", new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            entry.put("title", clip(title, 48));
            entry.put("detail", clip(detail, 100));
            items.put(entry);
            JSONArray kept = new JSONArray();
            for (int i = Math.max(0, items.length() - 16); i < items.length(); i++) kept.put(items.optJSONObject(i));
            p.edit().putString(KEY_JOURNEY_DAY, today).putString(KEY_JOURNEY, kept.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static JSONArray journey(Context ctx) {
        if (!AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return new JSONArray();
        JSONArray unified = ActivityEventStore.todayJourney(ctx, 500);
        if (unified.length() > 0) return unified;
        try {
            SharedPreferences p = AppPrefs.get(ctx);
            if (!p.getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return new JSONArray();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            if (!today.equals(p.getString(KEY_JOURNEY_DAY, ""))) return new JSONArray();
            return new JSONArray(p.getString(KEY_JOURNEY, "[]"));
        } catch (Exception ignored) { return new JSONArray(); }
    }

    private static String eventType(String title) {
        String value = title == null ? "" : title;
        if (value.contains("归电") || value.contains("回来")) return "guidian_return";
        if (value.contains("门禁") || value.contains("休息")) return "screen_break_trigger";
        if (value.contains("窗语")) return "whisper_update";
        return "phone_activity";
    }

    public static String elapsed(String iso) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date parsed = parser.parse(iso);
            if (parsed == null) return "";
            long time = parsed.getTime();
            long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000L);
            if (minutes < 1) return "刚刚";
            if (minutes < 60) return minutes + " 分钟前";
            long hours = minutes / 60;
            if (hours < 24) return hours + " 小时前";
            return (hours / 24) + " 天前";
        } catch (Exception ignored) {
            return iso == null || iso.isEmpty() ? "" : iso.replace('T', ' ').replace("Z", "");
        }
    }

    private static JSONObject defaultState(Context ctx) {
        JSONObject state = new JSONObject();
        try {
            state.put("ok", true);
            state.put("whisper", new JSONObject().put("content", "把今天，轻轻收进窗里。").put("author", AppPrefs.companionName(ctx)).put("updated_at", "").put("version", 1));
            state.put("actions", new JSONArray());
        } catch (Exception ignored) { }
        return state;
    }

    private static JSONObject request(Context ctx, String method, String path, JSONObject body) throws Exception {
        String base = AppPrefs.server(ctx);
        String token = AppPrefs.token(ctx);
        if (base == null || base.trim().isEmpty()) throw new Exception("请先设置服务器地址");
        String url = base.replaceAll("/+$", "") + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("X-Auth-Token", token == null ? "" : token);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        }
        int code = conn.getResponseCode();
        InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = input.read(buf)) > 0) bytes.write(buf, 0, n);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new Exception("HTTP " + code + " " + clip(text, 100));
        return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String clip(String value, int limit) {
        String s = value == null ? "" : value.trim();
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}
