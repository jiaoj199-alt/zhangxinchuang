package dev.linjian.peek;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 公开版媒体状态：仅在用户开启通知使用权后，从本机媒体通知/MediaSession 读取正在播放的标题、歌手与状态。
 * 不读取聊天正文，不上传通知全文；只用于「此刻状态」卡片和 life_state 的 media_state。
 */
public class MediaState {
    private static volatile Snapshot latest = Snapshot.empty("等待媒体通知");

    public static JSONObject collect(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            boolean granted = hasNotificationListenerAccess(ctx);
            Snapshot live = null;
            if (granted) {
                live = queryActiveSessions(ctx);
                if (live != null && live.hasMedia()) latest = live;
            }
            Snapshot s = (live != null && live.hasMedia()) ? live : (latest == null ? Snapshot.empty("等待媒体通知") : latest);
            out.put("available", granted && s.hasMedia());
            out.put("permission_granted", granted);
            out.put("source", "media_session_or_notification_listener");
            out.put("package", s.pkg);
            out.put("app", s.app);
            out.put("title", s.title);
            out.put("artist", s.artist);
            out.put("album", s.album);
            out.put("state", s.state);
            out.put("state_label", stateLabel(s.state));
            out.put("is_playing", "playing".equals(s.state));
            out.put("updated_at_ms", s.updatedAtMs);
            out.put("updated_at_local", s.updatedAtMs <= 0 ? "" : formatLocal(s.updatedAtMs));
            if (!granted) out.put("reason", "通知使用权未开启");
            else if (!s.hasMedia()) out.put("reason", s.reason == null || s.reason.length() == 0 ? "暂无媒体播放" : s.reason);
            out.put("summary", summary(ctx));
            out.put("privacy_note", "媒体状态仅在你开启通知使用权后读取，用于显示正在播放的歌曲/音频；不读取聊天内容。");
        } catch (Exception e) {
            try { out.put("available", false); out.put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    public static String pretty(Context ctx) {
        if (!hasNotificationListenerAccess(ctx)) return "媒体：未开启通知使用权";
        Snapshot s = currentSnapshot(ctx);
        if (!s.hasMedia()) return "媒体：暂无播放";
        return "媒体：" + displayLine(s);
    }

    public static String summary(Context ctx) {
        if (!hasNotificationListenerAccess(ctx)) return "媒体状态未授权";
        Snapshot s = currentSnapshot(ctx);
        if (!s.hasMedia()) return "暂无媒体播放";
        return displayLine(s);
    }

    private static Snapshot currentSnapshot(Context ctx) {
        Snapshot live = queryActiveSessions(ctx);
        if (live != null && live.hasMedia()) latest = live;
        return (live != null && live.hasMedia()) ? live : (latest == null ? Snapshot.empty("等待媒体通知") : latest);
    }

    public static boolean hasNotificationListenerAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || enabled.trim().length() == 0) return false;
            String pkg = ctx.getPackageName();
            ComponentName self = new ComponentName(ctx, MediaNotificationService.class);
            String expectedLong = self.flattenToString();
            String expectedShort = pkg + "/." + MediaNotificationService.class.getSimpleName();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                String item = splitter.next();
                if (matchesListener(item, pkg, expectedLong, expectedShort)) return true;
            }
            return matchesListener(enabled, pkg, expectedLong, expectedShort);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matchesListener(String raw, String pkg, String expectedLong, String expectedShort) {
        if (raw == null) return false;
        String item = raw.trim();
        if (item.length() == 0) return false;
        if (expectedLong.equalsIgnoreCase(item) || expectedShort.equalsIgnoreCase(item)) return true;
        try {
            ComponentName cn = ComponentName.unflattenFromString(item);
            if (cn != null && pkg.equals(cn.getPackageName())) {
                String cls = cn.getClassName() == null ? "" : cn.getClassName();
                return MediaNotificationService.class.getName().equals(cls)
                        || cls.endsWith("." + MediaNotificationService.class.getSimpleName())
                        || MediaNotificationService.class.getSimpleName().equals(cls);
            }
        } catch (Exception ignored) { }
        String lower = item.toLowerCase(Locale.ROOT).replace(" ", "");
        String lowerPkg = pkg.toLowerCase(Locale.ROOT);
        String full = MediaNotificationService.class.getName().toLowerCase(Locale.ROOT);
        String simple = MediaNotificationService.class.getSimpleName().toLowerCase(Locale.ROOT);
        return lower.contains(lowerPkg + "/") && (lower.contains(full) || lower.contains("/.") && lower.contains(simple) || lower.endsWith("/" + simple));
    }

    static void updateFromActiveNotifications(Context ctx, StatusBarNotification[] notifications) {
        try {
            Snapshot best = null;
            if (notifications != null) {
                for (StatusBarNotification sbn : notifications) {
                    Snapshot s = fromNotification(ctx, sbn);
                    if (s == null || !s.hasMedia()) continue;
                    if (best == null || score(s) > score(best)) best = s;
                }
            }
            if (best == null) best = queryActiveSessions(ctx);
            latest = best == null ? Snapshot.empty("暂无媒体播放") : best;
        } catch (Exception e) {
            latest = Snapshot.empty("媒体读取失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private static Snapshot fromNotification(Context ctx, StatusBarNotification sbn) {
        if (ctx == null || sbn == null || sbn.getNotification() == null) return null;
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras == null) extras = new Bundle();
        String category = n.category == null ? "" : n.category;
        MediaSession.Token token = null;
        try {
            Object raw = extras.getParcelable("android.mediaSession");
            if (raw instanceof MediaSession.Token) token = (MediaSession.Token) raw;
        } catch (Exception ignored) { }
        boolean mediaLike = Notification.CATEGORY_TRANSPORT.equals(category) || token != null;
        if (!mediaLike) return null;

        String title = safeText(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = safeText(extras.getCharSequence(Notification.EXTRA_TEXT));
        String subText = safeText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String artist = text;
        String album = subText;
        String state = "unknown";
        if (token != null) {
            try {
                MediaController controller = new MediaController(ctx, token);
                MediaMetadata md = controller.getMetadata();
                if (md != null) {
                    String metaTitle = firstNonEmpty(
                            metaText(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                            metaText(md, MediaMetadata.METADATA_KEY_TITLE));
                    String metaArtist = firstNonEmpty(
                            metaText(md, MediaMetadata.METADATA_KEY_ARTIST),
                            metaText(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                            metaText(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    String metaAlbum = metaText(md, MediaMetadata.METADATA_KEY_ALBUM);
                    if (!empty(metaTitle)) title = metaTitle;
                    if (!empty(metaArtist)) artist = metaArtist;
                    if (!empty(metaAlbum)) album = metaAlbum;
                    try {
                        MediaDescription desc = md.getDescription();
                        if (desc != null) {
                            if (empty(title)) title = safeText(desc.getTitle());
                            if (empty(artist)) artist = safeText(desc.getSubtitle());
                            if (empty(album)) album = safeText(desc.getDescription());
                        }
                    } catch (Exception ignored) { }
                }
                PlaybackState ps = controller.getPlaybackState();
                if (ps != null) state = playbackState(ps.getState());
            } catch (Exception ignored) { }
        }
        if (empty(title) && empty(artist)) return null;
        Snapshot s = new Snapshot();
        s.pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        s.app = LifeState.appLabelPublic(ctx, s.pkg);
        s.title = title;
        s.artist = artist;
        s.album = album;
        s.state = state;
        s.updatedAtMs = Math.max(System.currentTimeMillis(), sbn.getPostTime());
        s.reason = "";
        return s;
    }

    /**
     * 主动读取系统 MediaSession。
     * 某些播放器通知不会稳定带 CATEGORY_TRANSPORT / android.mediaSession，或者授权时通知已经存在，
     * 只等 NotificationListener 回调会漏掉正在播放的内容。这里在每次 collect 时主动拉一次活跃会话。
     */
    private static Snapshot queryActiveSessions(Context ctx) {
        if (ctx == null) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            ComponentName listener = new ComponentName(ctx, MediaNotificationService.class);
            List<MediaController> controllers = msm.getActiveSessions(listener);
            Snapshot best = null;
            if (controllers != null) {
                for (MediaController controller : controllers) {
                    Snapshot s = fromController(ctx, controller);
                    if (s == null || !s.hasMedia()) continue;
                    if (best == null || score(s) > score(best)) best = s;
                }
            }
            return best;
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Snapshot fromController(Context ctx, MediaController controller) {
        if (ctx == null || controller == null) return null;
        Snapshot s = new Snapshot();
        try {
            s.pkg = controller.getPackageName() == null ? "" : controller.getPackageName();
            s.app = LifeState.appLabelPublic(ctx, s.pkg);
            MediaMetadata md = controller.getMetadata();
            if (md != null) {
                s.title = firstNonEmpty(
                        metaText(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                        metaText(md, MediaMetadata.METADATA_KEY_TITLE));
                s.artist = firstNonEmpty(
                        metaText(md, MediaMetadata.METADATA_KEY_ARTIST),
                        metaText(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                        metaText(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                s.album = metaText(md, MediaMetadata.METADATA_KEY_ALBUM);
                try {
                    MediaDescription desc = md.getDescription();
                    if (desc != null) {
                        if (empty(s.title)) s.title = safeText(desc.getTitle());
                        if (empty(s.artist)) s.artist = safeText(desc.getSubtitle());
                        if (empty(s.album)) s.album = safeText(desc.getDescription());
                    }
                } catch (Exception ignored) { }
            }
            PlaybackState ps = controller.getPlaybackState();
            if (ps != null) s.state = playbackState(ps.getState());
            else s.state = "unknown";
            s.updatedAtMs = System.currentTimeMillis();
            s.reason = "";
            if (empty(s.title) && empty(s.artist) && empty(s.album)) return null;
            return s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int score(Snapshot s) {
        int score = 0;
        if ("playing".equals(s.state)) score += 100;
        else if ("buffering".equals(s.state) || "connecting".equals(s.state)) score += 80;
        else if ("paused".equals(s.state)) score += 60;
        else if ("stopped".equals(s.state)) score -= 20;
        if (!empty(s.title)) score += 20;
        if (!empty(s.artist)) score += 10;
        if (!empty(s.album)) score += 3;
        if (!empty(s.pkg)) score += 1;
        return score;
    }

    private static String playbackState(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_CONNECTING: return "connecting";
            case PlaybackState.STATE_FAST_FORWARDING: return "fast_forwarding";
            case PlaybackState.STATE_REWINDING: return "rewinding";
            case PlaybackState.STATE_SKIPPING_TO_NEXT: return "skipping_next";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS: return "skipping_previous";
            default: return "unknown";
        }
    }

    private static String stateLabel(String state) {
        if ("playing".equals(state)) return "播放中";
        if ("paused".equals(state)) return "已暂停";
        if ("stopped".equals(state)) return "已停止";
        if ("buffering".equals(state)) return "缓冲中";
        if ("connecting".equals(state)) return "连接中";
        if ("fast_forwarding".equals(state)) return "快进中";
        if ("rewinding".equals(state)) return "倒退中";
        if ("skipping_next".equals(state)) return "切歌中";
        if ("skipping_previous".equals(state)) return "切歌中";
        return "状态未知";
    }

    private static String displayLine(Snapshot s) {
        String title = empty(s.title) ? "未知音频" : s.title;
        String app = empty(s.app) ? "媒体应用" : s.app;
        StringBuilder sb = new StringBuilder();
        sb.append("正在听《").append(title).append("》");
        if (!empty(s.artist)) sb.append(" - ").append(s.artist);
        sb.append(" · ").append(stateLabel(s.state));
        if (!empty(app)) sb.append(" · ").append(app);
        return sb.toString();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (!empty(v)) return v.trim();
        }
        return "";
    }

    private static String metaText(MediaMetadata md, String key) {
        if (md == null || key == null) return "";
        try { return safeText(md.getText(key)); } catch (Exception ignored) { return ""; }
    }

    private static String safeText(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
    private static boolean empty(String s) { return s == null || s.trim().length() == 0; }
    private static String formatLocal(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms)); }

    private static class Snapshot {
        String pkg = "";
        String app = "";
        String title = "";
        String artist = "";
        String album = "";
        String state = "unknown";
        long updatedAtMs = 0L;
        String reason = "";
        boolean hasMedia() { return !MediaState.empty(title) || !MediaState.empty(artist) || !MediaState.empty(album); }
        static Snapshot empty(String reason) { Snapshot s = new Snapshot(); s.reason = reason == null ? "" : reason; return s; }
    }
}
