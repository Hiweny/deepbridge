package com.hiweny.deepbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍保活服务：由系统绑定（仅系统可绑定，普通应用无法解绑），进程优先级高、很难被系统回收。
 * 监听窗口变化并周期性自检：只要微信令牌仍在，一旦发现桥接前台服务停止（或轮询长时间无响应），
 * 立即重新拉起 {@link BotService} 并续期心跳，从而在后台长时间放置后也能继续收发消息。
 */
public class KeepAliveAccessibility extends AccessibilityService {
    private long lastCheck = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.notificationTimeout = 300;
        setServiceInfo(info);
        ensureService();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 10000L) return; // 10 秒节流，避免频繁检测
        lastCheck = now;
        ensureService();
    }

    private void ensureService() {
        try {
            SharedPreferences sp = getSharedPreferences("deepbridge", MODE_PRIVATE);
            String token = sp.getString("ilink_token", null);
            if (token == null || token.isEmpty()) return; // 未绑定微信，不自动拉起
            boolean needStart = !BotService.serviceRunning;
            // 服务进程在，但轮询已超过 2 分钟无响应，也重启
            if (!needStart && !BotService.pollAlive && BotService.lastPollOk > 0
                    && System.currentTimeMillis() - BotService.lastPollOk > 120000L) {
                needStart = true;
            }
            if (needStart) {
                Util.log("无障碍保活：检测到桥接停止/卡死，重新拉起");
                BotService.start(this);
            }
            KeepAlive.scheduleHeartbeat(this);
        } catch (Exception e) {
            Util.log("无障碍保活异常: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {}
}
