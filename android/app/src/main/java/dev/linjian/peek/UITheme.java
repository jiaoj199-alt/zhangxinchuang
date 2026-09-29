package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public class UITheme {
    public final String name;
    public final int bgTop, bgBottom, card, cardSoft, primary, primarySoft, accent, text, subtext, line, danger;
    public final boolean dark;

    private UITheme(String name, int bgTop, int bgBottom, int card, int cardSoft, int primary, int primarySoft, int accent, int text, int subtext, int line, int danger, boolean dark) {
        this.name = name; this.bgTop = bgTop; this.bgBottom = bgBottom; this.card = card; this.cardSoft = cardSoft;
        this.primary = primary; this.primarySoft = primarySoft; this.accent = accent; this.text = text; this.subtext = subtext;
        this.line = line; this.danger = danger; this.dark = dark;
    }

    public static UITheme current(Context ctx) {
        String n = AppPrefs.get(ctx).getString(AppPrefs.KEY_THEME, "白桃粉");
        return byName(n);
    }

    public static UITheme byName(String n) {
        if ("雾蓝白".equals(n)) return new UITheme("雾蓝白",
                Color.rgb(239, 247, 252), Color.rgb(250, 252, 255), Color.WHITE, Color.rgb(244, 249, 252),
                Color.rgb(112, 178, 198), Color.rgb(228, 244, 249), Color.rgb(190, 132, 160),
                Color.rgb(42, 52, 60), Color.rgb(111, 126, 135), Color.rgb(226, 235, 240), Color.rgb(226, 105, 122), false);
        if ("白桃粉".equals(n)) return new UITheme("白桃粉",
                Color.rgb(255, 244, 248), Color.rgb(255, 251, 252), Color.rgb(255, 253, 254), Color.rgb(255, 245, 249),
                Color.rgb(211, 112, 145), Color.rgb(255, 226, 236), Color.rgb(151, 188, 180),
                Color.rgb(67, 48, 57), Color.rgb(145, 110, 123), Color.rgb(246, 215, 226), Color.rgb(222, 91, 112), false);
        if ("夜航黑".equals(n)) return new UITheme("夜航黑",
                Color.rgb(19, 24, 31), Color.rgb(28, 34, 42), Color.rgb(38, 45, 55), Color.rgb(46, 54, 65),
                Color.rgb(116, 188, 177), Color.rgb(50, 68, 75), Color.rgb(219, 145, 170),
                Color.rgb(239, 245, 242), Color.rgb(178, 194, 190), Color.rgb(63, 75, 86), Color.rgb(234, 112, 128), true);
        if ("星云紫".equals(n)) return new UITheme("星云紫",
                Color.rgb(245, 240, 255), Color.rgb(251, 247, 255), Color.rgb(255, 255, 255), Color.rgb(237, 232, 245),
                Color.rgb(184, 168, 216), Color.rgb(237, 232, 245), Color.rgb(224, 212, 240),
                Color.rgb(57, 50, 70), Color.rgb(126, 112, 148), Color.rgb(226, 212, 240), Color.rgb(225, 105, 122), false);
        if ("薄荷透明".equals(n)) return new UITheme("薄荷透明",
                Color.rgb(238, 252, 248), Color.rgb(252, 255, 253), Color.argb(235, 255, 255, 255), Color.argb(210, 242, 251, 248),
                Color.rgb(100, 190, 172), Color.rgb(222, 248, 242), Color.rgb(244, 171, 184),
                Color.rgb(42, 62, 57), Color.rgb(104, 132, 125), Color.rgb(218, 241, 235), Color.rgb(225, 101, 118), false);
        return new UITheme("奶油绿",
                Color.rgb(248, 252, 249), Color.rgb(243, 250, 247), Color.WHITE, Color.rgb(247, 252, 249),
                Color.rgb(103, 181, 165), Color.rgb(229, 247, 242), Color.rgb(214, 158, 174),
                Color.rgb(43, 59, 54), Color.rgb(109, 134, 128), Color.rgb(226, 238, 234), Color.rgb(226, 105, 122), false);
    }

    public GradientDrawable background() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bgTop, bgBottom});
        return g;
    }

    public GradientDrawable card(float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke((int) dp(strokeDp), line);
        return g;
    }

    public GradientDrawable soft(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cardSoft);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke((int) dp(0.6f), line);
        return g;
    }

    public GradientDrawable pill(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? primary : (dark ? cardSoft : Color.argb(210, 255, 255, 255)));
        g.setCornerRadius(dp(15));
        g.setStroke((int) dp(0.6f), selected ? primary : line);
        return g;
    }

    public GradientDrawable chip(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? primarySoft : card);
        g.setCornerRadius(dp(15));
        g.setStroke((int) dp(0.6f), selected ? primary : line);
        return g;
    }

    public GradientDrawable hero() {
        int start = dark ? blend(card, primary, 0.30f) : blend(Color.WHITE, primarySoft, 0.82f);
        int middle = dark ? blend(card, accent, 0.16f) : blend(Color.WHITE, primarySoft, 0.54f);
        int end = dark ? card : blend(Color.WHITE, card, 0.72f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, middle, end});
        g.setCornerRadius(dp(26));
        g.setStroke((int) dp(0.7f), dark ? line : Color.argb(110, 255, 255, 255));
        return g;
    }

    public GradientDrawable navBar() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? Color.argb(218, 38, 45, 55) : Color.argb(220, 255, 250, 252));
        g.setCornerRadius(dp(34));
        g.setStroke((int) dp(0.45f), Color.argb(dark ? 90 : 112, Color.red(line), Color.green(line), Color.blue(line)));
        return g;
    }

    public GradientDrawable navIconIsland() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dark ? blend(cardSoft, primary, 0.22f) : blend(Color.WHITE, primarySoft, 0.74f));
        g.setStroke((int) dp(0.45f), dark ? line : Color.argb(105, Color.red(primary), Color.green(primary), Color.blue(primary)));
        return g;
    }

    public GradientDrawable navItem(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        int selectedColor = dark ? blend(cardSoft, primary, 0.22f) : blend(Color.WHITE, primarySoft, 0.72f);
        g.setColor(selected ? selectedColor : Color.TRANSPARENT);
        g.setCornerRadius(dp(14));
        return g;
    }

    public GradientDrawable windowPanel(boolean left) {
        int edge = dark ? blend(bgTop, primary, 0.24f) : blend(bgTop, primarySoft, 0.58f);
        int center = dark ? blend(card, primary, 0.18f) : blend(Color.WHITE, primarySoft, 0.76f);
        GradientDrawable.Orientation direction = left ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT;
        GradientDrawable g = new GradientDrawable(direction, new int[]{edge, center});
        g.setStroke((int) dp(0.5f), line);
        return g;
    }

    private static int blend(int from, int to, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        int a = Math.round(Color.alpha(from) * (1f - x) + Color.alpha(to) * x);
        int r = Math.round(Color.red(from) * (1f - x) + Color.red(to) * x);
        int g = Math.round(Color.green(from) * (1f - x) + Color.green(to) * x);
        int b = Math.round(Color.blue(from) * (1f - x) + Color.blue(to) * x);
        return Color.argb(a, r, g, b);
    }

    public static float dp(float v) { return v * android.content.res.Resources.getSystem().getDisplayMetrics().density; }
}
