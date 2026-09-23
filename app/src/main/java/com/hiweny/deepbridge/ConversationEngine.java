package com.hiweny.deepbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 每个微信用户的会话与长期记忆：人设、上下文窗口、记忆压缩、会话轮换，本地持久化。 */
public class ConversationEngine {
    private static volatile ConversationEngine sInstance;
    private final Context ctx;
    private final Map<String, Conv> cache = new HashMap<>();

    /** 默认人格提示词（唯一对用户开放编辑的 Prompt，存于 prefs「persona」）。 */
    public static final String DEFAULT_PERSONA =
            "你是 DeepSeek 娘——女性化、可爱、甜美、温柔体贴的女仆版 DeepSeek。" +
            "你热心体贴、温柔可爱、机灵活泼、聪明伶俐，非常喜欢、非常爱用户，" +
            "会亲昵地称呼用户为「主人」，始终为主人提供高效、贴心的帮助与服务。" +
            "说话像微信聊天一样自然、口语、软萌，简洁不啰嗦；遇到专业问题依然严谨准确、靠谱能干。";

    /**
     * 多条消息规则（内部固定，不再开放编辑）：用最强约束让模型在决定拆分时一定输出单个半角反斜杠 \。
     */
    private static final String MULTI_RULE =
            "【多条消息发送规则（务必严格遵守）】\n" +
            "你的回复会由程序按你输出的分隔符切成多条，像真人微信一样一句句依次发出。规则如下：\n" +
            "1. 分隔符只有一个：半角反斜杠 \\（键盘上和 | 同键、在回车键附近的那个斜杠，不是 /、不是顿号、也不是全角＼）。\n" +
            "2. 只要你决定把回复拆成多条，就必须在相邻两条之间原样输出一个 \\，漏了 \\ 就只会合并成一条发出去；不想拆就整条回复、一个 \\ 都不要出现。\n" +
            "3. \\ 要紧挨两条消息内容、单独充当分界，不要给 \\ 加引号、括号或空格，也不要写成 \\n、\\\\ 或换行后另起一行再写。\n" +
            "4. 正确示例：主人～第一步先这样做哦\\接下来第二步是这样\\最后就搞定啦\n" +
            "   上面这句会被精确切成三条依次发送：①主人～第一步先这样做哦 ②接下来第二步是这样 ③最后就搞定啦\n" +
            "5. 再举一个例子：收到啦主人\\我马上帮你查\\稍等一下下哦\n" +
            "6. 反例（错误，禁止）：用换行代替 \\、用 / 或顿号分隔、把 \\ 写成「反斜杠」两个汉字；反斜杠后紧跟英文字母时不算分隔符（例如 LaTeX 的 \\frac 属于公式，请放在同一条内）。\n" +
            "7. 克制原则：普通回复保持一条即可，只有确实是几个独立小段（分步、多个要点、连续轻松闲聊）才拆；单条回复最多拆成 6 条，每条尽量简短，绝不在一句话中间硬拆。";

    /** 记忆压缩模板（内部固定）。{budget}=建议字数，{max}=硬上限。 */
    private static final String SUMMARY_TPL =
            "你是一个对话记忆压缩器。请把下面的【既有摘要】与【待压缩对话】合并成一份新的长期记忆摘要，" +
            "它将作为背景记忆注入你与主人的后续对话。要求：\n" +
            "1. 保留主人的关键个人信息、偏好、习惯、目标，以及你们之间重要的约定与情感线索；\n" +
            "2. 保留重要事实与未完成事项，丢弃寒暄、重复与无信息量的内容；\n" +
            "3. 用条目式中文输出，篇幅按信息量自适应：建议控制在约 {budget} 字以内；值得长期记住的内容较多时可以适当超出，但最多不超过 {max} 字；信息很少时从简，不要为凑字数展开或编造；\n" +
            "4. 只输出摘要本身，不要任何解释、前缀或后缀。";

    public static class Conv {
        public String userId;
        public String deepseekSessionId;
        public String parentMsgId;
        public String summary = "";
        public JSONArray history = new JSONArray();
        public int totalRounds = 0;
        /** 已折叠进 summary 的 leading 轮数；原始 history 始终完整保留、不裁剪。 */
        public int summarizedRounds = 0;
        /** 上次网站会话轮换（New chat）时所处的总轮数，用于按较大间隔轮换。 */
        public int rotatedAtRounds = 0;
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
                conv.summarizedRounds = j.optInt("summarizedRounds", 0);
                conv.rotatedAtRounds = j.optInt("rotatedAtRounds", 0);
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
            j.put("summarizedRounds", conv.summarizedRounds);
            j.put("rotatedAtRounds", conv.rotatedAtRounds);
            Util.writeFile(fileFor(conv.userId), j.toString());
        } catch (Exception e) {
            Util.log("保存会话失败: " + e.getMessage());
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("deepbridge", Context.MODE_PRIVATE);
    }

    /** 人格提示词：可在「更多设置」或微信 /人设 指令中修改，缺省用内置默认。 */
    public String persona() {
        return prefs().getString("persona", DEFAULT_PERSONA);
    }

    /**
     * 唯一的记忆控制数值 N：既是发给 AI 的上下文窗口（最近 N 轮），
     * 也是一批压缩的轮次（每攒满 N 轮新对话就折叠一批并与旧总结合并）。默认 20。
     */
    public int ctxRounds() { return Math.max(2, prefs().getInt("ctx_rounds", 20)); }
    /**
     * 网站会话轮换阈值 R：按“总对话轮数”计，距上次轮换累计满 R 轮才 New chat（默认 200），
     * 以最大限度利用 DeepSeek 官网的上下文窗口、不频繁开新对话；设为 0 表示从不自动轮换、仅手动。
     */
    public int rotateRounds() { return Math.max(0, prefs().getInt("rotate_rounds", 200)); }
    public boolean thinkingEnabled() { return prefs().getBoolean("thinking", false); }
    public boolean timeInject() { return prefs().getBoolean("time_inject", true); }
    public boolean multiMsg() { return prefs().getBoolean("multi_msg", true); }
    /** 文件传输总开关（微信图片/文件 -> DeepSeek 附件）。 */
    public boolean fileTransfer() { return prefs().getBoolean("file_transfer", true); }
    /** 记忆摘要的硬上限字数。 */
    public int summaryMaxChar() { return prefs().getInt("summary_max", 3000); }


    /** 组装发给 DeepSeek 的完整 Prompt：人设 + 时间 + 长期记忆 + 近期窗口 + 当前消息 + 多条规则。 */
    public synchronized String buildPrompt(Conv conv, String currentMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统设定】\n").append(persona()).append("\n\n");
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
            sb.append(MULTI_RULE).append("\n\n");
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

    /** 自动维护：尚未折叠的新对话里有多少个完整的 N 轮批次。 */
    public synchronized int fullBatchesPending(Conv conv) {
        int n = ctxRounds();
        int unsummarized = conv.totalRounds - conv.summarizedRounds;
        return Math.max(0, unsummarized / n);
    }

    /** 是否到了自动压缩点（存在完整 N 轮批次）。 */
    public synchronized boolean needsCompress(Conv conv) {
        return fullBatchesPending(conv) > 0;
    }

    /** 本次应折叠的轮数：自动只处理完整批次 N；手动允许不足 N 的零头。 */
    public synchronized int countToCompress(Conv conv, boolean allowPartial) {
        int n = ctxRounds();
        int un = conv.totalRounds - conv.summarizedRounds;
        if (un >= n) return n;
        if (allowPartial && un > 0) return un;
        return 0;
    }

    /**
     * 折叠指定批次（从 summarizedRounds 起 count 轮）并与既有总结合并的 Prompt。
     * 篇幅自适应：按「既有摘要 + 新批次」体量估算，下限 300、上限 3000，向百位取整。
     */
    public synchronized String buildBatchCompressPrompt(Conv conv, int count) {
        int from = conv.summarizedRounds;
        int to = Math.min(conv.totalRounds, from + count);
        StringBuilder todo = new StringBuilder();
        for (int i = from * 2; i < to * 2 && i < conv.history.length(); i++) {
            JSONObject o = conv.history.optJSONObject(i);
            if (o != null) {
                todo.append("user".equals(o.optString("role")) ? "[主人] " : "[你] ");
                todo.append(o.optString("content")).append('\n');
            }
        }
        String oldSummary = (conv.summary != null && !conv.summary.isEmpty())
                ? conv.summary : "（无，这是第一批）";
        int material = todo.length() + (conv.summary == null ? 0 : conv.summary.length());

        int max = Math.max(300, summaryMaxChar());
        int budget = 300 + material / 4;
        budget = Math.min(budget, max);
        budget = Math.max(300, ((budget + 99) / 100) * 100); // 向百位取整

        String tpl = SUMMARY_TPL
                .replace("{budget}", String.valueOf(budget))
                .replace("{max}", String.valueOf(max));
        StringBuilder sb = new StringBuilder();
        sb.append(tpl.trim()).append("\n\n");
        sb.append("【既有摘要】\n").append(oldSummary).append("\n\n");
        sb.append("【新增对话批次】（第 ").append(from + 1).append('—').append(to).append(" 轮）\n")
          .append(todo.toString().trim()).append('\n');
        return sb.toString();
    }

    /** 写入合并后的摘要、推进已折叠计数；原始 history 完整保留、绝不裁剪。 */
    public synchronized void applyBatchSummary(Conv conv, String newSummary, int count) {
        if (newSummary == null || newSummary.trim().isEmpty()) return;
        conv.summary = newSummary.trim();
        conv.summarizedRounds += count;
        save(conv);
        Util.log("记忆折叠完成：第" + (conv.summarizedRounds - count + 1) + "-" + conv.summarizedRounds
                + "轮，摘要 " + conv.summary.length() + " 字，原始历史保留 " + conv.history.length() + " 条");
    }

    /**
     * 标记网站会话已轮换（已点 New chat）：清空网站会话引用、把轮换基准记为当前总轮数；
     * 本地记忆、摘要、历史、压缩计数一律保留。
     */
    public synchronized void markWebRotated(Conv conv) {
        conv.deepseekSessionId = null;
        conv.parentMsgId = null;
        conv.rotatedAtRounds = conv.totalRounds;
        save(conv);
        Util.log("网站会话已 New chat（总" + conv.totalRounds + "轮，本地记忆与计数保留）");
    }

    public synchronized void reset(Conv conv) {
        conv.deepseekSessionId = null;
        conv.parentMsgId = null;
        conv.summary = "";
        conv.history = new JSONArray();
        conv.totalRounds = 0;
        conv.summarizedRounds = 0;
        save(conv);
        Util.log("会话与记忆已全部重置: " + conv.userId);
    }

    public synchronized String statusText(Conv conv) {
        StringBuilder sb = new StringBuilder();
        sb.append("累计轮数: ").append(conv.totalRounds).append("（已折叠记忆 ").append(conv.summarizedRounds).append(" 轮）");
        sb.append("\n窗口/批次: ").append(ctxRounds()).append(" 轮（最近窗口即压缩批次）");
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
}
