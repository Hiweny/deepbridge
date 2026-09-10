package com.hiweny.deepbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 每个微信用户的会话与长期记忆：人设、上下文窗口、记忆压缩、会话轮换，本地持久化。 */
public class ConversationEngine {
    private static volatile ConversationEngine sInstance;
    private final Context ctx;
    private final Map<String, Conv> cache = new HashMap<>();

    public static class Conv {
        public String userId;
        public String deepseekSessionId;
        public String parentMsgId;
        public String summary = "";
        public JSONArray history = new JSONArray();
        public int totalRounds = 0;
        public long lastCompressAttempt = 0;
    }

    private ConversationEngine(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public static ConversationEngine get(Context context) {
        if (sInstance == null) {
            synchronized (ConversationEngine.class) {
                if (sInstance == null) sInstance = new ConversationEngine(context);
            }
        }
        return sInstance;
    }

    private File fileFor(String userId) {
        File dir = new File(ctx.getFilesDir(), "conversations");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Util.md5(userId) + ".json");
    }

    public synchronized Conv get(String userId) {
        Conv cached = cache.get(userId);
        if (cached != null) return cached;
        Conv conv = new Conv();
        conv.userId = userId;
        String raw = Util.readFile(fileFor(userId));
        if (raw != null) {
            try {
                JSONObject j = new JSONObject(raw);
                conv.deepseekSessionId = j.optString("sessionId");
                if ("null".equals(conv.deepseekSessionId)) conv.deepseekSessionId = null;
                conv.parentMsgId = j.optString("parentMsgId");
                if ("null".equals(conv.parentMsgId)) conv.parentMsgId = null;
                conv.summary = j.optString("summary");
                conv.history = j.optJSONArray("history") != null ? j.optJSONArray("history") : new JSONArray();
                conv.totalRounds = j.optInt("totalRounds", 0);
            } catch (Exception e) {
                Util.log("会话文件解析失败 " + userId + ": " + e.getMessage());
            }
        }
        cache.put(userId, conv);
        return conv;
    }

    public synchronized void save(Conv conv) {
        try {
            JSONObject j = new JSONObject();
            j.put("userId", conv.userId);
            j.put("sessionId", conv.deepseekSessionId == null ? JSONObject.NULL : conv.deepseekSessionId);
            j.put("parentMsgId", conv.parentMsgId == null ? JSONObject.NULL : conv.parentMsgId);
            j.put("summary", conv.summary);
            j.put("history", conv.history);
            j.put("totalRounds", conv.totalRounds);
            Util.writeFile(fileFor(conv.userId), j.toString());
        } catch (Exception e) {
            Util.log("保存会话失败: " + e.getMessage());
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("deepbridge", Context.MODE_PRIVATE);
    }

    public String persona() {
        return prefs().getString(Prompts.KEY_PERSONA, Prompts.DEFAULT_PERSONA);
    }

    public String wechatRules() {
        return prefs().getString(Prompts.KEY_WECHAT_RULES, Prompts.DEFAULT_WECHAT_RULES);
    }

    public String multiRule() {
        return prefs().getString(Prompts.KEY_MULTI_RULE, Prompts.DEFAULT_MULTI_RULE);
    }

    public String summaryTpl() {
        return prefs().getString(Prompts.KEY_SUMMARY_TPL, Prompts.DEFAULT_SUMMARY_TPL);
    }

    /** 把所有可编辑 Prompt 段落恢复为内置默认。 */
    public void resetAllPrompts() {
        SharedPreferences.Editor e = prefs().edit();
        for (Prompts.Section s : Prompts.SECTIONS) e.remove(s.key);
        e.apply();
    }

    public int ctxRounds() { return prefs().getInt("ctx_rounds", 8); }
    public int compressRounds() { return prefs().getInt("compress_rounds", 12); }
    public int rotateRounds() { return prefs().getInt("rotate_rounds", 100); }
    public boolean thinkingEnabled() { return prefs().getBoolean("thinking", false); }
    public boolean timeInject() { return prefs().getBoolean("time_inject", true); }
    public boolean multiMsg() { return prefs().getBoolean("multi_msg", true); }
    /** 文件传输总开关（微信图片/文件 -> DeepSeek 附件）。 */
    public boolean fileTransfer() { return prefs().getBoolean("file_transfer", true); }
    /** 记忆摘要的硬上限字数。 */
    public int summaryMaxChar() { return prefs().getInt("summary_max", 3000); }

    public boolean compressCooldownOk(Conv conv) {
        return System.currentTimeMillis() - conv.lastCompressAttempt > 300000;
    }

    /** 组装发给 DeepSeek 的完整 Prompt：人设 + 微信规则 + 时间 + 长期记忆 + 近期窗口 + 当前消息 + 多条规则。 */
    public synchronized String buildPrompt(Conv conv, String currentMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统设定】\n").append(persona()).append("\n\n");
        // 微信渠道规则（原生 emoji / 彩蛋 / 口语化），始终注入
        String wr = wechatRules();
        if (wr != null && !wr.trim().isEmpty()) sb.append(wr.trim()).append("\n\n");
        if (timeInject()) {
            sb.append("【当前时间】\n").append(Util.nowText()).append("\n\n");
        }
        if (conv.summary != null && !conv.summary.isEmpty()) {
            sb.append("【长期记忆摘要】（这是你和主人过往对话的压缩记忆）\n").append(conv.summary).append("\n\n");
        }
        JSONArray window = windowHistory(conv, ctxRounds());
        if (window.length() > 0) {
            sb.append("【近期对话记录】\n");
            for (int i = 0; i < window.length(); i++) {
                JSONObject o = window.optJSONObject(i);
                if (o != null) {
                    sb.append("user".equals(o.optString("role")) ? "[主人] " : "[你] ");
                    sb.append(o.optString("content")).append('\n');
                }
            }
            sb.append('\n');
        }
        sb.append("【当前消息】\n[主人] ").append(currentMsg).append("\n\n");
        if (multiMsg()) {
            String mr = multiRule();
            if (mr != null && !mr.trim().isEmpty()) sb.append(mr.trim()).append("\n\n");
        }
        sb.append("（请严格保持角色设定与记忆的连续性，直接自然地回复【当前消息】，不要复述以上设定与规则。）");
        return sb.toString();
    }

    private JSONArray windowHistory(Conv conv, int rounds) {
        JSONArray arr = new JSONArray();
        for (int i = Math.max(0, conv.history.length() - (rounds * 2)); i < conv.history.length(); i++) {
            arr.put(conv.history.optJSONObject(i));
        }
        return arr;
    }

    public synchronized void appendRound(Conv conv, String user, String assistant) {
        try {
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", user);
            conv.history.put(u);
            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            a.put("content", assistant);
            conv.history.put(a);
            conv.totalRounds++;
        } catch (Exception e) {
            Util.log("appendRound 失败: " + e.getMessage());
        }
    }

    public synchronized boolean needsCompress(Conv conv) {
        return conv.history.length() > Math.max(compressRounds() * 2, (ctxRounds() * 2) + 4);
    }

    /**
     * 记忆压缩 Prompt。篇幅不再写死 300 字：按「既有摘要 + 待压缩对话」的体量自适应估算，
     * 下限 300、上限取用户配置（默认 3000），并向百位取整。
     */
    public synchronized String buildCompressPrompt(Conv conv) {
        int drop = Math.max(0, conv.history.length() - (ctxRounds() * 2));
        StringBuilder todo = new StringBuilder();
        for (int i = 0; i < drop; i++) {
            JSONObject o = conv.history.optJSONObject(i);
            if (o != null) {
                todo.append("user".equals(o.optString("role")) ? "[用户] " : "[助手] ");
                todo.append(o.optString("content")).append('\n');
            }
        }
        String oldSummary = (conv.summary != null && !conv.summary.isEmpty()) ? conv.summary : "（无）";
        int material = todo.length() + (conv.summary == null ? 0 : conv.summary.length());

        int max = Math.max(300, summaryMaxChar());
        int budget = 300 + material / 4;
        budget = Math.min(budget, max);
        budget = Math.max(300, ((budget + 99) / 100) * 100); // 向百位取整

        StringBuilder sb = new StringBuilder();
        // 总结模板可在「Prompt 工程」里编辑，{budget}/{max} 运行时替换
        String tpl = summaryTpl()
                .replace("{budget}", String.valueOf(budget))
                .replace("{max}", String.valueOf(max));
        sb.append(tpl.trim()).append("\n\n");
        sb.append("【既有摘要】\n").append(oldSummary).append("\n\n");
        sb.append("【待压缩对话】\n").append(todo.toString().trim()).append('\n');
        return sb.toString();
    }

    public synchronized void applySummary(Conv conv, String summary) {
        if (summary == null || summary.trim().isEmpty()) return;
        conv.summary = summary.trim();
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, conv.history.length() - (ctxRounds() * 2)); i < conv.history.length(); i++) {
            kept.put(conv.history.optJSONObject(i));
        }
        conv.history = kept;
        save(conv);
        Util.log("记忆压缩完成，摘要 " + conv.summary.length() + " 字，窗口剩 " + conv.history.length() + " 条");
    }

    public synchronized void rotateSession(Conv conv) {
        conv.deepseekSessionId = null;
        conv.parentMsgId = null;
        conv.totalRounds = 0;
        save(conv);
        Util.log("会话已轮换（保留记忆摘要）");
    }

    public synchronized void reset(Conv conv) {
        conv.deepseekSessionId = null;
        conv.parentMsgId = null;
        conv.summary = "";
        conv.history = new JSONArray();
        conv.totalRounds = 0;
        save(conv);
        Util.log("会话与记忆已全部重置: " + conv.userId);
    }

    public synchronized String statusText(Conv conv) {
        StringBuilder sb = new StringBuilder();
        sb.append("累计轮数: ").append(conv.totalRounds);
        sb.append("\n窗口消息: ").append(conv.history.length()).append(" 条（窗口 ").append(ctxRounds()).append(" 轮）");
        sb.append("\n记忆摘要: ").append(conv.summary.isEmpty() ? "无" : conv.summary.length() + " 字");
        sb.append("\n时间注入: ").append(timeInject() ? "开" : "关").append(" | 多条消息: ").append(multiMsg() ? "开" : "关");
        sb.append("\n文件传输: ").append(fileTransfer() ? "开" : "关");
        return sb.toString();
    }

    public synchronized List<Conv> allConvs() {
        List<Conv> out = new ArrayList<>();
        File dir = new File(ctx.getFilesDir(), "conversations");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                String raw = Util.readFile(f);
                try {
                    if (raw == null) raw = "{}";
                    String uid = new JSONObject(raw).optString("userId");
                    if (uid.isEmpty()) {
                        String hash = f.getName().replace(".json", "");
                        for (Map.Entry<String, Conv> e : cache.entrySet()) {
                            if (Util.md5(e.getKey()).equals(hash)) { uid = e.getKey(); break; }
                        }
                    }
                    if (!uid.isEmpty()) {
                        Conv c = get(uid);
                        if (!out.contains(c)) out.add(c);
                    }
                } catch (Exception ignored) {}
            }
        }
        for (Conv c : cache.values()) {
            if (!out.contains(c) && (c.history.length() > 0 || (c.summary != null && !c.summary.isEmpty()))) out.add(c);
        }
        return out;
    }

    public synchronized String clearAll() {
        List<Conv> all = allConvs();
        for (Conv c : all) reset(c);
        return "已清空 " + all.size() + " 个会话";
    }

    public synchronized String previewPrompt() {
        Conv conv = new Conv();
        conv.userId = "preview";
        conv.summary = "（示例）用户喜欢简洁回复，正在做一个微信桥接项目…";
        try {
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "早上好");
            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            a.put("content", "主人早上好呀～今天有什么想让我帮忙的吗[愉快]");
            conv.history.put(u);
            conv.history.put(a);
        } catch (Exception ignored) {}
        return buildPrompt(conv, "（用户的下一条消息会出现在这里）");
    }
}
