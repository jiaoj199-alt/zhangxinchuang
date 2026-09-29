package dev.linjian.peek;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 通知使用权开启后，同步媒体通知给 MediaState。 */
public class MediaNotificationService extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        refreshMediaState("媒体通知监听已连接");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        try { WalletState.captureNotification(this, sbn); } catch (Exception ignored) { }
        refreshMediaState(null);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        refreshMediaState(null);
    }

    private void refreshMediaState(String log) {
        try {
            MediaState.updateFromActiveNotifications(this, getActiveNotifications());
            if (log != null) DebugState.append(this, log);
        } catch (Exception e) {
            DebugState.append(this, "媒体状态刷新失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
