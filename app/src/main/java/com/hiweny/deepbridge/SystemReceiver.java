package com.hiweny.deepbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 系统事件接收器：开机自启 + AlarmManager 心跳续期/拉起服务。 */
public class SystemReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Util.log("SystemReceiver: " + action);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean heartbeat = KeepAlive.ACTION_HEARTBEAT.equals(action);
        if (boot || heartbeat) {
            // 只有已绑定过微信令牌才自动拉起
            String token = context.getSharedPreferences("deepbridge", Context.MODE_PRIVATE)
                    .getString("ilink_token", null);
            if (token != null && !token.isEmpty()) {
                BotService.start(context);
            }
            KeepAlive.scheduleHeartbeat(context);
        }
    }
}
