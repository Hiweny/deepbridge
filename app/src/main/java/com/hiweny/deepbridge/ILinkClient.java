package com.hiweny.deepbridge;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 微信 ClawBot（iLink）HTTP 协议客户端。 */
public final class ILinkClient {
    private static final String CHANNEL_VERSION = "1.0.2";
    public static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com";
    private static final SecureRandom RND = new SecureRandom();

    private ILinkClient() {}

    private static String randomUin() {
        long v = RND.nextInt() & 0xffffffffL;
        return Base64.encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static Map<String, String> baseHeaders(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("AuthorizationType", "ilink_bot_token");
        h.put("X-WECHAT-UIN", randomUin());
        if (token != null && !token.isEmpty()) h.put("Authorization", "Bearer " + token);
        return h;
    }

    private static JSONObject http(String base, String path, boolean post, JSONObject body,
                                   String token, Map<String, String> extra, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(post ? "POST" : "GET");
            Map<String, String> headers = baseHeaders(token);
            if (extra != null) headers.putAll(extra);
            for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            if (post && body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            String text = sb.length() == 0 ? "{}" : sb.toString();
            JSONObject json = new JSONObject(text);
            json.put("__http", code);
            return json;
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("__error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception ignored) {}
            return err;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject baseInfo() throws org.json.JSONException {
        JSONObject b = new JSONObject();
        b.put("channel_version", CHANNEL_VERSION);
        return b;
    }

    public static JSONObject getQrCode(String base) {
        return http(base, "/ilink/bot/get_bot_qrcode?bot_type=3", false, null, null, null, 15000);
    }

    public static JSONObject pollQrStatus(String base, String qrcode) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("iLink-App-ClientVersion", "1");
            return http(base, "/ilink/bot/get_qrcode_status?qrcode=" + URLEncoder.encode(qrcode, "UTF-8"),
                    false, null, null, h, 40000);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject getUpdates(String base, String token, String cursor) {
        try {
            JSONObject body = new JSONObject();
            body.put("get_updates_buf", cursor == null ? "" : cursor);
            body.put("base_info", baseInfo());
            return http(base, "/ilink/bot/getupdates", true, body, token, null, 45000);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject sendText(String base, String token, String toUser, String contextToken, String text) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("from_user_id", "");
            msg.put("to_user_id", toUser);
            msg.put("client_id", "db-" + UUID.randomUUID().toString());
            msg.put("message_type", 2);
            msg.put("message_state", 2);
            msg.put("context_token", contextToken);
            JSONArray itemList = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", 1);
            JSONObject textItem = new JSONObject();
            textItem.put("text", text);
            item.put("text_item", textItem);
            itemList.put(item);
            msg.put("item_list", itemList);
            JSONObject body = new JSONObject();
            body.put("msg", msg);
            body.put("base_info", baseInfo());
            return http(base, "/ilink/bot/sendmessage", true, body, token, null, 20000);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject getConfig(String base, String token, String ilinkUserId, String contextToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("ilink_user_id", ilinkUserId);
            body.put("context_token", contextToken);
            body.put("base_info", baseInfo());
            return http(base, "/ilink/bot/getconfig", true, body, token, null, 12000);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject sendTyping(String base, String token, String ilinkUserId, String ticket, int status) {
        try {
            JSONObject body = new JSONObject();
            body.put("ilink_user_id", ilinkUserId);
            body.put("typing_ticket", ticket);
            body.put("status", status);
            body.put("base_info", baseInfo());
            return http(base, "/ilink/bot/sendtyping", true, body, token, null, 10000);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    /** 提取文本（语音取服务端转写）；图片/文件/视频用占位符，真实附件由 WeChatMedia 另路下载。 */
    public static String extractText(JSONObject msg) {
        try {
            JSONArray items = msg.optJSONArray("item_list");
            if (items == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                int type = it.optInt("type", -1);
                if (type == 1 && it.optJSONObject("text_item") != null) {
                    sb.append(it.optJSONObject("text_item").optString("text", ""));
                } else if (type == 3 && it.optJSONObject("voice_item") != null) {
                    String t = it.optJSONObject("voice_item").optString("text", "");
                    sb.append(t.isEmpty() ? "[语音]" : t);
                } else if (type == 2) {
                    sb.append("[图片]");
                } else if (type == 4) {
                    sb.append("[文件]");
                } else if (type == 5) {
                    sb.append("[视频]");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("__error", msg == null ? "unknown" : msg); } catch (Exception ignored) {}
        return o;
    }
}
