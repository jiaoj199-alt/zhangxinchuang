package dev.linjian.peek;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机端外卖自动点单状态机。
 *
 * 目标：远端只下发一次 prepare_takeout_checkout，后续点击在手机本地连续完成，
 * 最终停在支付/收银台页面；不会点击任何真正的付款按钮。
 */
public final class TakeoutCheckoutAutomation {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern PAYABLE_PATTERN = Pattern.compile("(?:实付|应付|合计|总计|待支付)[^0-9¥￥]{0,12}[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern COUPON_DISCOUNT_PATTERN = Pattern.compile("((?:满\\s*[0-9]+(?:\\.[0-9]+)?\\s*)?减\\s*([0-9]+(?:\\.[0-9]+)?)\\s*元?)");
    private static String activeId = "";
    private static Runnable pendingTick;

    private TakeoutCheckoutAutomation() {}

    public static synchronized void start(Context rawCtx, JSONObject session) {
        final Context ctx = rawCtx.getApplicationContext();
        cancelRunnableOnly();
        activeId = session.optString("id", "");
        schedule(ctx, activeId, 80);
    }

    public static synchronized void cancel(Context rawCtx, String reason) {
        Context ctx = rawCtx.getApplicationContext();
        String id = activeId;
        cancelRunnableOnly();
        activeId = "";
        try {
            JSONObject s = TakeoutState.checkoutSession(ctx);
            if (id.length() == 0 || id.equals(s.optString("id", ""))) {
                s.put("status", "cancelled");
                s.put("stage", "cancelled");
                s.put("detail", reason == null || reason.length() == 0 ? "已取消自动点单" : reason);
                touch(s);
                TakeoutState.saveCheckoutSession(ctx, s);
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void cancelRunnableOnly() {
        if (pendingTick != null) MAIN.removeCallbacks(pendingTick);
        pendingTick = null;
    }

    private static synchronized void schedule(Context ctx, String sessionId, long delayMs) {
        if (sessionId == null || sessionId.length() == 0) return;
        Runnable r = () -> tick(ctx, sessionId);
        pendingTick = r;
        MAIN.postDelayed(r, Math.max(60, Math.min(4000, delayMs)));
    }

    private static void tick(Context ctx, String sessionId) {
        try {
            JSONObject s = TakeoutState.checkoutSession(ctx);
            if (!sessionId.equals(s.optString("id", ""))) return;
            if (!"running".equals(s.optString("status", ""))) return;
            if (System.currentTimeMillis() > s.optLong("deadline_at_ms", Long.MAX_VALUE)) {
                fail(ctx, s, "timeout", "自动点单超时，已停下，没有继续提交订单。");
                return;
            }

            ScreenshotService svc = ScreenshotService.getInstance();
            if (svc == null) {
                fail(ctx, s, "accessibility_lost", "无障碍服务断开，自动点单已停止。");
                return;
            }

            String stage = s.optString("stage", "starting");
            if ("starting".equals(stage)) {
                String rawLink = s.optString("link", "");
                String jdOpenApp = s.optString("jd_openapp", "").trim();

                // 京东 3.cn 分享短链先在后端解析真实落地页，再包装成京东 openApp 协议。
                // 这样不再经过 Chrome 登录 -> “打开京东” -> 丢失目标页 -> 京东首页这一条脆弱链路。
                if (isJdSession(s) && TakeoutState.isJdShortLink(rawLink) && jdOpenApp.length() == 0) {
                    if (!s.optBoolean("link_resolution_started", false)) {
                        s.put("link_resolution_started", true);
                        setStage(ctx, s, "resolving_link", "正在解析京东分享短链，准备直接交给京东 App。");
                        final String sid = sessionId;
                        final String linkToResolve = rawLink;
                        final String query = s.optString("item_query", "");
                        final String cardId = s.optString("card_id", "");
                        new Thread(() -> {
                            try {
                                JSONObject rr = TakeoutState.resolveJdShortLinkViaBackend(ctx, linkToResolve, query);
                                JSONObject current = TakeoutState.checkoutSession(ctx);
                                if (!sid.equals(current.optString("id", "")) || !"running".equals(current.optString("status", ""))) return;
                                current.put("link_resolution_result", rr);
                                current.put("link_resolution_started", true);
                                if (rr.optBoolean("ok", false) && rr.optString("openapp_url", "").trim().length() > 0) {
                                    current.put("resolved_link", rr.optString("resolved_url", ""));
                                    current.put("jd_openapp", rr.optString("openapp_url", ""));
                                    current.put("link_resolution_source", rr.optString("source", ""));
                                    current.put("search_openapp", rr.optString("search_openapp", ""));
                                    current.put("stage", "starting");
                                    current.put("detail", "京东短链已解析，正在直接打开正确的京东落地页。");
                                    TakeoutState.cacheResolvedMealLink(ctx, cardId, rr);
                                } else {
                                    current.put("link_resolution_failed", true);
                                    current.put("search_openapp", rr.optString("search_openapp", ""));
                                    current.put("stage", "starting");
                                    current.put("detail", "京东短链解析失败，将使用京东 App 内搜索兜底，不再跳 Chrome。");
                                }
                                touch(current);
                                TakeoutState.saveCheckoutSession(ctx, current);
                                schedule(ctx, sid, 80);
                            } catch (Exception e) {
                                try {
                                    JSONObject current = TakeoutState.checkoutSession(ctx);
                                    if (!sid.equals(current.optString("id", ""))) return;
                                    current.put("link_resolution_failed", true);
                                    current.put("stage", "starting");
                                    current.put("detail", "京东短链解析异常，将使用 App 内搜索兜底。");
                                    touch(current); TakeoutState.saveCheckoutSession(ctx, current); schedule(ctx, sid, 80);
                                } catch (Exception ignored) { }
                            }
                        }, "linjian-jd-resolver").start();
                        return;
                    }
                    if (!s.optBoolean("link_resolution_failed", false)) {
                        // 后台解析线程尚未完成，避免重复发起。
                        schedule(ctx, sessionId, 250);
                        return;
                    }
                    String searchOpenApp = s.optString("search_openapp", "").trim();
                    if (searchOpenApp.length() > 0) {
                        JSONObject opened = TakeoutState.openJdOpenApp(ctx, searchOpenApp);
                        if (!opened.optBoolean("ok", false)) {
                            fail(ctx, s, "jd_search_open_failed", opened.optString("error", "京东 App 内搜索打开失败"));
                            return;
                        }
                        s.put("jd_search_fallback", true);
                        setStage(ctx, s, "opening", "短链解析失败，已直接在京东 App 内搜索目标菜，不经过 Chrome。");
                        schedule(ctx, sessionId, 1600);
                        return;
                    }
                    fail(ctx, s, "jd_link_resolve_failed", "京东分享短链无法解析，且没有可用的 App 内搜索兜底；已停止，没有打开 Chrome。请确认 Cloudflare Worker 已更新到 0.4.4.5。");
                    return;
                }

                JSONObject opened;
                if (jdOpenApp.length() > 0) opened = TakeoutState.openJdOpenApp(ctx, jdOpenApp);
                else opened = TakeoutState.openLink(ctx, new JSONObject().put("link", rawLink).put("platform", s.optString("platform", "")));
                if (!opened.optBoolean("ok", false)) {
                    fail(ctx, s, "open_link_failed", opened.optString("error", "外卖链接打开失败"));
                    return;
                }
                setStage(ctx, s, "opening", jdOpenApp.length() > 0 ? "已通过京东 openApp 直达解析后的落地页，开始本地找菜。" : "已打开外卖平台，手机端开始接管后续步骤。");
                schedule(ctx, sessionId, 1700);
                return;
            }

            if ("resolving_link".equals(stage)) {
                schedule(ctx, sessionId, 250);
                return;
            }

            svc.refreshScreenModel();
            String text = ScreenshotService.screenText();
            String pkg = ScreenshotService.currentPackage();
            s.put("current_package", pkg == null ? "" : pkg);
            s.put("screen_hint", compact(text));
            s.put("attempts", s.optInt("attempts", 0) + 1);
            touch(s);
            TakeoutState.saveCheckoutSession(ctx, s);

            // 京东分享短链有些 ROM 仍会先进入 Chrome。这里把“浏览器 -> 打开京东”
            // 作为自动流程的一部分；在真正进入京东前绝不开始找菜/滚屏。
            if (isJdSession(s) && !isJdPackage(pkg) && !s.optBoolean("link_resolution_started", false)) {
                int hops = s.optInt("app_handoff_attempts", 0);
                if (isBrowserPackage(pkg) && tryTapAny(svc, text, "打开京东", "打开京东APP", "打开京东App", "打开App", "打开 APP", "立即打开", "去京东")) {
                    s.put("app_handoff_attempts", hops + 1);
                    setStage(ctx, s, "opening_app", "已自动点击网页里的“打开京东”，正在等待京东接管。");
                    schedule(ctx, sessionId, 1100);
                    return;
                }
                if (hops < 8) {
                    s.put("app_handoff_attempts", hops + 1);
                    if (hops == 2 || hops == 5) {
                        TakeoutState.openLink(ctx, new JSONObject().put("link", s.optString("link", "")).put("platform", "京东"));
                    }
                    setStage(ctx, s, "opening_app", "正在从浏览器自动切到京东（" + (hops + 1) + "/8）。");
                    schedule(ctx, sessionId, 900);
                    return;
                }
                fail(ctx, s, "jd_app_handoff_failed", "外卖链接没有成功交给京东 App。已停止，没有继续乱点；请确认京东已安装并允许打开支持的链接。");
                return;
            }

            // 京东刚接管时页面可能仍在加载。等外卖店铺真正可读后再开始找菜。
            if (isJdSession(s) && isJdPackage(pkg) && ("opening".equals(stage) || "opening_app".equals(stage))) {
                if (containsAny(text, "跳过") && tryTapAny(svc, text, "跳过")) {
                    setStage(ctx, s, "opening_app", "已跳过京东启动页，继续等待外卖店铺。");
                    schedule(ctx, sessionId, 800);
                    return;
                }
                if (s.optBoolean("direct_item", false) && looksLikeDirectMealPage(text)) {
                    s.put("item_title_tapped", true);
                    s.put("store_ready_waits", 0);
                    setStage(ctx, s, "direct_item_ready", "已直达记住的这道饭，不再进店搜索。");
                    schedule(ctx, sessionId, 180);
                    return;
                }
                if (!looksLikeTakeoutStore(text, s.optString("item_query", ""))) {
                    int waits = s.optInt("store_ready_waits", 0) + 1;
                    s.put("store_ready_waits", waits);
                    touch(s);
                    TakeoutState.saveCheckoutSession(ctx, s);
                    if (waits <= 12) {
                        schedule(ctx, sessionId, 850);
                        return;
                    }
                    if (s.optBoolean("direct_item", false)) {
                        s.put("direct_item", false);
                        s.put("direct_fallback", true);
                        setStage(ctx, s, "finding_item", "直达链接没有落到菜品页，已自动回退为店内找菜。");
                        schedule(ctx, sessionId, 250);
                        return;
                    }
                    String fallbackSearch = s.optString("search_openapp", "").trim();
                    if (s.optBoolean("link_resolution_started", false) && fallbackSearch.length() > 0 && !s.optBoolean("jd_search_fallback", false)) {
                        JSONObject fallbackOpened = TakeoutState.openJdOpenApp(ctx, fallbackSearch);
                        if (fallbackOpened.optBoolean("ok", false)) {
                            s.put("jd_search_fallback", true);
                            setStage(ctx, s, "opening", "解析后的页面没有进入外卖店铺，已切换京东 App 内搜索兜底。");
                            schedule(ctx, sessionId, 1400);
                            return;
                        }
                    }
                    fail(ctx, s, "jd_store_not_reached", "京东已打开，但解析后的页面没有落到外卖店铺。已停止，避免在京东首页乱点。");
                    return;
                } else {
                    s.put("store_ready_waits", 0);
                    setStage(ctx, s, "finding_item", "已进入京东外卖店铺，开始本地找菜。");
                    schedule(ctx, sessionId, 180);
                    return;
                }
            }

            // 京东外卖的“立即支付”页同时还是订单确认页：配送时间、餐具和商家备注
            // 仍可能在这里修改。先把这些偏好补齐，再停在最终支付按钮前。
            if (isJdSession(s) && (looksLikeJdCheckoutPage(text) || s.optBoolean("jd_checkout_seen", false) || isJdCheckoutPreferenceStage(stage))) {
                if (looksLikeJdCheckoutPage(text)) {
                    s.put("jd_checkout_seen", true);
                    touch(s);
                    TakeoutState.saveCheckoutSession(ctx, s);
                }
                if (handleJdCheckoutPreferences(ctx, s, svc, text, sessionId)) return;
                if (checkoutPreferencesDone(s) && isPaymentPage(text)) {
                    ready(ctx, s, "配送、餐具、备注和优惠已检查，已停在付款页，等待用户本人确认支付。");
                    return;
                }
                // 已经进入京东订单确认流程后，不再把弹层/滚动后的页面误当成店铺继续找菜。
                schedule(ctx, sessionId, 400);
                return;
            } else if (isPaymentPage(text)) {
                ready(ctx, s, "已到付款页，等待用户本人确认支付。");
                return;
            }

            if (text == null || text.trim().length() == 0) {
                int blanks = s.optInt("blank_count", 0) + 1;
                s.put("blank_count", blanks);
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                if (blanks > 14) {
                    fail(ctx, s, "screen_unreadable", "连续读不到外卖页面控件，已停止自动点单。");
                } else schedule(ctx, sessionId, 700);
                return;
            }
            s.put("blank_count", 0);

            // 订单确认页：先处理备注，再核对金额，最后只点击“提交订单”，随后等待收银台。
            if (containsAny(text, "提交订单", "确认下单")) {
                if (!s.optBoolean("note_applied", false) && s.optString("note", "").trim().length() > 0) {
                    if (tryTapAny(svc, text, "订单备注", "备注", "备注信息", "口味备注")) {
                        setStage(ctx, s, "note_editor", "正在填写下单备注。");
                        schedule(ctx, sessionId, 450);
                        return;
                    }
                    // 页面没有备注入口时不阻塞整单；记录为 skipped。
                    s.put("note_applied", true);
                    s.put("note_result", "entry_not_found_skipped");
                }
                if (!budgetAllowed(s, text)) {
                    double payable = extractPayable(text);
                    fail(ctx, s, "amount_over_limit", "结算金额 ¥" + WalletState.money(payable) + " 超过自动点单上限 ¥" + WalletState.money(s.optDouble("max_total", 0)) + "，已停止提交。");
                    return;
                }
                if (!s.optBoolean("submit_order", true)) {
                    ready(ctx, s, "已到订单确认页，按设置不自动提交订单。");
                    return;
                }
                if (tryTapAny(svc, text, "提交订单", "确认下单")) {
                    setStage(ctx, s, "submitting", "订单信息已确认，正在进入付款页；不会点击真正支付按钮。");
                    schedule(ctx, sessionId, 1300);
                    return;
                }
            }

            // 备注弹层/输入页。
            if ("note_editor".equals(stage) || containsAny(text, "请输入备注", "订单备注", "备注信息")) {
                String note = s.optString("note", "").trim();
                if (note.length() == 0) {
                    s.put("note_applied", true);
                    setStage(ctx, s, "checkout", "无需填写备注，继续结算。");
                    schedule(ctx, sessionId, 250);
                    return;
                }
                if (!s.optBoolean("note_text_entered", false)) {
                    JSONObject rr = svc.inputText(note, false);
                    if (rr.optBoolean("ok", false)) {
                        s.put("note_text_entered", true);
                        s.put("note_result", "input_ok");
                        touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                        schedule(ctx, sessionId, 350);
                        return;
                    }
                }
                if (tryTapAny(svc, text, "完成", "确定", "保存", "确认")) {
                    s.put("note_applied", true);
                    setStage(ctx, s, "checkout", "备注已填写，继续结算。");
                    schedule(ctx, sessionId, 500);
                    return;
                }
                // 有些平台输入后自动保存；尝试返回订单页。
                if (s.optBoolean("note_text_entered", false) && s.optInt("attempts", 0) % 3 == 0 && svc.doBack()) {
                    s.put("note_applied", true);
                    s.put("note_result", "input_then_back");
                    setStage(ctx, s, "checkout", "备注已输入并返回订单页。");
                    schedule(ctx, sessionId, 500);
                    return;
                }
            }

            // 店内搜索页：自动输入更具体的菜名关键词。
            if ("item_search".equals(stage)) {
                String keyword = searchKeyword(s.optString("item_query", ""));
                if (!s.optBoolean("item_search_text_entered", false)) {
                    JSONObject rr = svc.inputText(keyword, false);
                    if (rr.optBoolean("ok", false)) {
                        s.put("item_search_text_entered", true);
                        setStage(ctx, s, "item_search", "已输入店内搜索词：“ + keyword + ”。");
                        schedule(ctx, sessionId, 350);
                        return;
                    }
                }
                if (s.optBoolean("item_search_text_entered", false) && tryTapAny(svc, text, "搜索商品", "搜索", "确认")) {
                    s.put("scroll_count", 0);
                    setStage(ctx, s, "search_results", "已提交店内搜索：“ + keyword + ”，正在匹配结果。");
                    schedule(ctx, sessionId, 850);
                    return;
                }
                schedule(ctx, sessionId, 450);
                return;
            }

            // 购物车结算入口。
            if (containsAny(text, "去结算", "结算")) {
                if (tryTapAny(svc, text, "去结算", "结算")) {
                    setStage(ctx, s, "checkout", "商品已选好，正在进入订单确认页。");
                    schedule(ctx, sessionId, 950);
                    return;
                }
            }

            // 必选项入口。
            if (containsAny(text, "未选必选品", "下单必选", "请选择必选")) {
                if (tryTapAny(svc, text, "未选必选品", "下单必选", "请选择必选")) {
                    setStage(ctx, s, "required_choices", "正在补齐下单必选项。");
                    schedule(ctx, sessionId, 550);
                    return;
                }
            }

            // 记住的饭已经直达商品页时，不再找标题，直接进入“去抢购/选规格”。
            if (s.optBoolean("direct_item", false) && containsAny(text, "去抢购", "选规格", "立即选购", "选购")) {
                if (tryTapAny(svc, text, "去抢购", "选规格", "立即选购", "选购")) {
                    setStage(ctx, s, "item_options", "已打开记住菜品的规格。");
                    schedule(ctx, sessionId, 650);
                    return;
                }
            }

            // 规格/必选弹层：优先用卡片里保存的选项；没有配置时只使用低风险默认词，不瞎选未知口味。
            if (containsAny(text, "加入购物车", "加入购物袋")) {
                JSONArray choices = s.optJSONArray("choices");
                String selectedLine = selectedText(text);
                String choice = nextChoice(text, selectedLine, choices);
                if (choice.length() > 0) {
                    if (tryTap(svc, choice, "contains")) {
                        appendAppliedChoice(s, choice);
                        setStage(ctx, s, "required_choices", "已选择：" + choice);
                        schedule(ctx, sessionId, 350);
                        return;
                    }
                } else if (hasUnresolvedRequiredChoice(text, choices, s)) {
                    fail(ctx, s, "required_choice_needs_config", "发现必选规格，但常点卡没有可安全识别的默认选项。请在常点卡里填写“自动选择项”。");
                    return;
                }
                if (tryTapAny(svc, text, "加入购物车", "加入购物袋")) {
                    setStage(ctx, s, "cart", "必选项已处理，商品已加入购物车。");
                    schedule(ctx, sessionId, 850);
                    return;
                }
            }

            // 商品按钮：点过商品标题后，再点“去抢购/选规格”。
            if (s.optBoolean("item_title_tapped", false) && containsAny(text, "去抢购", "选规格", "选购", "立即选购")) {
                if (tryTapAny(svc, text, "去抢购", "选规格", "立即选购", "选购")) {
                    setStage(ctx, s, "item_options", "已打开商品规格。");
                    schedule(ctx, sessionId, 650);
                    return;
                }
            }

            // 找目标商品：支持安全关键词变体。比如“土豆片炒肉木桶饭 1人份”
            // 会同时尝试“土豆片炒肉木桶饭”和“土豆片炒肉”。
            List<String> candidates = itemCandidates(s.optString("item_query", ""));
            String item = candidates.isEmpty() ? "" : candidates.get(0);
            String matched = firstVisibleCandidate(text, candidates);
            if (matched.length() > 0) {
                if (tryTap(svc, matched, "contains")) {
                    s.put("item_title_tapped", true);
                    s.put("matched_item_keyword", matched);
                    setStage(ctx, s, "item_selected", "已定位目标商品：" + matched);
                    schedule(ctx, sessionId, 600);
                    return;
                }
            }

            int scrolls = s.optInt("scroll_count", 0);

            // 京东店铺页优先直接用店内搜索，不再先滚菜单。只要页面暴露“搜索”节点就立即搜索目标菜。
            if (isJdSession(s) && !s.optBoolean("item_search_attempted", false) && containsAny(text, "搜索")) {
                if (tryTapAny(svc, text, "搜索商品", "搜索")) {
                    s.put("item_search_attempted", true);
                    s.put("item_search_text_entered", false);
                    setStage(ctx, s, "item_search", "已打开京东店内搜索，直接查目标菜，不再滚菜单碰运气。");
                    schedule(ctx, sessionId, 650);
                    return;
                }
            }

            int maxScrolls = "search_results".equals(stage) ? 5 : 12;
            if (item.length() > 0 && scrolls < maxScrolls) {
                int w = ctx.getResources().getDisplayMetrics().widthPixels;
                int h = ctx.getResources().getDisplayMetrics().heightPixels;
                boolean swiped = svc.doSwipe(w * 0.52f, h * 0.78f, w * 0.52f, h * 0.34f, 320);
                if (swiped) {
                    s.put("scroll_count", scrolls + 1);
                    setStage(ctx, s, "search_results".equals(stage) ? "search_results" : "finding_item",
                            "正在店铺里寻找“" + searchKeyword(s.optString("item_query", "")) + "”（" + (scrolls + 1) + "/" + maxScrolls + "）。");
                    schedule(ctx, sessionId, 650);
                    return;
                }
            }

            // 没配置商品关键词时才允许使用当前第一个可见商品，避免点错菜。
            if (item.length() == 0 && containsAny(text, "去抢购", "选规格")) {
                if (tryTapAny(svc, text, "去抢购", "选规格")) {
                    setStage(ctx, s, "item_options", "未配置商品关键词，使用当前可见商品进入规格页。");
                    schedule(ctx, sessionId, 650);
                    return;
                }
            }

            if (scrolls >= maxScrolls && !s.optBoolean("item_title_tapped", false)) {
                fail(ctx, s, "item_not_found", "已用关键词变体和店内搜索查找，仍没找到目标商品“" + searchKeyword(s.optString("item_query", "")) + "”，已停止，避免点错菜。");
                return;
            }

            schedule(ctx, sessionId, 650);
        } catch (Exception e) {
            try {
                JSONObject s = TakeoutState.checkoutSession(ctx);
                fail(ctx, s, "automation_exception", ScreenshotService.shortMsg(e));
            } catch (Exception ignored) {}
        }
    }

    private static boolean handleJdCheckoutPreferences(Context ctx, JSONObject s, ScreenshotService svc, String text, String sessionId) {
        try {
            String stage = s.optString("stage", "checkout");

            // 1) 配送时间：保存的外卖默认优先“立即送”，避免上一次预约时间残留到下一单。
            if ("delivery_time_editor".equals(stage) || "delivery_time_confirm".equals(stage)) {
                if ("delivery_time_confirm".equals(stage) && s.optBoolean("delivery_option_tapped", false)) {
                    if (tryTapAny(svc, text, "确定", "完成", "保存", "确认")) {
                        s.put("delivery_checked", true);
                        s.put("delivery_result", "immediate_delivery");
                        setStage(ctx, s, "checkout_preferences", "已设为立即送，继续检查餐具和商家备注。");
                        schedule(ctx, sessionId, 500);
                        return true;
                    }
                    // 没有确认按钮且已经回到订单页，说明京东自动保存了选择。
                    if (looksLikeJdCheckoutPage(text) && !containsAny(text, "选择配送时间", "配送时间")) {
                        s.put("delivery_checked", true);
                        s.put("delivery_result", "immediate_delivery_auto_saved");
                        setStage(ctx, s, "checkout_preferences", "立即送已生效，继续检查餐具和商家备注。");
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                } else if (containsAny(text, "立即送")) {
                    if (tryTapAny(svc, text, "立即送预计", "立即送")) {
                        s.put("delivery_option_tapped", true);
                        setStage(ctx, s, "delivery_time_confirm", "已选择立即送，正在保存配送时间。");
                        schedule(ctx, sessionId, 450);
                        return true;
                    }
                }
                int tries = s.optInt("delivery_editor_tries", 0) + 1;
                s.put("delivery_editor_tries", tries);
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                if (tries <= 5) {
                    schedule(ctx, sessionId, 450);
                    return true;
                }
                svc.doBack();
                s.put("delivery_checked", true);
                if (containsAny(text, "预约送", "明天", "后天", "选择预约时间")) {
                    s.put("delivery_result", "reservation_only");
                    setStage(ctx, s, "checkout_preferences", "当前只提供预约配送，已保留可用预约时间并继续。");
                } else {
                    s.put("delivery_result", "immediate_option_not_found");
                    setStage(ctx, s, "checkout_preferences", "没有识别到立即送选项，已保留当前配送时间并继续。");
                }
                schedule(ctx, sessionId, 450);
                return true;
            }

            if (!s.optBoolean("delivery_checked", false)) {
                // 页面已经明确是立即送时无需再打开选择器。
                if (containsAny(text, "立即送预计", "立即送达") && !containsAny(text, "预约送")) {
                    s.put("delivery_checked", true);
                    s.put("delivery_result", "already_immediate");
                    touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                } else if (containsAny(text, "预约送", "选择时间") && tryTapAny(svc, text, "预约送", "选择时间")) {
                    setStage(ctx, s, "delivery_time_editor", "当前是预约配送，正在切换为立即送。");
                    schedule(ctx, sessionId, 500);
                    return true;
                } else {
                    int tries = s.optInt("delivery_entry_tries", 0) + 1;
                    s.put("delivery_entry_tries", tries);
                    if (tries >= 3) {
                        s.put("delivery_checked", true);
                        s.put("delivery_result", "entry_not_found_skipped");
                    }
                    touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                    if (!s.optBoolean("delivery_checked", false)) {
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                }
            }

            // 2) 餐具：从饭卡 choices 里找“餐具”偏好，在结算页再核对一次。
            String desiredTableware = desiredTablewareChoice(s.optJSONArray("choices"));
            if (!s.optBoolean("tableware_checked", false) && choiceAlreadyApplied(s, desiredTableware)) {
                s.put("tableware_checked", true);
                s.put("tableware_result", "applied_in_item_options");
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
            }
            if (desiredTableware.length() == 0) {
                s.put("tableware_checked", true);
                s.put("tableware_result", "not_configured");
            }
            if ("tableware_editor".equals(stage) || "tableware_confirm".equals(stage)) {
                if ("tableware_confirm".equals(stage) && s.optBoolean("tableware_option_tapped", false)) {
                    if (tryTapAny(svc, text, "确定", "完成", "保存", "确认")) {
                        s.put("tableware_checked", true);
                        s.put("tableware_result", "applied");
                        setStage(ctx, s, "checkout_preferences", "餐具偏好已保存，继续填写商家备注。");
                        schedule(ctx, sessionId, 450);
                        return true;
                    }
                    // 没有确认按钮且已经回订单页，说明京东自动保存了选择。
                    if (looksLikeJdCheckoutPage(text) && !containsAny(text, "餐具数量", "选择餐具")) {
                        s.put("tableware_checked", true);
                        s.put("tableware_result", "applied_auto_saved");
                        setStage(ctx, s, "checkout_preferences", "餐具偏好已生效，继续填写商家备注。");
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                } else if (desiredTableware.length() > 0) {
                    String candidate = tablewareVisibleChoice(text, desiredTableware);
                    if (candidate.length() > 0 && tryTap(svc, candidate, "contains")) {
                        s.put("tableware_option_tapped", true);
                        appendAppliedChoice(s, desiredTableware);
                        setStage(ctx, s, "tableware_confirm", "已选择餐具偏好：" + desiredTableware + "，正在保存。");
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                }
                int tries = s.optInt("tableware_editor_tries", 0) + 1;
                s.put("tableware_editor_tries", tries);
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                if (tries <= 4) {
                    schedule(ctx, sessionId, 400);
                    return true;
                }
                svc.doBack();
                s.put("tableware_checked", true);
                s.put("tableware_result", "option_not_found_skipped");
                setStage(ctx, s, "checkout_preferences", "没有识别到餐具选项，已保留当前设置并继续。");
                schedule(ctx, sessionId, 450);
                return true;
            }

            if (!s.optBoolean("tableware_checked", false)) {
                if (tablewareAlreadyMatches(text, desiredTableware)) {
                    s.put("tableware_checked", true);
                    s.put("tableware_result", "already_matches");
                    appendAppliedChoice(s, desiredTableware);
                    touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                } else if (containsAny(text, "餐具数量", "选择餐具", "餐具") && tryTapAny(svc, text, "餐具数量", "选择餐具", "餐具")) {
                    setStage(ctx, s, "tableware_editor", "正在设置餐具偏好：" + desiredTableware + "。");
                    schedule(ctx, sessionId, 450);
                    return true;
                }
            }

            // 3) 商家备注。配送偏好里的“不打电话”不能代替给商家的“少辣”等备注。
            if (s.optBoolean("note_applied", false) && !s.optBoolean("merchant_note_checked", false)) {
                s.put("merchant_note_checked", true);
                if (s.optString("note_result", "").length() == 0) s.put("note_result", "applied");
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
            }
            if ("note_editor".equals(stage) || containsAny(text, "请输入备注", "商家备注", "订单备注", "口味备注")) {
                String note = s.optString("note", "").trim();
                if (note.length() == 0) {
                    s.put("note_applied", true);
                    s.put("merchant_note_checked", true);
                    s.put("note_result", "empty");
                    setStage(ctx, s, "checkout_preferences", "无需商家备注，准备返回付款按钮。");
                    schedule(ctx, sessionId, 300);
                    return true;
                }
                if (!s.optBoolean("note_text_entered", false)) {
                    JSONObject rr = svc.inputText(note, false);
                    if (rr.optBoolean("ok", false)) {
                        s.put("note_text_entered", true);
                        s.put("note_result", "input_ok");
                        touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                    // 京东有时先打开“备注/口味偏好”页，但真正输入框还需要再点一下。
                    if (!s.optBoolean("note_input_entry_tapped", false)
                            && tryTapAny(svc, text, "请输入留言", "填写备注", "请输入备注", "备注输入", "留言")) {
                        s.put("note_input_entry_tapped", true);
                        setStage(ctx, s, "note_editor", "已进入备注输入框，正在填写：" + note + "。");
                        schedule(ctx, sessionId, 350);
                        return true;
                    }
                }
                if (tryTapAny(svc, text, "完成", "确定", "保存", "确认")) {
                    s.put("note_applied", true);
                    s.put("merchant_note_checked", true);
                    s.put("note_result", "saved");
                    setStage(ctx, s, "checkout_preferences", "商家备注已填写，准备返回付款按钮。");
                    schedule(ctx, sessionId, 500);
                    return true;
                }
                if (s.optBoolean("note_text_entered", false) && s.optInt("attempts", 0) % 3 == 0 && svc.doBack()) {
                    s.put("note_applied", true);
                    s.put("merchant_note_checked", true);
                    s.put("note_result", "input_then_back");
                    setStage(ctx, s, "checkout_preferences", "商家备注已输入并返回订单页。");
                    schedule(ctx, sessionId, 500);
                    return true;
                }
                int noteTries = s.optInt("note_editor_tries", 0) + 1;
                s.put("note_editor_tries", noteTries);
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                if (noteTries >= 6) {
                    svc.doBack();
                    s.put("merchant_note_checked", true);
                    s.put("note_applied", false);
                    s.put("note_result", "input_failed_skipped");
                    setStage(ctx, s, "checkout_preferences", "商家备注输入框无法可靠操作，已跳过并继续，避免卡住整单。");
                    schedule(ctx, sessionId, 450);
                    return true;
                }
                schedule(ctx, sessionId, 350);
                return true;
            }

            if (!s.optBoolean("merchant_note_checked", false)) {
                String note = s.optString("note", "").trim();
                if (note.length() == 0) {
                    s.put("note_applied", true);
                    s.put("merchant_note_checked", true);
                    s.put("note_result", "empty");
                    touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                } else if (tryTapAny(svc, text, "留言请选择口味偏好", "商家备注", "订单备注", "口味备注", "备注信息", "备注")) {
                    setStage(ctx, s, "note_editor", "正在填写商家备注：" + note + "。");
                    schedule(ctx, sessionId, 450);
                    return true;
                }
            }

            // 4) 优惠：默认追求当前订单“最终实付最低”。京东已经自动享受优惠时直接保留；
            // 若页面明确提示有可用券但尚未使用，则打开优惠券页并优先选择平台标注的最优/推荐方案。
            // 不为了凑满减额外加购，也不自动购买会员、月卡或开通付费权益。
            if (handleJdCouponOptimization(ctx, s, svc, text, sessionId)) return true;

            // 京东确认页内容很长，备注/餐具入口可能在下方。最多滚 5 次寻找；找不到就记录 skipped，
            // 但不会因此误点付款。
            if (!checkoutPreferencesDone(s)) {
                int scrolls = s.optInt("checkout_pref_scrolls", 0);
                if (scrolls < 5) {
                    int w = ctx.getResources().getDisplayMetrics().widthPixels;
                    int h = ctx.getResources().getDisplayMetrics().heightPixels;
                    if (svc.doSwipe(w * 0.52f, h * 0.78f, w * 0.52f, h * 0.38f, 300)) {
                        s.put("checkout_pref_scrolls", scrolls + 1);
                        setStage(ctx, s, "checkout_preferences", "正在订单页寻找餐具/商家备注入口（" + (scrolls + 1) + "/5）。");
                        schedule(ctx, sessionId, 450);
                        return true;
                    }
                }
                if (!s.optBoolean("tableware_checked", false)) {
                    s.put("tableware_checked", true);
                    s.put("tableware_result", "entry_not_found_skipped");
                }
                if (!s.optBoolean("merchant_note_checked", false)) {
                    s.put("merchant_note_checked", true);
                    s.put("note_applied", false);
                    s.put("note_result", "entry_not_found_skipped");
                }
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
            }

            // 全部检查完后，把页面滚回“立即支付”按钮再交给用户。
            if (checkoutPreferencesDone(s) && !isPaymentPage(text)) {
                int backScrolls = s.optInt("checkout_payment_scrolls", 0);
                if (backScrolls < 7) {
                    int w = ctx.getResources().getDisplayMetrics().widthPixels;
                    int h = ctx.getResources().getDisplayMetrics().heightPixels;
                    if (svc.doSwipe(w * 0.52f, h * 0.80f, w * 0.52f, h * 0.30f, 320)) {
                        s.put("checkout_payment_scrolls", backScrolls + 1);
                        setStage(ctx, s, "checkout_return_to_payment", "偏好已检查，正在回到立即支付按钮。");
                        schedule(ctx, sessionId, 400);
                        return true;
                    }
                }
                fail(ctx, s, "payment_button_not_found", "配送、餐具、备注和优惠已检查，但没有重新找到立即支付按钮；已停下，没有执行付款。");
                return true;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean handleJdCouponOptimization(Context ctx, JSONObject s, ScreenshotService svc, String text, String sessionId) {
        try {
            if (s.optBoolean("coupon_checked", false)) return false;
            String mode = s.optString("coupon_mode", "platform_default").trim().toLowerCase(Locale.US);
            if ("none".equals(mode) || "off".equals(mode) || "disabled".equals(mode) || "no_coupon".equals(mode)) {
                s.put("coupon_checked", true);
                s.put("coupon_result", "disabled");
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                return false;
            }

            String stage = s.optString("stage", "checkout_preferences");
            if ("coupon_editor".equals(stage) || "coupon_confirm".equals(stage)) {
                // 京东若明确标了“最优/最佳/推荐”，优先使用平台已经算好的最低实付方案。
                String recommended = firstCouponRecommendation(text);
                if (!s.optBoolean("coupon_option_tapped", false) && recommended.length() > 0 && tryTap(svc, recommended, "contains")) {
                    s.put("coupon_option_tapped", true);
                    s.put("coupon_choice", recommended);
                    setStage(ctx, s, "coupon_confirm", "已选择京东推荐的最优优惠方案，正在确认。");
                    schedule(ctx, sessionId, 350);
                    return true;
                }

                // 能可靠读出“满X减Y/减Y元”时选当前页面减免最大的券；不按“使用”按钮盲选。
                String bestLabel = bestCouponVisibleLabel(text);
                if (!s.optBoolean("coupon_option_tapped", false) && bestLabel.length() > 0 && tryTap(svc, bestLabel, "contains")) {
                    s.put("coupon_option_tapped", true);
                    s.put("coupon_choice", bestLabel);
                    setStage(ctx, s, "coupon_confirm", "已选择当前可见减免最大的优惠券，正在确认。");
                    schedule(ctx, sessionId, 350);
                    return true;
                }

                if (s.optBoolean("coupon_option_tapped", false)) {
                    if (tryTapAny(svc, text, "完成", "确定", "确认使用", "确认", "保存")) {
                        setStage(ctx, s, "checkout_preferences", "优惠券已选择，正在回订单页核对实付金额。");
                        schedule(ctx, sessionId, 500);
                        return true;
                    }
                    // 很多京东优惠选择后会自动返回/自动保存。
                    if (looksLikeJdCheckoutPage(text)) {
                        double now = extractPayable(text);
                        if (now > 0) s.put("coupon_payable_after", WalletState.round2(now));
                        s.put("coupon_checked", true);
                        s.put("coupon_result", "best_visible_applied");
                        setStage(ctx, s, "checkout_preferences", "优惠已应用，继续准备付款页。");
                        schedule(ctx, sessionId, 300);
                        return true;
                    }
                }

                // 页面明确表示已选最优/已使用优惠时直接完成。
                if (containsAny(text, "已选最优", "已选择最优", "当前最优", "已使用", "已领取并使用")) {
                    s.put("coupon_checked", true);
                    s.put("coupon_result", "platform_best_selected");
                    svc.doBack();
                    setStage(ctx, s, "checkout_preferences", "京东已使用最优优惠，返回订单页。");
                    schedule(ctx, sessionId, 450);
                    return true;
                }

                int tries = s.optInt("coupon_editor_tries", 0) + 1;
                s.put("coupon_editor_tries", tries);
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                if (tries >= 5) {
                    // 无法可靠判定时保留京东当前默认优惠，绝不随机点付费会员/凑单入口。
                    svc.doBack();
                    s.put("coupon_checked", true);
                    s.put("coupon_result", "platform_default_kept");
                    setStage(ctx, s, "checkout_preferences", "优惠页没有可安全识别的更优方案，保留京东当前优惠。");
                    schedule(ctx, sessionId, 450);
                    return true;
                }
                schedule(ctx, sessionId, 350);
                return true;
            }

            double payable = extractPayable(text);
            if (!s.has("coupon_payable_before") && payable > 0) s.put("coupon_payable_before", WalletState.round2(payable));

            // “已享/已减/超级补贴”等说明京东已经自动把优惠算进当前实付，直接保留。
            if (couponAlreadyApplied(text)) {
                s.put("coupon_checked", true);
                s.put("coupon_result", "platform_auto_applied");
                if (payable > 0) s.put("coupon_payable_after", WalletState.round2(payable));
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
                return false;
            }

            // 只有明确看到优惠券入口/可用券提示时才进入选择，不碰会员月卡和凑单入口。
            if (containsAny(text, "优惠券", "可用券", "有券可用")
                    && !containsAny(text, "购买后", "开通会员", "购买月卡")) {
                if (tryTapAny(svc, text, "优惠券", "可用券")) {
                    setStage(ctx, s, "coupon_editor", "发现可用优惠，正在选择最终实付更低的方案。");
                    schedule(ctx, sessionId, 500);
                    return true;
                }
            }

            int entryTries = s.optInt("coupon_entry_tries", 0) + 1;
            s.put("coupon_entry_tries", entryTries);
            if (entryTries >= 2) {
                s.put("coupon_checked", true);
                s.put("coupon_result", "no_coupon_entry");
            }
            touch(s); TakeoutState.saveCheckoutSession(ctx, s);
            return false;
        } catch (Exception e) {
            try {
                s.put("coupon_checked", true);
                s.put("coupon_result", "coupon_check_error_kept_default");
                touch(s); TakeoutState.saveCheckoutSession(ctx, s);
            } catch (Exception ignored) {}
            return false;
        }
    }

    private static boolean couponAlreadyApplied(String text) {
        if (text == null) return false;
        if (!containsAny(text, "优惠券", "补贴", "优惠")) return false;
        return containsAny(text, "已享", "已使用", "已选最优", "已选择最优", "门店加补");
    }

    private static String firstCouponRecommendation(String text) {
        if (text == null) return "";
        String[] labels = {"最优优惠", "最优方案", "最佳优惠", "推荐使用", "平台推荐", "推荐"};
        for (String v : labels) if (text.contains(v)) return v;
        return "";
    }

    private static String bestCouponVisibleLabel(String text) {
        if (text == null) return "";
        Matcher m = COUPON_DISCOUNT_PATTERN.matcher(text);
        String best = "";
        double bestValue = -1;
        while (m.find()) {
            String label = m.group(1) == null ? "" : m.group(1).trim();
            double value = -1;
            try { value = Double.parseDouble(m.group(2)); } catch (Exception ignored) {}
            if (value > bestValue && label.length() > 0) {
                bestValue = value;
                best = label;
            }
        }
        return best;
    }

    private static boolean isJdCheckoutPreferenceStage(String stage) {
        return "checkout_preferences".equals(stage)
                || "delivery_time_editor".equals(stage)
                || "delivery_time_confirm".equals(stage)
                || "tableware_editor".equals(stage)
                || "tableware_confirm".equals(stage)
                || "note_editor".equals(stage)
                || "coupon_editor".equals(stage)
                || "coupon_confirm".equals(stage)
                || "checkout_return_to_payment".equals(stage);
    }

    private static boolean choiceAlreadyApplied(JSONObject s, String desired) {
        if (desired == null || desired.length() == 0) return false;
        JSONArray a = s.optJSONArray("applied_choices");
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "");
            if (desired.equals(v) || (desired.contains("餐具") && v.contains("餐具") && tablewareSameIntent(desired, v))) return true;
        }
        return false;
    }

    private static boolean tablewareSameIntent(String a, String b) {
        boolean noA = a.contains("不需要") || a.contains("不要") || a.contains("无需");
        boolean noB = b.contains("不需要") || b.contains("不要") || b.contains("无需");
        return noA == noB;
    }

    private static boolean checkoutPreferencesDone(JSONObject s) {
        return s.optBoolean("delivery_checked", false)
                && s.optBoolean("tableware_checked", false)
                && s.optBoolean("merchant_note_checked", false)
                && s.optBoolean("coupon_checked", false);
    }

    private static boolean looksLikeJdCheckoutPage(String text) {
        if (text == null || text.trim().length() == 0) return false;
        return containsAny(text, "应付总额", "设置配送偏好", "配送", "自提")
                && containsAny(text, "立即支付", "提交订单", "确认下单")
                && containsAny(text, "京东秒送说明", "点餐请适量", "送达", "预约送", "立即送");
    }

    private static String desiredTablewareChoice(JSONArray choices) {
        if (choices == null) return "";
        for (int i = 0; i < choices.length(); i++) {
            String c = choices.optString(i, "").trim();
            if (c.contains("餐具")) return c;
        }
        return "";
    }

    private static boolean tablewareAlreadyMatches(String text, String desired) {
        if (text == null || desired == null || desired.length() == 0) return false;
        if (text.contains(desired)) return true;
        if (desired.contains("不需要") || desired.contains("不要") || desired.contains("无需")) {
            return containsAny(text, "不需要餐具", "不要餐具", "无需餐具", "0份餐具");
        }
        return containsAny(text, "需要餐具", "1份餐具", "1套餐具", "餐具1份", "商家按餐量提供", "商家按订单提供", "按餐量提供");
    }

    private static String tablewareVisibleChoice(String text, String desired) {
        if (text == null || desired == null || desired.length() == 0) return "";
        if (text.contains(desired)) return desired;
        if (desired.contains("不需要") || desired.contains("不要") || desired.contains("无需")) {
            String[] no = {"不需要餐具", "不要餐具", "无需餐具", "0份餐具"};
            for (String v : no) if (text.contains(v)) return v;
        } else {
            String[] yes = {"需要餐具", "1份餐具", "1套餐具", "餐具1份"};
            for (String v : yes) if (text.contains(v)) return v;
        }
        return "";
    }

    private static boolean budgetAllowed(JSONObject s, String text) {
        if (!s.optBoolean("strict_budget", false)) return true;
        double max = s.optDouble("max_total", 0);
        if (max <= 0) return true;
        double payable = extractPayable(text);
        if (payable <= 0) return true; // 无法可靠读取时不误伤，状态里仍会保留 screen_hint。
        try { s.put("detected_payable", WalletState.round2(payable)); } catch (Exception ignored) {}
        return payable <= max + 0.001;
    }

    private static double extractPayable(String text) {
        if (text == null) return -1;
        Matcher m = PAYABLE_PATTERN.matcher(text.replace("|", " "));
        double last = -1;
        while (m.find()) {
            try { last = Double.parseDouble(m.group(1)); } catch (Exception ignored) {}
        }
        return last;
    }

    private static boolean isPaymentPage(String text) {
        if (text == null || text.length() == 0) return false;
        if (text.contains("提交订单") || text.contains("确认下单")) return false;
        return containsAny(text, "收银台", "确认支付", "立即支付", "待付款", "支付订单", "确认付款", "付款码");
    }

    private static boolean hasUnresolvedRequiredChoice(String text, JSONArray configured, JSONObject s) {
        if (!containsAny(text, "餐盒", "规格", "口味", "必选", "打包方式")) return false;
        if (configured != null && configured.length() > 0) {
            JSONArray applied = s.optJSONArray("applied_choices");
            return applied == null || applied.length() < configured.length();
        }
        return !containsAny(text, "不需要隔层", "不需要餐具", "默认", "标准");
    }

    private static String nextChoice(String text, String selectedLine, JSONArray configured) {
        if (configured != null && configured.length() > 0) {
            for (int i = 0; i < configured.length(); i++) {
                String c = configured.optString(i, "").trim();
                if (c.length() == 0) continue;
                if (selectedLine.contains(c)) continue;
                if (text.contains(c)) return c;
            }
            return "";
        }
        String[] defaults = {"不需要隔层", "不需要餐具", "默认", "标准"};
        for (String d : defaults) if (!selectedLine.contains(d) && text.contains(d)) return d;
        return "";
    }

    private static String selectedText(String text) {
        int p = text.indexOf("已选：");
        if (p < 0) p = text.indexOf("已选:");
        if (p < 0) return "";
        int end = text.indexOf("|", p);
        if (end < 0) end = Math.min(text.length(), p + 180);
        return text.substring(p, end);
    }

    private static void appendAppliedChoice(JSONObject s, String choice) {
        try {
            JSONArray a = s.optJSONArray("applied_choices");
            if (a == null) a = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (choice.equals(a.optString(i))) return;
            a.put(choice); s.put("applied_choices", a);
        } catch (Exception ignored) {}
    }

    private static List<String> itemCandidates(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.length() == 0) return out;
        // 中文菜名没有可靠的 \b 边界，直接清理“1人份/2份”等数量后缀。
        s = s.replaceAll("[0-9]+\\s*(?:人份|份|个|盒|套)", " ");
        s = s.replace('+', ' ').replace('＋', ' ').replace('/', ' ').replace('，', ' ').replace(',', ' ');
        s = s.replaceAll("[【】\\[\\]（）()]+", " ").replaceAll("\\s+", " ").trim();
        for (String part : s.split("\\s+")) {
            String v = part.trim();
            if (v.length() < 2 || "外卖".equals(v)) continue;
            addCandidate(out, v);
            String stripped = v.replaceFirst("(?:木桶饭|盖饭|拌饭|炒饭|米饭|套餐|饭)$", "").trim();
            if (stripped.length() >= 3) addCandidate(out, stripped);
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out;
    }

    private static void addCandidate(List<String> out, String v) {
        if (v == null) return;
        String s = v.trim();
        if (s.length() < 2) return;
        for (String old : out) if (old.equals(s)) return;
        out.add(s);
    }

    private static String firstVisibleCandidate(String text, List<String> candidates) {
        if (text == null || candidates == null) return "";
        for (String c : candidates) if (c.length() >= 2 && text.contains(c)) return c;
        return "";
    }

    private static String searchKeyword(String raw) {
        List<String> candidates = itemCandidates(raw);
        if (candidates.isEmpty()) return "";
        for (String c : candidates) {
            if (!c.endsWith("木桶饭") && !c.endsWith("套餐") && c.length() >= 3) return c;
        }
        return candidates.get(0);
    }

    private static boolean isJdSession(JSONObject s) {
        String v = (s.optString("platform", "") + " " + s.optString("link", "")).toLowerCase(Locale.US);
        return v.contains("京东") || v.contains("3.cn") || v.contains("jd.com") || v.contains("jingdong");
    }

    private static boolean isJdPackage(String pkg) {
        return "com.jingdong.app.mall".equals(pkg);
    }

    private static boolean isBrowserPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("chrome") || p.contains("browser") || p.contains("huaweibrowser") || p.contains("quark") || p.contains("ucmobile");
    }

    private static boolean looksLikeDirectMealPage(String text) {
        if (text == null || text.trim().length() == 0) return false;
        // 店铺列表本身也会出现很多“去抢购”，不能把它误判成具体菜品页。
        // 只有出现规格/数量/已选等商品详情特征时才认为真正直达了某一道饭。
        return containsAny(text, "已选：", "已选:", "数量", "增加数量", "减少数量", "加入购物车", "加入购物袋")
                && !containsAny(text, "福利专区", "湘村热销招牌", "湘村爆款双拼");
    }

    private static boolean looksLikeTakeoutStore(String text, String itemQuery) {
        if (text == null || text.trim().length() == 0) return false;
        if (containsAny(text, "去抢购", "未选必选品", "去结算", "下单必选")) return true;
        if (text.contains("外卖") && containsAny(text, "配送", "自提", "点评", "商家")) return true;
        return firstVisibleCandidate(text, itemCandidates(itemQuery)).length() > 0;
    }

    private static boolean tryTapAny(ScreenshotService svc, String text, String... queries) {
        for (String q : queries) {
            if (q == null || q.length() == 0 || (text != null && !text.contains(q))) continue;
            if (tryTap(svc, q, "contains")) return true;
        }
        return false;
    }

    private static boolean tryTap(ScreenshotService svc, String query, String match) {
        try { return svc.tapText(query, match, 1).optBoolean("ok", false); }
        catch (Exception e) { return false; }
    }

    private static boolean containsAny(String text, String... values) {
        if (text == null) return false;
        for (String v : values) if (v != null && v.length() > 0 && text.contains(v)) return true;
        return false;
    }

    private static String compact(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 520 ? s.substring(0, 520) : s;
    }

    private static void setStage(Context ctx, JSONObject s, String stage, String detail) {
        try {
            s.put("stage", stage);
            s.put("detail", detail);
            touch(s);
            TakeoutState.saveCheckoutSession(ctx, s);
            DebugState.append(ctx, "外卖自动点单 [" + stage + "]：" + detail);
        } catch (Exception ignored) {}
    }

    private static void ready(Context ctx, JSONObject s, String detail) {
        try {
            s.put("status", "ready_for_payment");
            s.put("stage", "payment_page");
            s.put("detail", detail);
            s.put("finished_at_ms", System.currentTimeMillis());
            s.put("finished_at_local", TakeoutState.formatLocal(System.currentTimeMillis()));
            touch(s);
            TakeoutState.saveCheckoutSession(ctx, s);
            DebugState.append(ctx, "外卖自动点单完成：已到付款页，等待用户本人支付");
        } catch (Exception ignored) {}
        synchronized (TakeoutCheckoutAutomation.class) { activeId = ""; cancelRunnableOnly(); }
    }

    private static void fail(Context ctx, JSONObject s, String code, String detail) {
        try {
            s.put("status", "failed");
            s.put("stage", "stopped");
            s.put("error", code);
            s.put("detail", detail);
            s.put("finished_at_ms", System.currentTimeMillis());
            s.put("finished_at_local", TakeoutState.formatLocal(System.currentTimeMillis()));
            touch(s);
            TakeoutState.saveCheckoutSession(ctx, s);
            DebugState.append(ctx, "外卖自动点单停止 [" + code + "]：" + detail);
        } catch (Exception ignored) {}
        synchronized (TakeoutCheckoutAutomation.class) { activeId = ""; cancelRunnableOnly(); }
    }

    private static void touch(JSONObject s) {
        try {
            long now = System.currentTimeMillis();
            s.put("updated_at_ms", now);
            s.put("updated_at_local", TakeoutState.formatLocal(now));
        } catch (Exception ignored) {}
    }
}
