package com.hiweny.deepbridge;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 通用工具：内存日志、文本切分/清洗、MD5、文件读写。 */
public final class Util {
    private static final String TAG = "DeepBridge";
    private static final List<String> LOG = new ArrayList<>();
    private static final int LOG_MAX = 400;

    private Util() {}

    public static synchronized void log(String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        LOG.add("[" + ts + "] " + msg);
        if (LOG.size() > LOG_MAX) LOG.remove(0);
        Log.d(TAG, msg);
    }

    public static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, LOG.size() - 200); i < LOG.size(); i++) {
            sb.append(LOG.get(i)).append('\n');
        }
        return sb.toString();
    }

    public static String nowText() {
        Date d = new Date();
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        return new SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(d) + " 星期" + week[d.getDay()];
    }

    /** 把 Markdown 清洗成适合微信阅读的纯文本。 */
    public static String wechatify(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)```[a-zA-Z0-9+#-]*\\r?\\n(.*?)```", "$1")
                .replaceAll("(?s)```(.*?)```", "$1")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "[图片]")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?<!\\*)\\*([^*\n]+)\\*(?!\\*)", "$1")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("~~(.+?)~~", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s?", "")
                .replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "————————")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
                .trim();
    }

    /** 按段落/标点把长文本切成不超过 size 的块，尽量不在句子中间断开。 */
    public static List<String> chunkText(String s, int size) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        while (s.length() > size) {
            int cut;
            int para = s.lastIndexOf("\n\n", size);
            int half = size / 2;
            if (para < half) {
                String[] seps = {"。", "！", "？", "；", ". ", "\n", " "};
                cut = size;
                for (String sep : seps) {
                    int idx = s.lastIndexOf(sep, size);
                    if (idx > half) { cut = idx + sep.length(); break; }
                }
            } else {
                cut = para + 2;
            }
            if (cut <= 0) cut = size;
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** 多条消息拆分：用单个反斜杠分隔，但 \\ 与 \x（小写字母，如 LaTeX）不拆。 */
    public static List<String> splitMultiMsg(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.replace("\\\\", "\u0000").split("\\\\(?![a-z])")) {
            String t = part.replace("\u0000", "\\").trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty() && !s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = Math.max(in.read(buf), 0);
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log("写文件失败: " + f + " " + e.getMessage());
        }
    }
}
