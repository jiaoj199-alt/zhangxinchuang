package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 公开模板的本地陪伴状态：名字、最近一句话、相识日期和通用行动记录。 */
public final class PublicCompanionState {
    public static final String KEY_WHISPER = "public_companion_whisper";
    public static final String KEY_WHISPER_UPDATED_AT = "public_companion_whisper_updated_at";
    public static final String KEY_FIRST_DAY = "public_companion_first_day";

    private PublicCompanionState() { }

    public static JSONObject whisper(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            String text = AppPrefs.get(ctx).getString(KEY_WHISPER, "今天也慢慢来，我会陪着你。");
            long updatedAt = AppPrefs.get(ctx).getLong(KEY_WHISPER_UPDATED_AT, 0L);
            out.put("content", text == null || text.trim().isEmpty() ? "今天也慢慢来，我会陪着你。" : text.trim());
            out.put("author", AppPrefs.companionName(ctx));
            out.put("updated_at_ms", updatedAt);
            out.put("updated_text", elapsed(updatedAt));
        } catch (Exception ignored) { }
        return out;
    }

    public static void saveWhisper(Context ctx, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        AppPrefs.get(ctx).edit().putString(KEY_WHISPER, text).putLong(KEY_WHISPER_UPDATED_AT, System.currentTimeMillis()).apply();
        recordCompanionAction(ctx, "留下最近一句话", text);
    }

    public static String firstDay(Context ctx) {
        return AppPrefs.get(ctx).getString(KEY_FIRST_DAY, "").trim();
    }

    public static boolean saveFirstDay(Context ctx, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) { AppPrefs.get(ctx).edit().remove(KEY_FIRST_DAY).apply(); return true; }
        if (parseDate(value) == null) return false;
        AppPrefs.get(ctx).edit().putString(KEY_FIRST_DAY, value).apply();
        return true;
    }

    public static int daysTogether(Context ctx) {
        Date first = parseDate(firstDay(ctx));
        if (first == null) return 0;
        long days = (startOfDay(System.currentTimeMillis()) - startOfDay(first.getTime())) / 86400000L;
        return (int) Math.max(0, days + 1);
    }

    public static JSONObject nextAnniversary(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            Date first = parseDate(firstDay(ctx));
            if (first == null) return out.put("configured", false).put("title", "尚未设置纪念日");
            Calendar source = Calendar.getInstance(); source.setTime(first);
            Calendar today = Calendar.getInstance();
            Calendar next = Calendar.getInstance();
            next.set(Calendar.MONTH, source.get(Calendar.MONTH));
            next.set(Calendar.DAY_OF_MONTH, source.get(Calendar.DAY_OF_MONTH));
            next.set(Calendar.HOUR_OF_DAY, 0); next.set(Calendar.MINUTE, 0); next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
            if (next.before(startCalendar(today))) next.add(Calendar.YEAR, 1);
            int days = (int) Math.max(0, (next.getTimeInMillis() - startOfDay(System.currentTimeMillis())) / 86400000L);
            out.put("configured", true).put("title", "相识纪念日").put("date", new SimpleDateFormat("MM-dd", Locale.US).format(next.getTime())).put("days_left", days);
        } catch (Exception ignored) { }
        return out;
    }

    public static JSONArray actions(Context ctx, int limit) {
        return ActivityEventStore.companionActions(ctx, Math.max(1, limit));
    }

    public static void recordCompanionAction(Context ctx, String title, String subtitle) {
        try {
            ActivityEventStore.add(ctx, new JSONObject()
                    .put("source", "companion")
                    .put("type", "companion_action")
                    .put("title", title == null ? "一条行动" : title)
                    .put("subtitle", subtitle == null ? "" : subtitle)
                    .put("status", "completed"), true);
        } catch (Exception ignored) { }
    }

    private static Date parseDate(String raw) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            return format.parse(raw == null ? "" : raw.trim());
        } catch (Exception ignored) { return null; }
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Calendar startCalendar(Calendar source) {
        Calendar c = (Calendar) source.clone();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String elapsed(long updatedAt) {
        if (updatedAt <= 0) return "本机默认窗语";
        long minutes = Math.max(0, (System.currentTimeMillis() - updatedAt) / 60000L);
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return (hours / 24) + " 天前";
    }
}
