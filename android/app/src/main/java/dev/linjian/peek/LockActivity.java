package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class LockActivity extends Activity {
    private String pkg;
    private TextView titleView, remainView, reasonView, messageView;
    private EditText requestReasonInput;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); handler.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pkg = getIntent() == null ? "" : getIntent().getStringExtra("package");
        if (pkg == null) pkg = "";
        buildUi();
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getStringExtra("package") != null) pkg = intent.getStringExtra("package");
        AppGate.markLockActivityVisible(pkg, true);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        AppGate.markLockActivityVisible(pkg, true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        AppGate.markLockActivityVisible(pkg, false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        AppGate.markLockActivityVisible(pkg, false);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        ScreenshotService svc = ScreenshotService.getInstance();
        if (svc != null) svc.doHome();
        Toast.makeText(this, "还在门禁时间内，先回到桌面休息一下", Toast.LENGTH_SHORT).show();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFFFF5F8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(28), dp(26), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView tag = text("应用门禁 App Gate", 10, 0xFFD36F91, true);
        tag.setGravity(Gravity.CENTER_HORIZONTAL);
        tag.setLetterSpacing(.12f);
        root.addView(tag, lp(-1, -2, 0, 0, 0, 7));

        ImageView decor = new ImageView(this);
        decor.setImageResource(R.drawable.decor_gate_cat_box);
        decor.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        decor.setContentDescription(null);
        root.addView(decor, lp(dp(96), dp(74), 0, 0, 0, 6));

        titleView = text("", 22, 0xFF3D2E34, true);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(titleView, lp(-1, -2, 0, 0, 0, 10));

        remainView = text("", 12, 0xFFD36F91, true);
        remainView.setGravity(Gravity.CENTER_HORIZONTAL);
        remainView.setBackground(rounded(0xFFFFE7EF, 18, 0xFFF0C8D6, 1));
        remainView.setPadding(dp(15), dp(7), dp(15), dp(7));
        root.addView(remainView, lp(-2, -2, 0, 0, 0, 16));

        reasonView = card("锁定理由", "");
        root.addView(reasonView, lp(-1, -2, 0, 0, 0, 9));

        messageView = card(AppPrefs.companionName(this) + "说", "");
        root.addView(messageView, lp(-1, -2, 0, 0, 0, 13));

        requestReasonInput = new EditText(this);
        requestReasonInput.setHint("写下申请解锁的理由");
        requestReasonInput.setHintTextColor(0xFFB89AA5);
        requestReasonInput.setTextColor(0xFF3D2E34);
        requestReasonInput.setTextSize(12);
        requestReasonInput.setSingleLine(false);
        requestReasonInput.setMinLines(2);
        requestReasonInput.setPadding(dp(15), dp(11), dp(15), dp(11));
        requestReasonInput.setBackground(rounded(Color.WHITE, 20, 0xFFF0CBD8, 1));
        root.addView(requestReasonInput, lp(-1, dp(70), 0, 0, 0, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button request = button("申请解锁", true);
        request.setOnClickListener(v -> {
            String reason = requestReasonInput.getText().toString().trim();
            if (reason.length() == 0) { Toast.makeText(this, "先写一句解锁理由", Toast.LENGTH_SHORT).show(); return; }
            AppGate.submitUnlockRequest(this, pkg, reason);
            Toast.makeText(this, "已把解锁申请交给" + AppPrefs.companionName(this), Toast.LENGTH_LONG).show();
        });
        actions.addView(request, lp(dp(116), dp(40), 0, 0, 5, 0));

        Button home = button("返回桌面", false);
        home.setOnClickListener(v -> { ScreenshotService svc = ScreenshotService.getInstance(); if (svc != null) svc.doHome(); finish(); });
        actions.addView(home, lp(dp(104), dp(40), 5, 0, 0, 0));
        root.addView(actions, lp(-1, dp(40), 0, 0, 0, 11));

        Button emergency = textButton("长按 5 秒紧急解锁");
        final Runnable emergencyRunnable = () -> showEmergencyDialog();
        emergency.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                handler.postDelayed(emergencyRunnable, 5000);
                Toast.makeText(this, "继续按住 5 秒，才会打开紧急解锁", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(emergencyRunnable);
                return true;
            }
            return true;
        });
        root.addView(emergency, lp(dp(168), dp(32), 0, 0, 0, 7));

        TextView foot = text("时间结束后会自动打开", 9, 0xFFB0929D, false);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(foot, lp(-1, -2, 0, 0, 0, 0));
        setContentView(scroll);
    }

    private void refresh() {
        JSONObject lock = AppGate.currentLock(this, pkg);
        if (lock == null) { finish(); return; }
        long now = System.currentTimeMillis(); long until = lock.optLong("locked_until_ms", 0); long remain = Math.max(0, until - now);
        titleView.setText(lock.optString("app_name", AppGate.labelOf(this, pkg)) + " 已被" + AppPrefs.companionName(this) + "锁定");
        remainView.setText("剩余时间：" + remainText(remain));
        String reason = lock.optString("reason", "").trim();
        String message = lock.optString("message", "").trim();
        reasonView.setText("锁定理由" + (reason.isEmpty() ? "" : "\n" + reason));
        messageView.setText(AppPrefs.companionName(this) + "说" + (message.isEmpty() ? "" : "\n" + message));
        reasonView.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
        messageView.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showEmergencyDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入" + AppPrefs.companionName(this) + "告诉你的紧急口令");
        new AlertDialog.Builder(this)
                .setTitle("紧急解锁")
                .setMessage("确认是紧急情况再用。通过后会临时放行几分钟，并写入日志。")
                .setView(input)
                .setPositiveButton("解锁", (d, which) -> {
                    boolean ok = AppGate.tryEmergencyUnlock(this, pkg, input.getText().toString());
                    Toast.makeText(this, ok ? "紧急解锁成功，临时放行" : "口令不对", Toast.LENGTH_LONG).show();
                    if (ok) finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3), 1f); t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); return t;
    }
    private TextView card(String title, String body) { TextView t = text(title + (body.isEmpty() ? "" : "\n" + body), 12, 0xFF59414A, false); t.setPadding(dp(16), dp(13), dp(16), dp(13)); t.setBackground(rounded(Color.WHITE, 22, 0xFFF2D5DF, 1)); return t; }
    private Button button(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); b.setTextColor(primary ? Color.WHITE : 0xFFD36F91); b.setMinHeight(0); b.setPadding(dp(10), 0, dp(10), 0); b.setBackground(rounded(primary ? 0xFFD96891 : Color.WHITE, 20, primary ? 0xFFD96891 : 0xFFE9B8C9, 1)); return b; }
    private Button textButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(9); b.setTextColor(0xFFAD7F90); b.setMinHeight(0); b.setPadding(dp(8), 0, dp(8), 0); b.setBackground(rounded(0x00FFFFFF, 16, 0x00FFFFFF, 0)); return b; }
    private GradientDrawable rounded(int color, int radius, int stroke, int strokeWidth) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); if (strokeWidth > 0) g.setStroke(dp(strokeWidth), stroke); return g; }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(l,t,r,b); return p; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private String remainText(long ms) {
        long sec = ms / 1000; long h = sec / 3600; long m = (sec % 3600) / 60; long s = sec % 60;
        if (h > 0) return h + " 小时 " + m + " 分钟 " + s + " 秒";
        if (m > 0) return m + " 分钟 " + s + " 秒";
        return s + " 秒";
    }
}
