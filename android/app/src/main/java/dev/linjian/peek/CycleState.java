package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 生理期轻提醒：只用本地填写的周期信息推算，不联网。 */
public class CycleState {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    public static JSONObject collect(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = AppPrefs.get(ctx);
            boolean enabled = p.getBoolean(AppPrefs.KEY_CYCLE_ENABLED, false);
            String lastStart = p.getString(AppPrefs.KEY_LAST_PERIOD_START, "");
            int cycleLen = clamp(p.getInt(AppPrefs.KEY_CYCLE_LENGTH, 30), 15, 60);
            int periodLen = clamp(p.getInt(AppPrefs.KEY_PERIOD_LENGTH, 6), 1, 14);
            int remindBefore = clamp(p.getInt(AppPrefs.KEY_CYCLE_REMIND_BEFORE, 3), 0, 14);
            o.put("cycle_enabled", enabled);
            o.put("last_period_start", lastStart == null ? "" : lastStart.trim());
            o.put("cycle_length_days", cycleLen);
            o.put("period_length_days", periodLen);
            o.put("remind_before_days", remindBefore);
            o.put("configured", enabled && lastStart != null && lastStart.trim().length() > 0);
            if (!enabled || lastStart == null || lastStart.trim().length() == 0) {
                o.put("summary", "生理期提醒未开启或未填写上次开始日期。");
                return o;
            }

            long last = parseDateStart(lastStart.trim());
            if (last <= 0) {
                o.put("date_error", "请用 yyyy-MM-dd，例如 2026-07-01");
                o.put("summary", "上次开始日期格式不对，请用 yyyy-MM-dd。");
                return o;
            }
            long today = startOfToday();
            long daysSince = Math.floorDiv(today - last, DAY_MS);
            long normalized = daysSince >= 0 ? daysSince % cycleLen : 0;
            int cycleDay = (int) normalized + 1;
            boolean periodNow = daysSince >= 0 && cycleDay <= periodLen;
            int periodDay = periodNow ? cycleDay : 0;

            long next = last;
            if (next < today) {
                long passedCycles = Math.floorDiv(today - last, cycleLen * DAY_MS);
                next = last + passedCycles * cycleLen * DAY_MS;
                while (next < today) next += cycleLen * DAY_MS;
            }
            int daysUntil = (int) Math.round((next - today) / (double) DAY_MS);
            String phase;
            if (periodNow) phase = "生理期第" + periodDay + "天";
            else if (daysUntil >= 0 && daysUntil <= remindBefore) phase = "预计快来了";
            else if (cycleDay <= Math.max(periodLen + 3, 14)) phase = "周期前半";
            else phase = "周期后半";

            o.put("days_since_last_start", daysSince);
            o.put("cycle_day", cycleDay);
            o.put("is_period_now", periodNow);
            o.put("period_day", periodDay);
            o.put("next_period_start", formatDate(next));
            o.put("days_until_next", daysUntil);
            o.put("current_phase", phase);
            if (periodNow) o.put("summary", "现在是" + phase + "，记得温柔照顾自己，注意热敷和休息。");
            else if (daysUntil >= 0 && daysUntil <= remindBefore) o.put("summary", "预计还有 " + daysUntil + " 天来，少吃冰的，提前准备姨妈巾和热水袋。");
            else o.put("summary", "当前阶段：" + phase + "，预计下次 " + formatDate(next) + "。");
        } catch (Exception e) {
            try { o.put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return o;
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject c = collect(ctx);
            if (!c.optBoolean("cycle_enabled", false)) return "生理期提醒：未开启";
            if (c.optString("date_error", "").length() > 0) return "生理期提醒：" + c.optString("date_error");
            StringBuilder sb = new StringBuilder();
            sb.append("生理期提醒：").append(c.optString("current_phase", "-")).append("\n");
            sb.append("上次开始：").append(c.optString("last_period_start", "-")).append("  预计下次：").append(c.optString("next_period_start", "-")).append("\n");
            sb.append("距离预计下次：").append(c.optInt("days_until_next", -1)).append(" 天  周期第 ").append(c.optInt("cycle_day", 0)).append(" 天\n");
            sb.append(c.optString("summary", ""));
            return sb.toString();
        } catch (Exception e) { return "生理期提醒读取失败：" + ScreenshotService.shortMsg(e); }
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static long parseDateStart(String s) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setLenient(false);
            Date d = sdf.parse(s);
            if (d == null) return -1;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) { return -1; }
    }

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String formatDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }
}
