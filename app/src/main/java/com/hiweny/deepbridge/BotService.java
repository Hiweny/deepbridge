package com.hiweny.deepbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** 前台服务：长轮询微信消息 -> 构造 Prompt（可带附件）-> 驱动 DeepSeek 网页 -> 回发微信。 */
public class BotService extends Service {
    public static final String ACTION_START = "com.hiweny.deepbridge.START";
    public static final String ACTION_STOP = "com.hiweny.deepbridge.STOP";
    private static final String CHANNEL_ID = "deepbridge_service";
    private static final int NOTIF_ID = 1001;

    public static volatile String lastError = "";
    public static volatile long lastMsgRecv = 0;
    public static volatile long lastPollOk = 0;
    public static volatile long lastReplyOk = 0;
    public static volatile int msgReceived = 0;
    public static volatile int msgReplied = 0;
    public static volatile boolean pollAlive = false;
    /** 前台服务进程是否存活（供无障碍保活检测并拉起）。 */
    public static volatile boolean serviceRunning = false;
    /** AI 当前阶段，用于常驻通知展示：等待消息 / 处理中 / DeepSeek 思考中 / 已回复。 */
    public static volatile String aiStatus = "等待消息";
    private static volatile StatusUi statusUi;

    private String base;
    private String token;
    private String cursor;
    private Thread pollThread;
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ExecutorService> userExecutors = new HashMap<>();
    private final ExecutorService maintenanceExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-maintenance");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, long[]> typingTickets = new HashMap<>();
    private final Map<String, String> typingTicketVal = new HashMap<>();
    private volatile String connectionState = "未连接";

    interface StatusUi { void onStatus(String s); }

    private String usageText() {
        return "🦞 DeepSeek 微信桥 使用帮助\n————————————\n" +
                "直接发消息即可与 DeepSeek 对话（角色人设 + 长期记忆 + 可选多条消息）。\n" +
                "也可以直接发送图片或文件（PDF/Word/Excel/TXT/代码等），会自动上传给 DeepSeek 一起分析。\n" +
                "指令：\n" +
                "/帮助 - 本帮助\n/状态 - 查看桥接与记忆状态\n/压缩 - 立即压缩对话记忆\n" +
                "/新对话 - 在 DeepSeek 官网手动开新对话（记忆保留）\n" +
                "/重置 - 清空本用户的对话与记忆\n/人设 - 查看/修改角色人设\n" +
                "/多条开|关 - 多条消息模式（AI 用 \\ 分隔，依次发送）\n" +
                "/文件开|关 - 微信图片/文件转发给 DeepSeek 的总开关\n" +
                "————————————\n提示：请保持手机上 App 运行（已申请电池白名单）。";
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static void setStatusUi(StatusUi ui) { statusUi = ui; }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BotService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, BotService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        createChannel();
        Notification n = buildNotification("微信桥启动中…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, 1);
        else startForeground(NOTIF_ID, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepBridge::bot");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(/* 最长持有 24h，防止泄漏；心跳会续期 */ 24 * 60 * 60 * 1000L);
        KeepAlive.scheduleHeartbeat(this);
        startWatchdog();
    }

    /** 看门狗：轮询卡死/线程意外退出时自愈，避免后台久了不再收消息。 */
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final Runnable watchdogTask = new Runnable() {
        @Override public void run() {
            try {
                boolean threadDead = pollThread == null || !pollThread.isAlive();
                long since = lastPollOk == 0 ? 0 : System.currentTimeMillis() - lastPollOk;
                if (running.get() && (threadDead || (since > 150000L))) {
                    Util.log("看门狗触发自愈: threadDead=" + threadDead + " sincePoll=" + since + "ms");
                    if (pollThread != null) pollThread.interrupt();
                    pollThread = new Thread(BotService.this::pollLoop, "ilink-poll");
                    pollThread.start();
                }
                if (wakeLock != null && !wakeLock.isHeld() && running.get()) {
                    wakeLock.acquire(24 * 60 * 60 * 1000L);
                }
                refreshNotification(); // 周期性刷新常驻通知，保持 AI/微信状态与时间实时
            } catch (Exception e) {
                Util.log("看门狗异常: " + e.getMessage());
            }
            watchdog.postDelayed(this, 60000L);
        }
    };

    private void startWatchdog() {
        watchdog.removeCallbacks(watchdogTask);
        watchdog.postDelayed(watchdogTask, 60000L);
    }

    /** 一段时间无动作后，通知状态回到“等待消息”。 */
    private final Runnable resetIdle = new Runnable() {
        @Override public void run() {
            if (!DeepSeekController.get().isBusy()) { aiStatus = "等待消息"; refreshNotification(); }
        }
    };

    /** 更新 AI 阶段并立即刷新常驻通知；15s 后若空闲则回到等待。 */
    private void setAiStatus(final String s) {
        aiStatus = s;
        refreshNotification();
        watchdog.removeCallbacks(resetIdle);
        if (!"等待消息".equals(s)) watchdog.postDelayed(resetIdle, 15000L);
    }

    /**用当前连接/AI/收发状态重建并刷新常驻通知。 */
    void refreshNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIF_ID, buildNotification(connectionState));
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉：安排 1 秒后重启，配合 stopWithTask=false 尽量不断桥
        KeepAlive.scheduleServiceRestart(this, 1000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_STOP.equals(intent == null ? ACTION_START : intent.getAction())) {
            stopSelf();
            return START_REDELIVER_INTENT;
        }
        SharedPreferences sp = getSharedPreferences("deepbridge", MODE_PRIVATE);
        token = sp.getString("ilink_token", null);
        base = sp.getString("ilink_baseurl", ILinkClient.DEFAULT_BASE);
        cursor = sp.getString("ilink_cursor", "");
        if (token == null || token.isEmpty()) {
            updateState("未连接（请扫码）");
            Util.log("无 bot_token，请先扫码连接微信");
            KeepAlive.cancelHeartbeat(this);
            stopSelf();
            return START_REDELIVER_INTENT;
        }
        if (running.compareAndSet(false, true)) {
            pollThread = new Thread(this::pollLoop, "ilink-poll");
            pollThread.start();
            Util.log("iLink 轮询已启动");
        }
        KeepAlive.scheduleHeartbeat(this); // 每次被拉起都续期心跳
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        boolean intentionalStop = getSharedPreferences("deepbridge", MODE_PRIVATE)
                .getString("ilink_token", null) == null;
        running.set(false);
        pollAlive = false;
        serviceRunning = false;
        aiStatus = "已停止";
        watchdog.removeCallbacks(watchdogTask);
        if (pollThread != null) pollThread.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        maintenanceExec.shutdownNow();
        synchronized (userExecutors) {
            for (ExecutorService e : userExecutors.values()) e.shutdownNow();
            userExecutors.clear();
        }
        // 只有用户主动断开（令牌被清）才彻底停；否则（系统回收）安排重启
        if (intentionalStop) KeepAlive.cancelHeartbeat(this);
        else KeepAlive.scheduleServiceRestart(this, 3000L);
        super.onDestroy();
    }

    private void updateState(final String s) {
        connectionState = s;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification(s));
        } catch (Exception ignored) {}
        final StatusUi ui = statusUi;
        if (ui != null) {
            new Handler(Looper.getMainLooper()).post(() -> ui.onStatus(s));
        }
    }

    private void pollLoop() {
        updateState("已连接");
        pollAlive = true;
        int fails = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JSONObject updates = ILinkClient.getUpdates(base, token, cursor);
                if (updates.has("__error")) {
                    fails++;
                    long backoff = Math.min(30000L, fails * 3000L);
                    Util.log("getUpdates 异常: " + updates.optString("__error") + "，" + backoff + "ms 后重试");
                    Thread.sleep(backoff);
                    continue;
                }
                fails = 0;
                lastPollOk = System.currentTimeMillis();
                int code = updates.optInt("errcode", updates.optInt("ret", 0));
                if (code == -14) {
                    pollAlive = false;
                    lastError = "bot_token 已过期（-14），需重新扫码";
                    updateState("会话过期，需重新扫码");
                    notifyFail(lastError);
                    Util.log("errcode -14：bot_token 已过期，请重新扫码");
                    return;
                }
                if (code != 0) {
                    lastError = "getUpdates 错误码 " + code;
                    Util.log(lastError);
                }
                String buf = updates.optString("get_updates_buf", "");
                if (!buf.isEmpty() && !buf.equals(cursor)) {
                    cursor = buf;
                    getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putString("ilink_cursor", cursor).apply();
                }
                JSONArray msgs = updates.optJSONArray("msgs");
                if (msgs != null) {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m != null && m.optInt("message_type", 1) == 1) dispatch(m);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                fails = 0;
                Util.log("轮询异常: " + e.getMessage());
                try { Thread.sleep(5000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void dispatch(final JSONObject msg) {
        final String uid = msg.optString("from_user_id", "");
        if (uid.isEmpty()) return;
        ExecutorService exec;
        synchronized (userExecutors) {
            exec = userExecutors.get(uid);
            if (exec == null) {
                exec = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "user-" + Util.md5(uid).substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });
                userExecutors.put(uid, exec);
            }
        }
        exec.submit(() -> {
            try { handleUserMessage(msg); }
            catch (Exception e) { Util.log("处理消息异常(" + uid + "): " + e.getMessage()); }
        });
    }

    /** 去掉 extractText 产生的占位符，得到用户真正手打的文字。 */
    private static String realUserText(String s) {
        if (s == null) return "";
        return s.replace("[图片]", " ").replace("[文件]", " ").replace("[视频]", " ").replace("[语音]", " ").trim();
    }

    private void handleUserMessage(JSONObject msg) {
        final String uid = msg.optString("from_user_id");
        final String contextToken = msg.optString("context_token", "");
        final String extractText = ILinkClient.extractText(msg);
        lastMsgRecv = System.currentTimeMillis();
        msgReceived++;
        if (contextToken.isEmpty()) {
            lastError = "入站消息缺少 context_token，无法回复";
            Util.log(lastError);
            return;
        }
        if (extractText.isEmpty()) {
            reply(uid, contextToken, "（暂只支持文本、语音、图片与文件消息哦）");
            return;
        }
        String preview = extractText.length() > 50 ? extractText.substring(0, 50) + "…" : extractText;
        Util.log("收到微信消息(" + uid + "): " + preview);

        final ConversationEngine engine = ConversationEngine.get(this);
        final ConversationEngine.Conv conv = engine.get(uid);
        final String cmd = extractText.trim().toLowerCase();

        if (cmd.equals("/帮助") || cmd.equals("/help") || cmd.equals("帮助")) {
            reply(uid, contextToken, usageText());
            return;
        }
        if (cmd.equals("/重置") || cmd.equals("/reset") || cmd.equals("重置")) {
            engine.reset(conv);
            reply(uid, contextToken, "✅ 已重置：对话、记忆摘要与 DeepSeek 会话全部清空。");
            return;
        }
        if (cmd.equals("/状态") || cmd.equals("/status") || cmd.equals("状态")) {
            StringBuilder sb = new StringBuilder("📊 桥接状态\n连接: ");
            sb.append(connectionState)
              .append("\nDeepSeek页面: ").append(DeepSeekController.get().isAttached() ? "运行中" : "未运行（App被关闭）")
              .append("\n消息统计: 收到 ").append(msgReceived).append(" / 回复 ").append(msgReplied).append("\n")
              .append(engine.statusText(conv));
            reply(uid, contextToken, sb.toString());
            return;
        }
        if (cmd.equals("/压缩") || cmd.equals("/compress") || cmd.equals("压缩")) {
            if ((conv.summary == null || conv.summary.isEmpty()) && conv.history.length() == 0) {
                reply(uid, contextToken, "当前没有需要压缩的历史。");
                return;
            }
            sendTyping(uid, contextToken, true);
            String r = compressConv(this, conv);
            sendTyping(uid, contextToken, false);
            reply(uid, contextToken, r);
            return;
        }
        if (cmd.equals("/新对话") || cmd.equals("/newchat") || cmd.equals("/新建对话")) {
            String out;
            if (DeepSeekController.get().isBusy()) {
                out = "AI 正在回复中，请稍等再开新对话。";
            } else if (DeepSeekController.get().newChat().optBoolean("ok")) {
                engine.markWebRotated(conv);
                out = "✅ 已在 DeepSeek 官网开新对话；人设与长期记忆保留，可继续发送消息。";
            } else {
                out = "⚠️ 新建对话失败，请打开 App 切到对话页手动点 New chat。";
            }
            reply(uid, contextToken, out);
            return;
        }
        if (cmd.startsWith("/人设") || cmd.startsWith("人设 ")) {
            String persona = extractText.trim().replaceFirst("^/(人设|persona)\\s*", "").replaceFirst("^人设\\s*", "");
            if (persona.isEmpty()) {
                reply(uid, contextToken, "当前人设：\n" + engine.persona() + "\n\n用「/人设 新的人设描述」修改。");
                return;
            }
            getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putString("persona", persona).apply();
            reply(uid, contextToken, "✅ 人设已更新。");
            return;
        }
        if (cmd.equals("/多条开") || cmd.equals("/多条on")) {
            getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putBoolean("multi_msg", true).apply();
            reply(uid, contextToken, "✅ 多条消息模式已开启：AI 会用 \\ 分隔多条回复，将依次发送。");
            return;
        }
        if (cmd.equals("/多条关") || cmd.equals("/多条off")) {
            getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putBoolean("multi_msg", false).apply();
            reply(uid, contextToken, "✅ 多条消息模式已关闭：回复将整条发送。");
            return;
        }
        if (cmd.equals("/文件开") || cmd.equals("/文件on")) {
            getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putBoolean("file_transfer", true).apply();
            reply(uid, contextToken, "✅ 文件传输已开启：你发的图片/文件会自动上传给 DeepSeek。");
            return;
        }
        if (cmd.equals("/文件关") || cmd.equals("/文件off")) {
            getSharedPreferences("deepbridge", MODE_PRIVATE).edit().putBoolean("file_transfer", false).apply();
            reply(uid, contextToken, "✅ 文件传输已关闭：图片/文件将仅作为占位提示，不转发给 DeepSeek。");
            return;
        }

        // ---------- 正常对话（可能带附件） ----------
        sendTyping(uid, contextToken, true);
        setAiStatus("收到消息，处理中…");
        try {
            final String typed = realUserText(extractText);

            // 1) 收集并下载/解密/治理附件
            List<MediaFile> files = new ArrayList<>();
            List<WeChatMedia.Ref> refs = engine.fileTransfer() ? WeChatMedia.extractRefs(msg) : new ArrayList<>();
            List<String> dlErrors = new ArrayList<>();
            for (WeChatMedia.Ref ref : refs) {
                try {
                    byte[] plain = WeChatMedia.downloadAndDecrypt(ref);
                    MediaFile mf = MediaPrepare.prepare(
                            new MediaFile(ref.fileName, WeChatMedia.guessMime(ref.fileName, ref.type), plain));
                    files.add(mf);
                    Util.log("附件就绪: " + mf.name + " " + mf.size() + "B " + mf.mime);
                } catch (Exception e) {
                    dlErrors.add(ref.fileName + "(" + e.getMessage() + ")");
                    Util.log("附件下载失败 " + ref.fileName + ": " + e.getMessage());
                }
            }
            if (!dlErrors.isEmpty()) {
                reply(uid, contextToken, "⚠️ 有 " + dlErrors.size() + " 个附件接收失败：" + String.join("；", dlErrors));
            }

            // 2) 纯视频占位（DeepSeek 暂不支持视频）
            if (files.isEmpty() && typed.isEmpty()) {
                boolean isVideo = extractText.contains("[视频]");
                sendTyping(uid, contextToken, false);
                reply(uid, contextToken, isVideo
                        ? "（暂不支持视频消息哦，可以发图片、文件或文字）"
                        : "（没有可发送的文本或附件）");
                return;
            }

            // 3) 组装当前消息文案：优先用户手打文字；纯附件则生成说明
            String currentMsg = typed;
            if (currentMsg.isEmpty() && !files.isEmpty()) {
                StringBuilder note = new StringBuilder("（用户通过微信发来");
                for (int i = 0; i < files.size(); i++) {
                    MediaFile f = files.get(i);
                    if (i > 0) note.append("、");
                    note.append(f.mime.startsWith("image/") ? "一张图片" : "文件「" + f.name + "」");
                }
                note.append("，请结合其内容直接回复）");
                currentMsg = note.toString();
            }
            String prompt = engine.buildPrompt(conv, currentMsg);

            // 4) 发送（有附件走 attachFile+send，无附件走原路径）
            setAiStatus("DeepSeek 思考生成中…");
            JSONObject result = files.isEmpty()
                    ? DeepSeekController.get().sendPrompt(prompt)
                    : DeepSeekController.get().sendWithFiles(prompt, files, 200);

            if (result.optBoolean("ok") && !result.optString("content").isEmpty()) {
                String answer = result.optString("content");
                boolean recalled = result.optBoolean("recalled");
                // 历史里用带占位符的原始描述，保持记忆可读
                engine.appendRound(conv, extractText.isEmpty() ? currentMsg : extractText, answer);
                engine.save(conv);
                String out = Util.wechatify(answer);
                if (recalled) out = "⚠️ 该回复已被 DeepSeek 官方撤回，以下为拦截恢复的内容：\n————————\n" + out;
                reply(uid, contextToken, out);
                setAiStatus("已回复微信 ✓");
                maintenanceExec.submit(() -> postReplyMaintenance(engine, conv, uid));
                return;
            }
            sendTyping(uid, contextToken, false);
            String err = result.optString("error", "EMPTY");
            Util.log("DeepSeek 失败: " + err);
            reply(uid, contextToken, "⚠️ DeepSeek 回复失败（" + err + "）。请打开 App 检查页面状态后重试。");
        } finally {
            sendTyping(uid, contextToken, false);
        }
    }

    private void postReplyMaintenance(ConversationEngine engine, ConversationEngine.Conv conv, String uid) {
        try {
            // 1) 滑窗记忆：每攒满 N 轮新对话，就把这一批折叠并与旧总结合并；原始历史不裁剪、窗口不重置、不开新对话。
            int guard = 0;
            while (engine.countToCompress(conv, false) > 0 && guard++ < 30) {
                int count = engine.countToCompress(conv, false);
                conv.lastCompressAttempt = System.currentTimeMillis();
                int from = conv.summarizedRounds;
                JSONObject r = DeepSeekController.get()
                        .sendPrompt(engine.buildBatchCompressPrompt(conv, count), 120);
                if (r.optBoolean("ok") && !r.optString("content").isEmpty()) {
                    engine.applyBatchSummary(conv, r.optString("content").trim(), count);
                    Util.log("自动折叠(" + uid + ") 第" + (from + 1) + "-" + (from + count) + "轮");
                } else {
                    Util.log("自动折叠失败(" + uid + "): " + r.optString("error", "EMPTY"));
                    break;
                }
            }
            // 2) 网站会话轮换：仅当距上次轮换累计满 R 轮（默认 200，0=从不自动）才 New chat，
            //    最大限度利用 DeepSeek 官网的上下文窗口，绝不因压缩而频繁开新对话；本地记忆/计数保留。
            int R = engine.rotateRounds();
            if (R > 0 && !DeepSeekController.get().isBusy()
                    && conv.totalRounds - conv.rotatedAtRounds >= R
                    && DeepSeekController.get().newChat().optBoolean("ok")) {
                engine.markWebRotated(conv);
                Util.log("网站会话已轮换（总" + conv.totalRounds + "轮，本地记忆保留）");
            }
        } catch (Exception e) {
            Util.log("后台维护异常: " + e.getMessage());
        }
    }

    /**
     * 手动压缩：折叠全部待处理批次（允许不足 N 轮的零头），逐条返回结果。
     * 供微信「/压缩」与控制台调用。
     */
    public static String compressConv(Context context, ConversationEngine.Conv conv) {
        ConversationEngine engine = ConversationEngine.get(context);
        StringBuilder out = new StringBuilder();
        boolean allowPartial = true;
        int guard = 0;
        while (guard++ < 30) {
            int count = engine.countToCompress(conv, allowPartial);
            if (count <= 0) break;
            conv.lastCompressAttempt = System.currentTimeMillis();
            int from = conv.summarizedRounds;
            try {
                JSONObject r = DeepSeekController.get()
                        .sendPrompt(engine.buildBatchCompressPrompt(conv, count), 200);
                if (r.optBoolean("ok") && !r.optString("content").isEmpty()) {
                    engine.applyBatchSummary(conv, r.optString("content").trim(), count);
                    out.append("✅ 已折叠第").append(from + 1).append('-').append(from + count)
                       .append(" 轮，摘要 ").append(conv.summary.length()).append(" 字。\n");
                } else {
                    out.append("⚠️ 压缩失败：").append(r.optString("error", "EMPTY"));
                    break;
                }
            } catch (Exception e) {
                out.append("⚠️ 压缩异常: ").append(e.getMessage());
                break;
            }
        }
        String s = out.toString().trim();
        return s.isEmpty() ? "当前没有需要压缩的历史。" : s;
    }

    private void reply(String uid, String contextToken, String text) {
        List<String> messages;
        if (ConversationEngine.get(this).multiMsg()) {
            messages = Util.splitMultiMsg(text);
            if (messages.isEmpty()) messages = Collections.singletonList(text);
        } else {
            messages = Collections.singletonList(text);
        }
        for (int i = 0; i < messages.size(); i++) {
            List<String> chunks = Util.chunkText(messages.get(i), 900);
            for (int j = 0; j < chunks.size(); j++) {
                if (!sendWechatText(uid, contextToken, chunks.get(j))) return;
                if (j < chunks.size() - 1) {
                    try { Thread.sleep(400L); } catch (InterruptedException e) { return; }
                }
            }
            if (i < messages.size() - 1) {
                try { Thread.sleep(new Random().nextInt(500) + 600); } catch (InterruptedException e) { return; }
            }
        }
        msgReplied++;
    }

    private boolean sendWechatText(String uid, String contextToken, String text) {
        JSONObject r = ILinkClient.sendText(base, token, uid, contextToken, text);
        if (r.has("__error")) {
            Util.log("微信发送网络错误: " + r.optString("__error") + "，1.5s 后重试");
            try {
                Thread.sleep(1500L);
                r = ILinkClient.sendText(base, token, uid, contextToken, text);
            } catch (InterruptedException e) { /* ignore */ }
            if (r.has("__error")) {
                lastError = "微信发送失败: " + r.optString("__error");
                Util.log(lastError);
                updateState("微信发送失败");
                notifyFail(lastError);
                return false;
            }
        }
        int code = r.optInt("errcode", r.optInt("ret", 0));
        if (code == -14) {
            lastError = "bot_token 已过期（-14），请在 App 重新扫码";
            Util.log(lastError);
            updateState("会话过期，需重新扫码");
            notifyFail(lastError);
            return false;
        }
        if (code != 0) {
            lastError = "微信发送错误码 " + code + "（响应: " + r.toString().substring(0, Math.min(120, r.toString().length())) + "）";
            Util.log(lastError);
            updateState("微信发送异常");
            return false;
        }
        lastReplyOk = System.currentTimeMillis();
        return true;
    }

    private void notifyFail(String s) {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2001, buildNotification("⚠️ " + s));
        } catch (Exception ignored) {}
    }

    private void sendTyping(String uid, String contextToken, boolean typing) {
        try {
            long now = System.currentTimeMillis();
            long[] win = typingTickets.get(uid);
            String ticket = typingTicketVal.get(uid);
            if (ticket == null || win == null || now > win[1]) {
                ticket = ILinkClient.getConfig(base, token, uid, contextToken).optString("typing_ticket", "");
                if (ticket.isEmpty()) return;
                typingTickets.put(uid, new long[]{now, now + 72000000L});
                typingTicketVal.put(uid, ticket);
            }
            ILinkClient.sendTyping(base, token, uid, ticket, typing ? 1 : 2);
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DeepSeek 微信桥", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持微信桥后台运行");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 0, i, flags);

        // 动作：立即重启桥接（无需打开界面）
        Intent rs = new Intent(this, BotService.class).setAction(ACTION_START);
        PendingIntent restartPi = PendingIntent.getService(this, 2, rs, flags);

        String connLine = pollAlive ? "微信轮询：在线" : "微信轮询：" + text;
        String big =
                "🤖 AI 状态：" + aiStatus + "\n" +
                "🔗 " + connLine + "\n" +
                "📩 已收到 " + msgReceived + " 条（最近 " + Util.timeHM(lastMsgRecv) + "）\n" +
                "💬 已回复 " + msgReplied + " 条（最近 " + Util.timeHM(lastReplyOk) + "）";
        b.setContentTitle("DeepSeek 微信桥")
         .setContentText("AI：" + aiStatus + "｜" + (pollAlive ? "在线" : text))
         .setStyle(new Notification.BigTextStyle().bigText(big))
         .setSmallIcon(android.R.drawable.stat_notify_chat)
         .setContentIntent(openPi)
         .addAction(android.R.drawable.ic_menu_rotate, "重启桥接", restartPi)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
