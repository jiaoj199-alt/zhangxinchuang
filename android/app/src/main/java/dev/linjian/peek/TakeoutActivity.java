package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 外卖小助手页面：记住多道常点外卖、预算、自动点到付款页与小金库联动。 */
public class TakeoutActivity extends Activity {
    private LinearLayout root, content;
    private TextView title, actionText;
    private String page = "home";
    private UITheme theme;
    private int ink, sub, primary, primarySoft, cardColor, cardSoft, cardStroke;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadTheme();
        buildRoot();
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        UITheme latest = UITheme.current(this);
        if (theme == null || !theme.name.equals(latest.name)) {
            loadTheme(); buildRoot(); showHome();
        }
    }

    private void loadTheme() {
        theme = UITheme.current(this);
        ink = theme.text; sub = theme.subtext; primary = theme.primary; primarySoft = theme.primarySoft;
        cardColor = theme.card; cardSoft = theme.cardSoft; cardStroke = theme.line;
    }

    private void buildRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(theme.background());
        root.setPadding(dp(16), dp(18), dp(16), dp(10));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));

        Button back = tinyButton("‹");
        back.setTextSize(22);
        back.setOnClickListener(v -> { if ("home".equals(page)) finish(); else showHome(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));

        title = new TextView(this);
        title.setTextColor(ink); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1); tp.leftMargin = dp(8); header.addView(title, tp);

        actionText = new TextView(this);
        actionText.setTextColor(ink); actionText.setTextSize(17); actionText.setTypeface(Typeface.DEFAULT_BOLD); actionText.setGravity(Gravity.CENTER);
        actionText.setBackground(round(cardColor, dp(20), cardStroke, 1));
        header.addView(actionText, new LinearLayout.LayoutParams(dp(42), dp(42)));

        ScrollView sv = new ScrollView(this);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(18));
        sv.addView(content, new ScrollView.LayoutParams(-1, -2));
    }

    private void reset(String t, String action, View.OnClickListener listener) {
        page = "home".equals(page) ? page : page;
        title.setText(t);
        actionText.setText(action == null ? "" : action);
        actionText.setVisibility(action == null || action.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        actionText.setOnClickListener(listener);
        content.removeAllViews();
    }

    private void showHome() {
        page = "home";
        reset("今天吃什么", "+", v -> showRememberMeal());
        try {
            JSONObject s = TakeoutState.collect(this);
            LinearLayout hero = card(); hero.setBackground(theme.hero());
            hero.addView(small("外卖小助手 · 记住这道饭与自动点单"));
            hero.addView(big("¥ " + WalletState.money(s.optDouble("meal_budget", 25))));
            hero.addView(small("单餐预算 · 今日外卖预算 ¥" + WalletState.money(s.optDouble("day_budget", 45)) + " · 常点 " + s.optInt("card_count", 0) + " 个"));
            hero.addView(progress(s.optDouble("meal_budget", 25), Math.max(1, s.optDouble("day_budget", 45))));
            content.addView(hero);

            LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL); quick.setGravity(Gravity.CENTER); quick.setPadding(0, dp(4), 0, dp(4));
            content.addView(quick, new LinearLayout.LayoutParams(-1, dp(58)));
            quick.addView(actionButton("记住这道饭", v -> showRememberMeal()), weightLp(1,0,4));
            quick.addView(actionButton("帮我挑", v -> showSuggest()), weightLp(1,4,4));
            quick.addView(actionButton("预算", v -> showBudget()), weightLp(1,4,0));

            JSONObject checkout = s.optJSONObject("checkout_session");
            if (checkout != null && checkout.optString("status", "").length() > 0) {
                String st = checkout.optString("status", "");
                String label = "running".equals(st) ? "正在自动点单" : ("ready_for_payment".equals(st) ? "已到付款页" : ("failed".equals(st) ? "自动点单已停止" : "自动点单：" + st));
                content.addView(emptyCard(label + "\n" + checkout.optString("detail", ""), "→"));
            }

            addSectionTitle("今日建议", "换一批", v -> showSuggest());
            JSONArray suggestions = s.optJSONArray("suggestions");
            if (suggestions == null || suggestions.length() == 0) content.addView(emptyCard("先保存一张常点套餐，我就能帮你挑，也能直接点到付款页。", "饭"));
            else for (int i = 0; i < Math.min(3, suggestions.length()); i++) content.addView(takeoutCard(suggestions.optJSONObject(i), true));

            addSectionTitle(AppPrefs.companionName(this) + "记住的饭", "再记一份", v -> showRememberMeal());
            JSONArray cards = s.optJSONArray("cards");
            if (cards == null || cards.length() == 0) content.addView(emptyCard("第一次打开具体菜品后，点分享→复制链接，再回来点“记住这道饭”。可以连续记很多份。", "+"));
            else for (int i = 0; i < cards.length(); i++) content.addView(takeoutCard(cards.optJSONObject(i), false));
        } catch (Exception e) { content.addView(emptyCard("外卖小助手加载失败：" + ScreenshotService.shortMsg(e), "!")); }
    }

    private void showBudget() {
        page = "budget";
        reset("外卖预算", "✓", null);
        JSONObject s = TakeoutState.collect(this);
        LinearLayout form = card();
        EditText meal = input("单餐预算，例如 25", true); meal.setText(String.valueOf(s.optDouble("meal_budget", 25))); form.addView(meal);
        EditText day = input("今日外卖预算，例如 45", true); day.setText(String.valueOf(s.optDouble("day_budget", 45))); form.addView(day);
        EditText taste = input("口味偏好，例如 少油、不要香菜、想吃热饭", false); taste.setText(s.optString("taste_note", "")); form.addView(taste);
        content.addView(form);
        content.addView(emptyCard("这里的预算只管外卖助手推荐；真正花钱仍会联动小金库审批和记账。", "¥"));
        actionText.setOnClickListener(v -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("meal_budget", Double.parseDouble(meal.getText().toString().trim()));
                cmd.put("day_budget", Double.parseDouble(day.getText().toString().trim()));
                cmd.put("taste_note", taste.getText().toString());
                TakeoutState.setPrefs(this, cmd); toast("外卖预算已保存"); showHome();
            } catch (Exception e) { toast("预算要填数字哦"); }
        });
    }

    private void showRememberMeal() {
        page = "remember";
        reset("记住这道饭", "✓", null);
        LinearLayout form = card();
        EditText titleInput = input("给这道饭起个名字，例如 土豆片炒肉木桶饭", false); form.addView(titleInput);
        EditText link = input("分享链接（京东复制店铺分享链接也可以）", false);
        String clip = clipboardText();
        String suggestedTitle = TakeoutState.suggestTitleFromShareText(clip);
        boolean jdStoreShare = clip != null && clip.contains("京东外卖 |") && TakeoutState.isJdShortLink(TakeoutState.normalizeLink(clip));
        if (suggestedTitle.length() > 0 && !jdStoreShare) titleInput.setText(suggestedTitle);
        String clipUrl = TakeoutState.normalizeLink(clip);
        if (clipUrl.startsWith("http://") || clipUrl.startsWith("https://") || clipUrl.startsWith("meituan://") || clipUrl.startsWith("eleme://")) link.setText(clipUrl);
        form.addView(link);
        EditText aliases = input("还可以怎么叫它，用 | 分隔，例如 木桶饭|土豆片", false); form.addView(aliases);
        EditText choices = input("固定选项，用 | 分隔，例如 不需要隔层|不要餐具", false); form.addView(choices);
        EditText note = input("固定备注，例如 少辣、不用打电话", false); form.addView(note);
        EditText checkoutMax = input("最多多少钱（可空）", true); form.addView(checkoutMax);
        content.addView(form);
        content.addView(emptyCard("京东外卖不用再找“具体菜品深链”：复制这家店的 3.cn 分享链接，再手动填菜名即可。点单时掌心窗会让 Cloudflare 先解析真实京东落地页，直接交给京东 App，再在店内找你填的那道菜；不会再经过 Chrome 登录中转。可以记很多道饭。", "记"));
        actionText.setOnClickListener(v -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("title", titleInput.getText().toString());
                cmd.put("direct_link", link.getText().toString());
                cmd.put("aliases", aliases.getText().toString());
                cmd.put("choices", choices.getText().toString());
                cmd.put("note", note.getText().toString());
                cmd.put("checkout_max", parseDouble(checkoutMax.getText().toString(), 0));
                cmd.put("strict_budget", checkoutMax.getText().toString().trim().length() > 0);
                JSONObject rr = TakeoutState.rememberMeal(this, cmd);
                if (!rr.optBoolean("ok", false)) { String err = rr.optString("error", ""); toast("jd_meal_name_required".equals(err) ? "京东这张卡还要填菜名哦" : "还没拿到外卖分享链接"); return; }
                toast("这道饭记住啦"); showHome();
            } catch (Exception e) { toast("记住失败"); }
        });
    }

    private void showAddCard() {
        page = "add";
        reset("保存常点", "✓", null);
        LinearLayout form = card();
        EditText titleInput = input("店名或卡片名，例如 楼下麻辣烫", false); form.addView(titleInput);
        EditText platform = input("平台：京东 / 美团 / 饿了么 / 其他", false); form.addView(platform);
        EditText link = input("分享链接或整段分享文本（会自动提取 URL）", false); form.addView(link);
        EditText items = input("常点菜品，例如 土豆片炒肉木桶饭 1人份", false); form.addView(items);
        EditText itemQuery = input("自动找菜关键词，例如 土豆片炒肉木桶饭", false); form.addView(itemQuery);
        EditText choices = input("自动选择项，用 | 分隔，例如 不需要隔层|不要餐具", false); form.addView(choices);
        EditText min = input("最低价格，例如 18", true); form.addView(min);
        EditText max = input("最高价格，例如 25", true); form.addView(max);
        EditText checkoutMax = input("自动提交前金额上限（可空；默认不强制）", true); form.addView(checkoutMax);
        EditText note = input("下单备注，例如 少辣、不用打电话", false); form.addView(note);
        EditText tags = input("标签，例如 热饭 米饭 微辣", false); form.addView(tags);
        content.addView(form);
        actionText.setOnClickListener(v -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("title", titleInput.getText().toString()); cmd.put("platform", platform.getText().toString()); cmd.put("link", link.getText().toString());
                cmd.put("items", items.getText().toString()); cmd.put("item_query", itemQuery.getText().toString()); cmd.put("choices", choices.getText().toString());
                cmd.put("price_min", parseDouble(min.getText().toString(), 0)); cmd.put("price_max", parseDouble(max.getText().toString(), 0));
                cmd.put("checkout_max", parseDouble(checkoutMax.getText().toString(), 0)); cmd.put("strict_budget", checkoutMax.getText().toString().trim().length() > 0);
                cmd.put("note", note.getText().toString()); cmd.put("tags", tags.getText().toString());
                TakeoutState.saveCard(this, cmd); toast("已保存常点"); showHome();
            } catch (Exception e) { toast("保存失败"); }
        });
    }

    private void showSuggest() {
        page = "suggest";
        reset("帮我挑", "", null);
        try {
            JSONObject s = TakeoutState.suggest(this, new JSONObject().put("limit", 6));
            content.addView(emptyCard("我会优先从常点套餐里挑；选好后可以让手机本地自动点到付款页，真正支付仍留给你。", "饭"));
            JSONArray arr = s.optJSONArray("suggestions");
            if (arr == null || arr.length() == 0) content.addView(emptyCard("还没记住任何饭，先把一份具体菜品保存下来。", "+"));
            else for (int i = 0; i < arr.length(); i++) content.addView(takeoutCard(arr.optJSONObject(i), true));
        } catch (Exception e) { content.addView(emptyCard("推荐失败：" + ScreenshotService.shortMsg(e), "!")); }
    }

    private View takeoutCard(JSONObject c, boolean suggest) {
        if (c == null) return emptyCard("外卖卡片读取失败", "!");
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); box.addView(row);
        TextView icon = text("饭", 16, true); icon.setGravity(Gravity.CENTER); icon.setBackground(round(primarySoft, dp(18), 0, 0)); row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1); mp.leftMargin = dp(10); row.addView(mid, mp);
        mid.addView(text(c.optString("title", "常点外卖") + "｜" + c.optString("platform", "外卖"), 14, true));
        TextView note = small(nonEmpty(c.optString("items", ""), c.optString("note", "点进去确认订单"))); note.setMaxLines(1); mid.addView(note);
        double estimated = c.has("estimated_amount") ? c.optDouble("estimated_amount", 0) : estimate(c);
        TextView price = text(estimated > 0 ? "约 ¥" + WalletState.money(estimated) : "打开", 13, true); row.addView(price);
        String reason = c.optString("reason", "");
        if (suggest && reason.length() > 0) { TextView why = small(reason); why.setPadding(dp(52), dp(8), 0, 0); box.addView(why); }
        box.setOnClickListener(v -> showTakeoutDetail(c));
        if (suggest) return box;
        return swipeableMealCard(box, c);
    }

    /**
     * 陪伴者记住的饭：左滑卡片露出“编辑 / 删除”。
     * 用 HorizontalScrollView 做原生方向判断，纵向滚页面时不会被一堆手势代码抢焦点。
     */
    private View swipeableMealCard(LinearLayout front, JSONObject c) {
        final int actionWidth = dp(144);
        final int cardWidth = Math.max(dp(260), getResources().getDisplayMetrics().widthPixels - dp(32));

        // card() 自带的底部 margin 移到外层，避免滑开后动作区和下一张卡挤在一起。
        front.setLayoutParams(new LinearLayout.LayoutParams(cardWidth, -2));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setFillViewport(false);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(track, new HorizontalScrollView.LayoutParams(cardWidth + actionWidth, -2));
        track.addView(front, new LinearLayout.LayoutParams(cardWidth, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(6), 0, 0, 0);
        track.addView(actions, new LinearLayout.LayoutParams(actionWidth, -1));

        TextView edit = swipeAction("编辑", false);
        TextView delete = swipeAction("删除", true);
        actions.addView(edit, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, -1, 1); deleteLp.leftMargin = dp(6);
        actions.addView(delete, deleteLp);

        edit.setOnClickListener(v -> {
            hsv.smoothScrollTo(0, 0);
            showEditMeal(c);
        });
        delete.setOnClickListener(v -> {
            hsv.smoothScrollTo(0, 0);
            confirmDeleteMeal(c);
        });

        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.bottomMargin = dp(10);
        hsv.setLayoutParams(outer);
        return hsv;
    }

    private TextView swipeAction(String label, boolean danger) {
        TextView v = text(label, 13, true);
        v.setGravity(Gravity.CENTER);
        int bg = danger ? Color.rgb(235, 88, 88) : primarySoft;
        int fg = danger ? Color.WHITE : ink;
        v.setTextColor(fg);
        v.setBackground(round(bg, dp(18), danger ? bg : cardStroke, 1));
        return v;
    }

    private void showEditMeal(JSONObject c) {
        if (c == null) return;
        page = "edit";
        reset("编辑这道饭", "✓", null);

        LinearLayout form = card();
        EditText titleInput = input("饭名", false);
        titleInput.setText(c.optString("title", ""));
        form.addView(titleInput);

        EditText platform = input("平台：京东 / 美团 / 饿了么 / 其他", false);
        platform.setText(c.optString("platform", ""));
        form.addView(platform);

        EditText link = input("分享链接 / 菜品链接", false);
        link.setText(nonEmpty(c.optString("direct_link", ""), c.optString("link", "")));
        form.addView(link);

        EditText items = input("显示菜品 / 份量，例如 土豆片炒肉木桶饭 1人份", false);
        items.setText(c.optString("items", ""));
        form.addView(items);

        EditText aliases = input("别名，用 | 分隔，例如 木桶饭|土豆片", false);
        aliases.setText(joinArray(c.optJSONArray("aliases")));
        form.addView(aliases);

        EditText itemQuery = input("自动找菜关键词", false);
        itemQuery.setText(nonEmpty(c.optString("item_query", ""), c.optString("items", "")));
        form.addView(itemQuery);

        EditText choices = input("固定选项，用 | 分隔，例如 不需要隔层|不要餐具", false);
        choices.setText(joinArray(c.optJSONArray("choices")));
        form.addView(choices);

        EditText min = input("最低价格（可空）", true);
        if (c.optDouble("price_min", 0) > 0) min.setText(WalletState.money(c.optDouble("price_min", 0)));
        form.addView(min);

        EditText max = input("最高价格（可空）", true);
        if (c.optDouble("price_max", 0) > 0) max.setText(WalletState.money(c.optDouble("price_max", 0)));
        form.addView(max);

        EditText checkoutMax = input("点单金额上限（可空）", true);
        if (c.optDouble("checkout_max", 0) > 0) checkoutMax.setText(WalletState.money(c.optDouble("checkout_max", 0)));
        form.addView(checkoutMax);

        EditText note = input("固定备注，例如 少辣、不用打电话", false);
        note.setText(c.optString("note", ""));
        form.addView(note);

        EditText tags = input("标签，例如 热饭 米饭 微辣", false);
        tags.setText(c.optString("tags", ""));
        form.addView(tags);

        content.addView(form);
        content.addView(emptyCard("保存后继续使用原来的饭卡，不会重复新增。左滑仍可再次编辑或删除。", "编"));

        actionText.setOnClickListener(v -> {
            try {
                String normalized = TakeoutState.normalizeLink(link.getText().toString());
                String previousEntry = TakeoutState.normalizeLink(nonEmpty(c.optString("direct_link", ""), c.optString("link", "")));
                boolean linkChanged = !normalized.equals(previousEntry);
                JSONObject cmd = new JSONObject();
                cmd.put("id", c.optString("id"));
                cmd.put("title", titleInput.getText().toString());
                cmd.put("platform", platform.getText().toString());
                cmd.put("items", items.getText().toString());
                cmd.put("item_query", itemQuery.getText().toString());
                cmd.put("aliases", aliases.getText().toString());
                cmd.put("choices", choices.getText().toString());
                cmd.put("price_min", parseDouble(min.getText().toString(), 0));
                cmd.put("price_max", parseDouble(max.getText().toString(), 0));
                cmd.put("checkout_max", parseDouble(checkoutMax.getText().toString(), 0));
                cmd.put("strict_budget", checkoutMax.getText().toString().trim().length() > 0);
                cmd.put("note", note.getText().toString());
                cmd.put("tags", tags.getText().toString());

                // 3.cn 继续作为京东店铺入口；其它链接保留原饭卡的 direct/store 模式。
                if (TakeoutState.isJdShortLink(normalized)) {
                    cmd.put("link", normalized);
                    cmd.put("direct_link", "");
                } else if (c.optString("direct_link", "").trim().length() > 0) {
                    cmd.put("direct_link", normalized);
                    cmd.put("link", normalized);
                } else {
                    cmd.put("link", normalized);
                    cmd.put("direct_link", "");
                }
                if (linkChanged) {
                    cmd.put("resolved_link", "");
                    cmd.put("jd_openapp", "");
                    cmd.put("resolution_status", "");
                }

                JSONObject rr = TakeoutState.saveCard(this, cmd);
                if (!rr.optBoolean("ok", false)) { toast("保存失败"); return; }
                toast("这道饭已更新");
                showHome();
            } catch (Exception e) { toast("编辑保存失败"); }
        });
    }

    private void confirmDeleteMeal(JSONObject c) {
        if (c == null) return;
        new AlertDialog.Builder(this)
                .setTitle("删除这道饭？")
                .setMessage("会从“" + AppPrefs.companionName(this) + "记住的饭”里移除「" + c.optString("title", "这道饭") + "」，不会影响已经产生的订单或小金库记录。")
                .setNegativeButton("取消", (d, w) -> { })
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        JSONObject rr = TakeoutState.removeCard(this, new JSONObject().put("id", c.optString("id")));
                        if (rr.optBoolean("removed", false)) {
                            toast("已经删掉啦");
                            showHome();
                        } else toast("没有找到这张饭卡");
                    } catch (Exception e) { toast("删除失败"); }
                })
                .show();
    }

    private void showTakeoutDetail(JSONObject c) {
        if (c == null) return;
        StringBuilder sb = new StringBuilder();
        double estimated = estimate(c);
        sb.append("平台：").append(c.optString("platform", "外卖平台")).append("\n");
        sb.append("常点：").append(nonEmpty(c.optString("items", ""), "未填写")).append("\n");
        sb.append("预计：¥").append(WalletState.money(estimated)).append("\n");
        boolean direct = c.optString("direct_link", "").trim().length() > 0 && !TakeoutState.isJdShortLink(c.optString("direct_link", ""));
        sb.append("打开方式：").append(direct ? "具体菜品直达" : (TakeoutState.isJdShortLink(nonEmpty(c.optString("direct_link", ""), c.optString("link", ""))) ? "解析京东店铺入口 → 店内找菜" : "店铺内查找")).append("\n");
        if (!direct) sb.append("自动找菜：").append(nonEmpty(c.optString("item_query", ""), c.optString("items", "未填写"))).append("\n");
        JSONArray autoChoices = c.optJSONArray("choices");
        sb.append("自动选择项：").append(autoChoices == null || autoChoices.length() == 0 ? "按安全默认项处理；遇到未知必选会停下" : autoChoices.toString()).append("\n");
        sb.append("备注：").append(nonEmpty(c.optString("note", ""), "无")).append("\n\n");
        sb.append(direct ? "这张饭卡会优先打开具体菜品，跳过整家店找菜；再处理规格、备注和结算。真正支付按钮永远留给你。" : "“点到付款页”会把商品、规格、备注和提交订单交给手机本地连续完成；遇到找不到商品、未知必选项或金额超限会自动停下，真正支付按钮永远留给你。");
        new AlertDialog.Builder(this)
                .setTitle(c.optString("title", "常点外卖"))
                .setMessage(sb.toString())
                .setNegativeButton("先不点", (d,w)-> { })
                .setNeutralButton("小金库申请", (d,w)-> { try { TakeoutState.takeoutWalletRequest(this, new JSONObject().put("card_id", c.optString("id")).put("amount", estimated)); toast("已提交外卖申请"); startActivity(new Intent(this, WalletActivity.class)); } catch(Exception e) { toast("申请失败"); } })
                .setPositiveButton("点到付款页", (d,w)-> {
                    try {
                        JSONObject rr = TakeoutState.prepareCheckout(this, new JSONObject().put("card_id", c.optString("id")));
                        if (!rr.optBoolean("ok", false)) toast("启动失败：" + rr.optString("error", "未知错误"));
                        else toast(AppPrefs.companionName(this) + "开始帮你点，最后停在付款页");
                    } catch(Exception e) { toast("自动点单启动失败"); }
                })
                .show();
    }

    private void addSectionTitle(String left, String right, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(14), 0, dp(7)); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView l = text(left, 14, true); row.addView(l, new LinearLayout.LayoutParams(0, -1, 1));
        if (right != null && right.length() > 0) { TextView r = text(right + " ›", 12, true); r.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); r.setTextColor(sub); r.setOnClickListener(listener); row.addView(r, new LinearLayout.LayoutParams(dp(96), -1)); }
    }

    private LinearLayout card() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16), dp(14), dp(16), dp(14)); l.setBackground(round(cardColor, dp(24), cardStroke, 1)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); l.setLayoutParams(lp); return l; }
    private LinearLayout emptyCard(String msg, String iconText) { LinearLayout l = card(); l.setGravity(Gravity.CENTER); l.setPadding(dp(16), dp(12), dp(16), dp(12)); TextView icon = text(iconText, 18, true); icon.setTextColor(withAlpha(primary, theme.dark ? 150 : 110)); icon.setGravity(Gravity.CENTER); icon.setBackground(round(primarySoft, dp(17), 0, 0)); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(34), dp(34)); ip.bottomMargin = dp(4); l.addView(icon, ip); TextView t = small(msg); t.setGravity(Gravity.CENTER); l.addView(t, new LinearLayout.LayoutParams(-1, -2)); return l; }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(ink); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView small(String s) { TextView v = text(s, 11, false); v.setTextColor(sub); v.setLineSpacing(dp(2), 1f); return v; }
    private TextView big(String s) { TextView v = text(s, 28, true); v.setPadding(0, dp(6), 0, dp(4)); return v; }
    private Button tinyButton(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(ink); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(round(cardColor, dp(18), cardStroke, 1)); return b; }
    private Button actionButton(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setTextSize(12); b.setTextColor(ink); b.setTypeface(Typeface.DEFAULT_BOLD); b.setAllCaps(false); b.setBackground(round(cardColor, dp(20), cardStroke, 1)); b.setOnClickListener(l); return b; }
    private EditText input(String hint, boolean number) { EditText e = new EditText(this); e.setHint(hint); e.setTextSize(12); e.setSingleLine(true); e.setTextColor(ink); e.setHintTextColor(withAlpha(sub, 165)); e.setPadding(dp(12), 0, dp(12), 0); e.setBackground(round(cardSoft, dp(16), cardStroke, 1)); if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46)); lp.bottomMargin = dp(8); e.setLayoutParams(lp); return e; }
    private View progress(double used, double total) { LinearLayout outer = new LinearLayout(this); outer.setBackground(round(primarySoft, dp(999), 0, 0)); LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(8)); op.topMargin = dp(12); outer.setLayoutParams(op); View bar = new View(this); bar.setBackground(round(primary, dp(999), 0, 0)); double ratio = Math.max(0, Math.min(1, used / Math.max(1, total))); outer.post(() -> outer.addView(bar, new LinearLayout.LayoutParams((int)(outer.getWidth()*ratio), dp(8)))); return outer; }
    private LinearLayout.LayoutParams weightLp(float w, int left, int right) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w); lp.leftMargin = dp(left); lp.rightMargin = dp(right); return lp; }
    private GradientDrawable round(int color, int radius, int strokeColor, int stroke) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); if (stroke > 0) g.setStroke(dp(stroke), strokeColor); return g; }
    private int withAlpha(int color, int alpha) { return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color)); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private double parseDouble(String s, double d) { try { return Double.parseDouble(s.trim()); } catch(Exception e) { return d; } }
    private String nonEmpty(String s, String fallback) { return s == null || s.trim().length() == 0 ? fallback : s.trim(); }
    private double estimate(JSONObject c) { double min = c.optDouble("price_min", 0); double max = c.optDouble("price_max", 0); if (min > 0 && max > 0) return WalletState.round2((min + max) / 2.0); return WalletState.round2(Math.max(min, max)); }
    private String clipboardText() { try { ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); if (cm == null || !cm.hasPrimaryClip()) return ""; ClipData c = cm.getPrimaryClip(); if (c == null || c.getItemCount() == 0) return ""; CharSequence t = c.getItemAt(0).coerceToText(this); return t == null ? "" : t.toString(); } catch(Exception e) { return ""; } }
    private String joinArray(JSONArray arr) { if (arr == null || arr.length() == 0) return ""; StringBuilder sb = new StringBuilder(); for (int i = 0; i < arr.length(); i++) { String v = arr.optString(i, "").trim(); if (v.length() == 0) continue; if (sb.length() > 0) sb.append("|"); sb.append(v); } return sb.toString(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
