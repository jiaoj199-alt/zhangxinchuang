package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 天气地区层：手机端保存多个地区和当前地区；v0.3.8.2 会缓存真实天气，失败时回退到备注建议。 */
public class WeatherState {
    public static final String DEFAULT_LOCATIONS = "家|石家庄||1\n学校|石家庄||0\n常去地|||0\n";

    public static JSONObject collect(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = locationsJson(ctx);
            JSONObject current = currentLocation(ctx);
            o.put("enabled", true);
            o.put("current", current);
            o.put("locations", arr);
            JSONObject live = WeatherLive.cached(ctx, current.optString("city", ""));
            if (live != null) o.put("live", live);
            o.put("summary", pretty(ctx));
            o.put("outdoor_advice", live != null && live.optBoolean("ok") ? WeatherLive.advice(live, current.optString("name", "当前地区")) : localAdvice(current.optString("note", "")));
        } catch (Exception ignored) { }
        return o;
    }

    public static JSONArray locationsJson(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            for (Location loc : readLocations(ctx).values()) {
                JSONObject o = new JSONObject();
                o.put("name", loc.name);
                o.put("city", loc.city);
                o.put("note", loc.note);
                o.put("current", loc.current);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr;
    }

    public static JSONObject currentLocation(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Location chosen = null;
            for (Location loc : readLocations(ctx).values()) if (loc.current) { chosen = loc; break; }
            if (chosen == null) {
                for (Location loc : readLocations(ctx).values()) { chosen = loc; break; }
            }
            if (chosen == null) chosen = new Location("家", AppPrefs.get(ctx).getString(AppPrefs.KEY_CITY, ""), AppPrefs.get(ctx).getString(AppPrefs.KEY_WEATHER_NOTE, ""), true);
            o.put("name", chosen.name);
            o.put("city", chosen.city);
            o.put("note", chosen.note);
            o.put("current", true);
        } catch (Exception ignored) { }
        return o;
    }

    public static String summaryLine(Context ctx) {
        try {
            JSONObject cur = currentLocation(ctx);
            String name = cur.optString("name", "当前地区");
            String city = cur.optString("city", "");
            return "天气地区 · " + name + (city.isEmpty() ? " · 未设城市" : (" · " + city));
        } catch (Exception e) { return "天气地区 · 读取中"; }
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject cur = currentLocation(ctx);
            String name = cur.optString("name", "当前地区");
            String city = cur.optString("city", "");
            String note = cur.optString("note", "");
            StringBuilder sb = new StringBuilder();
            sb.append("当前地区：").append(name);
            if (!city.isEmpty()) sb.append(" · ").append(city);
            if (!note.isEmpty()) sb.append("\n天气备注：").append(note);
            sb.append("\n").append(localAdvice(note));
            return sb.toString();
        } catch (Exception e) { return "天气地区读取失败：" + ScreenshotService.shortMsg(e); }
    }

    public static String locationsText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Location loc : readLocations(ctx).values()) {
            sb.append(loc.current ? "✓ " : "  ").append(loc.name);
            if (!loc.city.isEmpty()) sb.append(" · ").append(loc.city);
            if (!loc.note.isEmpty()) sb.append(" · ").append(loc.note);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static void saveLocation(Context ctx, String name, String city, String note, boolean makeCurrent) {
        name = clean(name); city = clean(city); note = clean(note);
        if (name.isEmpty()) name = city.isEmpty() ? "新地区" : city;
        LinkedHashMap<String, Location> map = readLocations(ctx);
        if (makeCurrent) for (Location loc : map.values()) loc.current = false;
        Location old = map.get(name);
        if (old == null) old = new Location(name, city, note, makeCurrent);
        old.city = city; old.note = note; old.current = makeCurrent || old.current;
        map.put(name, old);
        writeLocations(ctx, map);
        if (old.current) syncLegacy(ctx, old);
    }

    public static boolean setCurrent(Context ctx, String name) {
        name = clean(name);
        if (name.isEmpty()) return false;
        LinkedHashMap<String, Location> map = readLocations(ctx);
        Location found = map.get(name);
        if (found == null) return false;
        for (Location loc : map.values()) loc.current = false;
        found.current = true;
        writeLocations(ctx, map);
        syncLegacy(ctx, found);
        return true;
    }

    private static void syncLegacy(Context ctx, Location loc) {
        AppPrefs.get(ctx).edit().putString(AppPrefs.KEY_CITY, loc.city).putString(AppPrefs.KEY_WEATHER_NOTE, loc.note).apply();
    }

    private static LinkedHashMap<String, Location> readLocations(Context ctx) {
        SharedPreferences p = AppPrefs.get(ctx);
        String raw = p.getString(AppPrefs.KEY_WEATHER_LOCATIONS, "");
        if (raw == null || raw.trim().isEmpty()) raw = DEFAULT_LOCATIONS;
        LinkedHashMap<String, Location> map = new LinkedHashMap<>();
        for (String line : raw.split("\\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            String name = parts.length > 0 ? clean(parts[0]) : "";
            String city = parts.length > 1 ? clean(parts[1]) : "";
            String note = parts.length > 2 ? clean(parts[2]) : "";
            boolean current = parts.length > 3 && "1".equals(parts[3].trim());
            if (name.isEmpty()) continue;
            map.put(name, new Location(name, city, note, current));
        }
        if (map.isEmpty()) {
            map.put("家", new Location("家", p.getString(AppPrefs.KEY_CITY, "石家庄"), p.getString(AppPrefs.KEY_WEATHER_NOTE, ""), true));
            map.put("学校", new Location("学校", "石家庄", "", false));
            map.put("常去地", new Location("常去地", "", "", false));
        }
        boolean hasCurrent = false;
        for (Location loc : map.values()) if (loc.current) { hasCurrent = true; break; }
        if (!hasCurrent) {
            for (Location loc : map.values()) { loc.current = true; break; }
        }
        return map;
    }

    private static void writeLocations(Context ctx, LinkedHashMap<String, Location> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Location> e : map.entrySet()) {
            Location l = e.getValue();
            sb.append(escape(l.name)).append("|").append(escape(l.city)).append("|").append(escape(l.note)).append("|").append(l.current ? "1" : "0").append("\n");
        }
        AppPrefs.get(ctx).edit().putString(AppPrefs.KEY_WEATHER_LOCATIONS, sb.toString()).apply();
    }

    public static String localAdvice(String note) {
        String n = note == null ? "" : note;
        if (n.contains("雨") || n.contains("雪")) return "出门建议：伞拿上，别淋湿。";
        if (n.contains("降温") || n.contains("冷") || n.contains("低温")) return "出门建议：外套带着，别嘴硬。";
        if (n.contains("热") || n.contains("高温") || n.contains("晒")) return "出门建议：水杯和防晒记得带。";
        if (n.contains("风") || n.contains("大风")) return "出门建议：风大，头发和外套都顾一下。";
        if (n.contains("空气") || n.contains("霾") || n.contains("污染")) return "出门建议：空气一般，少在外面吹太久。";
        return "出门建议：当前地区已设好，查看掌心窗时会一起显示。";
    }

    private static String clean(String s) { return s == null ? "" : s.trim().replace("|", " ").replace("\n", " "); }
    private static String escape(String s) { return clean(s); }

    private static class Location {
        String name; String city; String note; boolean current;
        Location(String name, String city, String note, boolean current) { this.name = name; this.city = city; this.note = note; this.current = current; }
    }
}
