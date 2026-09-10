package com.hiweny.deepbridge;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信 iLink CDN 媒体下载与解密。
 *
 * 入站消息里 image_item / file_item 携带 CDNMedia：
 *   { full_url, encrypt_query_param, aes_key, encrypt_type }
 * 下载得到 AES-128-ECB + PKCS7 密文，用 aes_key 解密；密钥存在三种编码，需要全部兼容。
 */
public final class WeChatMedia {
    /** CDN 回退基址（full_url 缺失时手工拼接）。 */
    private static final String CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";
    /** 单个附件大小上限（超过则放弃，避免撑爆 WebView 的 JS 桥）。 */
    public static final int MAX_BYTES = 25 * 1024 * 1024;

    private WeChatMedia() {}

    /** 一条待下载的入站媒体描述。 */
    public static class Ref {
        public final int type;                 // 2 图片 / 4 文件
        public final String fileName;
        public final JSONObject media;         // CDNMedia
        public final String aeskeyHex;         // image_item.aeskey（直接 32 位 hex，可能为空）
        public final String directUrl;         // 部分场景 image_item.url 直链

        Ref(int type, String fileName, JSONObject media, String aeskeyHex, String directUrl) {
            this.type = type;
            this.fileName = fileName;
            this.media = media;
            this.aeskeyHex = aeskeyHex;
            this.directUrl = directUrl;
        }
    }

    /** 从入站消息中抽取图片/文件引用（视频暂不支持，DeepSeek 网页侧无法接收）。 */
    public static List<Ref> extractRefs(JSONObject msg) {
        List<Ref> out = new ArrayList<>();
        if (msg == null) return out;
        org.json.JSONArray items = msg.optJSONArray("item_list");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            int type = it.optInt("type", -1);
            try {
                if (type == 2) {
                    JSONObject img = it.optJSONObject("image_item");
                    if (img != null && img.optJSONObject("media") != null) {
                        String name = "weixin-image-" + System.currentTimeMillis() + "-" + i + ".jpg";
                        out.add(new Ref(2, name, img.optJSONObject("media"), img.optString("aeskey", ""), img.optString("url", "")));
                    }
                } else if (type == 4) {
                    JSONObject file = it.optJSONObject("file_item");
                    if (file != null && file.optJSONObject("media") != null) {
                        String name = file.optString("file_name", "");
                        if (name.isEmpty()) name = "weixin-file-" + i;
                        out.add(new Ref(4, name, file.optJSONObject("media"), "", ""));
                    }
                }
            } catch (Exception e) {
                Util.log("抽取媒体引用失败: " + e.getMessage());
            }
        }
        return out;
    }

    /** 下载并解密，返回明文字节；失败抛异常。 */
    public static byte[] downloadAndDecrypt(Ref ref) throws Exception {
        List<String> urls = new ArrayList<>();
        String full = ref.media.optString("full_url", "");
        if (!full.isEmpty()) urls.add(full);
        String qp = ref.media.optString("encrypt_query_param", "");
        if (!qp.isEmpty()) urls.add(CDN_BASE + "/download?encrypted_query_param=" + URLEncoder.encode(qp, "UTF-8"));
        if (ref.directUrl != null && !ref.directUrl.isEmpty()) urls.add(ref.directUrl);
        if (urls.isEmpty()) throw new IllegalStateException("媒体缺少下载地址");

        byte[] cipher = null;
        Exception last = null;
        for (String u : urls) {
            try {
                cipher = httpGet(u);
                if (cipher != null && cipher.length > 0) break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (cipher == null || cipher.length == 0) {
            throw new IllegalStateException("下载媒体失败" + (last != null ? ": " + last.getMessage() : ""));
        }
        if (cipher.length > MAX_BYTES) throw new IllegalStateException("文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");

        List<byte[]> keys = candidateKeys(ref.media.optString("aes_key", ""), ref.aeskeyHex);
        IllegalStateException decryptErr = null;
        for (byte[] key : keys) {
            try {
                return aesEcbDecrypt(cipher, key);
            } catch (Exception e) {
                decryptErr = new IllegalStateException(e.getMessage());
            }
        }
        // 解密全部失败：个别直链返回的本身就是明文，直接回退原始字节
        if (keys.isEmpty()) return cipher;
        throw new IllegalStateException("AES 解密失败" + (decryptErr != null ? ": " + decryptErr.getMessage() : ""));
    }

    private static byte[] httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DeepBridge");
        try {
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** 汇总所有可能的 16 字节 AES 密钥候选。 */
    private static List<byte[]> candidateKeys(String mediaAesKeyB64, String itemAesKeyHex) {
        List<byte[]> keys = new ArrayList<>();
        addKey(keys, decodeMediaAesKey(mediaAesKeyB64));
        if (itemAesKeyHex != null && itemAesKeyHex.length() == 32) addKey(keys, hex(itemAesKeyHex));
        return keys;
    }

    private static void addKey(List<byte[]> keys, byte[] k) {
        if (k != null && k.length == 16) {
            for (byte[] ex : keys) if (java.util.Arrays.equals(ex, k)) return;
            keys.add(k);
        }
    }

    /** CDNMedia.aes_key：base64(原始16字节) 或 base64(32位hex字符串)。 */
    private static byte[] decodeMediaAesKey(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            if (raw.length == 16) return raw;
            if (raw.length == 32) {
                String maybeHex = new String(raw, StandardCharsets.US_ASCII);
                if (maybeHex.matches("[0-9a-fA-F]{32}")) return hex(maybeHex);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] aesEcbDecrypt(byte[] cipher, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(cipher);
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    /** 根据文件名推断 MIME。 */
    public static String guessMime(String name, int type) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".doc") || n.endsWith(".docx")) return "application/msword";
        if (n.endsWith(".xls") || n.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (n.endsWith(".ppt") || n.endsWith(".pptx")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        if (type == 2) return "image/jpeg";
        return "application/octet-stream";
    }
}
