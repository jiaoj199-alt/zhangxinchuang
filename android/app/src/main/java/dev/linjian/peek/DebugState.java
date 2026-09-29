package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugState {
    private static final String PREFS = "linjian_peek";
    private static final String KEY_DEBUG = "debug_text";

    public static void set(Context ctx, String message) {
        if (ctx == null) return;
        String line = now() + "  " + message;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEBUG, line)
                .apply();
    }

    public static void append(Context ctx, String message) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY_DEBUG, "");
        String line = now() + "  " + message;
        String next = old == null || old.isEmpty() ? line : old + "\n" + line;
        String[] lines = next.split("\\n");
        if (lines.length > 12) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 12; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            next = sb.toString();
        }
        prefs.edit().putString(KEY_DEBUG, next).apply();
    }

    public static String get(Context ctx) {
        if (ctx == null) return "";
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEBUG, "等待调试信息…");
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
