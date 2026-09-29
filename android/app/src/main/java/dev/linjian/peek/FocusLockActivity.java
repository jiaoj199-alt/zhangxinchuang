package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class FocusLockActivity extends Activity {
    private TextView titleView, remainView, goalView, messageView, chatView, emergencyView;
    private EditText contactInput;
    private LinearLayout contactPanel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); handler.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        } catch (Exception ignored) { }
        buildUi();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        FocusMode.setLockActivityVisible(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
    @Override protected void onStop() {
        FocusMode.setLockActivityVisible(false);
        super.onStop();
    }
    @Override protected void onDestroy() {
        FocusMode.setLockActivityVisible(false);
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (FocusMode.isActive(this)) Toast.makeText(this, "专注还没结束，先让他守住你。", Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFFFF5F8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(30), dp(24), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView tag = text("全机专注 Focus Mode", 10, 0xFFD36F91, true);
        tag.setGravity(Gravity.CENTER);
        tag.setLetterSpacing(.12f);
        root.addView(tag, lp(-1, -2, 0, 0, 0, 8));

        ImageView decor = new ImageView(this);
        decor.setImageResource(R.drawable.decor_gate_cat_box);
        decor.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(decor, lp(dp(98), dp(74), 0, 0, 0, 8));

        titleView = text("全机专注中", 23, 0xFF3D2E34, true);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, lp(-1, -2, 0, 0, 0, 10));

        remainView = text("剩余时间读取中", 12, 0xFFD36F91, true);
        remainView.setGravity(Gravity.CENTER);
        remainView.setPadding(dp(15), dp(7), dp(15), dp(7));
        remainView.setBackground(rounded(0xFFFFE7EF, 18, 0xFFF0CBD8, 1));
        root.addView(remainView, lp(-2, -2, 0, 0, 0, 10));

        TextView scopeView = text("全机专注：解锁后也会回到这里", 10, 0xFFB0929D, false);
        scopeView.setGravity(Gravity.CENTER);
        root.addView(scopeView, lp(-1, -2, 0, 0, 0, 14));

        goalView = card("目标", "早点休息");
        root.addView(goalView, lp(-1, -2, 0, 0, 0, 9));

        messageView = card("他说", "你提前把这段时间交给我了，我会帮你守住。");
        root.addView(messageView, lp(-1, -2, 0, 0, 0, 13));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button rest = button("我去休息", true);
        rest.setOnClickListener(v -> { ScreenshotService svc = ScreenshotService.getInstance(); if (svc != null && svc.doLockScreen()) Toast.makeText(this, "晚安，专注结束前我还会守着。", Toast.LENGTH_SHORT).show(); else Toast.makeText(this, "把手机放下就好，我还在守着。", Toast.LENGTH_SHORT).show(); });
        actions.addView(rest, lp(dp(104), dp(40), 0, 0, 5, 0));

        Button talk = button("留言给他", false);
        talk.setOnClickListener(v -> toggleContactPanel());
        actions.addView(talk, lp(dp(96), dp(40), 5, 0, 5, 0));

        Button emergency = button("应急", false);
        emergency.setOnClickListener(v -> showEmergencyDialog());
        actions.addView(emergency, lp(dp(78), dp(40), 5, 0, 0, 0));
        root.addView(actions, lp(-1, dp(42), 0, 0, 0, 12));

        contactPanel = new LinearLayout(this);
        contactPanel.setOrientation(LinearLayout.VERTICAL);
        contactPanel.setPadding(dp(15), dp(14), dp(15), dp(14));
        contactPanel.setBackground(rounded(Color.WHITE, 24, 0xFFF0CBD8, 1));
        contactPanel.setVisibility(View.GONE);

        TextView contactTitle = text("留言给他 · 只通向当前连接的机", 13, 0xFF3D2E34, true);
        contactPanel.addView(contactTitle, lp(-1, -2, 0, 0, 0, 8));
        chatView = text("还没有新的对话。", 11, 0xFF59414A, false);
        chatView.setLineSpacing(dp(4), 1f);
        contactPanel.addView(chatView, lp(-1, -2, 0, 0, 0, 10));
        contactInput = new EditText(this);
        contactInput.setHint("这条留言会被他看到，例如：我真的有急事");
        contactInput.setHintTextColor(0xFFB89AA5);
        contactInput.setTextColor(0xFF3D2E34);
        contactInput.setTextSize(12);
        contactInput.setSingleLine(false);
        contactInput.setMinLines(2);
        contactInput.setPadding(dp(13), dp(10), dp(13), dp(10));
        contactInput.setBackground(rounded(0xFFFFF9FB, 18, 0xFFF0CBD8, 1));
        contactPanel.addView(contactInput, lp(-1, dp(68), 0, 0, 0, 10));
        Button send = button("留下留言", true);
        send.setOnClickListener(v -> {
            String text = contactInput.getText().toString().trim();
            if (text.length() == 0) { Toast.makeText(this, "先写一句话", Toast.LENGTH_SHORT).show(); return; }
            FocusMode.submitContactMessage(this, text);
            contactInput.setText("");
            Toast.makeText(this, "已留下，他会看到的", Toast.LENGTH_SHORT).show();
            refresh();
        });
        contactPanel.addView(send, lp(dp(116), dp(38), 0, 0, 0, 0));
        root.addView(contactPanel, lp(-1, -2, 0, 0, 0, 12));

        emergencyView = text("应急出口很窄：默认 1 次，每次 1 分钟。", 10, 0xFFB0929D, false);
        emergencyView.setGravity(Gravity.CENTER);
        emergencyView.setLineSpacing(dp(3), 1f);
        root.addView(emergencyView, lp(-1, -2, 0, 0, 0, 0));

        setContentView(scroll);
    }

    private void refresh() {
        JSONObject s = FocusMode.config(this);
        if (!s.optBoolean("active", false) || s.optBoolean("temporary_active", false)) { finish(); return; }
        titleView.setText("全机专注中");
        remainView.setText("距离结束：" + FocusMode.remainText(s.optLong("remaining_ms", 0)));
        goalView.setText("目标（由他填写）\n" + s.optString("goal", "先离开手机"));
        messageView.setText("他说（由他填写）\n" + s.optString("message", "你提前把这段时间交给我了，我会帮你守住。"));
        emergencyView.setText("应急剩余：" + s.optInt("emergency_remaining", 0) + " 次｜每次 " + s.optInt("emergency_minutes", 1) + " 分钟");
        if (chatView != null) chatView.setText(conversationText(s));
    }

    private void toggleContactPanel() { contactPanel.setVisibility(contactPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); refresh(); }

    private void showEmergencyDialog() {
        JSONObject s = FocusMode.config(this);
        if (s.optInt("emergency_remaining", 0) <= 0) { Toast.makeText(this, "今天没有应急次数了，可以留言给他一句。", Toast.LENGTH_LONG).show(); contactPanel.setVisibility(View.VISIBLE); return; }
        final EditText input = new EditText(this);
        input.setHint("写下应急原因，会记录进专注报告");
        input.setSingleLine(false);
        input.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle("应急解锁 " + s.optInt("emergency_minutes", 1) + " 分钟")
                .setMessage("只在真正需要时使用。放行结束后会重新进入专注模式。")
                .setView(input)
                .setPositiveButton("放行", (d, which) -> {
                    boolean ok = FocusMode.offlineEmergencyUnlock(this, input.getText().toString());
                    Toast.makeText(this, ok ? "已应急放行，时间到会重新锁住" : "今天应急次数用完了", Toast.LENGTH_LONG).show();
                    if (ok) { ScreenshotService svc = ScreenshotService.getInstance(); if (svc != null) svc.doHome(); finish(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String conversationText(JSONObject s) {
        JSONArray arr = s.optJSONArray("messages");
        if (arr == null || arr.length() == 0) return "还没有留言。";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, arr.length() - 6);
        for (int i = start; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String role = "user".equals(m.optString("role")) ? "你" : "他";
            sb.append(role).append("：").append(m.optString("text", "")).append("\n");
        }
        return sb.toString().trim();
    }

    private TextView text(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3), 1f); t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); return t; }
    private TextView card(String title, String body) { TextView t = text(title + (body.isEmpty() ? "" : "\n" + body), 12, 0xFF59414A, false); t.setPadding(dp(16), dp(13), dp(16), dp(13)); t.setBackground(rounded(Color.WHITE, 22, 0xFFF2D5DF, 1)); return t; }
    private Button button(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); b.setTextColor(primary ? Color.WHITE : 0xFFD36F91); b.setMinHeight(0); b.setPadding(dp(8), 0, dp(8), 0); b.setBackground(rounded(primary ? 0xFFD96891 : Color.WHITE, 20, primary ? 0xFFD96891 : 0xFFE9B8C9, 1)); return b; }
    private GradientDrawable rounded(int color, int radius, int stroke, int strokeWidth) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); if (strokeWidth > 0) g.setStroke(dp(strokeWidth), stroke); return g; }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h); p.setMargins(l,t,r,b); return p; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
