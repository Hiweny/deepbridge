package com.hiweny.deepbridge;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 原生侧与注入脚本 bridge.js 的唯一通道：在主线程 eval JS，用 reqId/latch 同步取回结果。 */
public class DeepSeekController {
    private static volatile DeepSeekController sInstance;
    private volatile WebView webView;
    private volatile String bridgeJs = "";
    private volatile StatusListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Object domLock = new Object();

    public interface StatusListener {
        void onStatus(String json);
    }

    private static class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject result;
    }

    private DeepSeekController() {}

    public static DeepSeekController get() {
        if (sInstance == null) {
            synchronized (DeepSeekController.class) {
                if (sInstance == null) sInstance = new DeepSeekController();
            }
        }
        return sInstance;
    }

    public void attach(WebView view, String bridgeJs, StatusListener listener) {
        this.webView = view;
        this.bridgeJs = bridgeJs;
        this.listener = listener;
    }

    public void detach(WebView view) {
        if (this.webView == view) this.webView = null;
    }

    public boolean isAttached() { return webView != null; }

    public void injectBridge() { eval(bridgeJs); }

    private void eval(final String js) {
        if (js == null || js.isEmpty()) return;
        final WebView view = webView;
        if (view == null) return;
        main.post(() -> {
            try { view.evaluateJavascript(js, null); }
            catch (Exception e) { Util.log("eval失败: " + e.getMessage()); }
        });
    }

    public void probe() { eval("window.DSKB && DSKB.probe();"); }

    public void onJsEvent(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type");
            String reqId = o.optString("reqId", "");
            Pending p = reqId.isEmpty() ? null : pending.remove(reqId);
            if (p != null) {
                p.result = o;
                p.latch.countDown();
            }
            if ("reply".equals(type)) {
                StringBuilder sb = new StringBuilder("DS回复 via=dom ok=").append(o.optBoolean("ok"));
                if (o.optBoolean("recalled")) sb.append(" [撤回已拦截]");
                sb.append(" len=").append(o.optString("content").length());
                if (!o.optString("error").isEmpty()) sb.append(" err=").append(o.optString("error"));
                Util.log(sb.toString());
                return;
            }
            if ("attach".equals(type)) {
                Util.log("附件挂载 " + (o.optBoolean("ok") ? "成功" : "失败")
                        + " " + o.optString("name", "")
                        + (o.optBoolean("ok") ? "" : " err=" + o.optString("error")));
                return;
            }
            if ("newChat".equals(type)) {
                Util.log("新建对话: " + (o.optBoolean("ok") ? "成功" : "失败"));
                return;
            }
            if ("pageReply".equals(type)) {
                Util.log("页面手动回复 len=" + o.optString("content").length()
                        + (o.optBoolean("recalled") ? " [撤回已拦截]" : ""));
                return;
            }
            if (p == null) forward(o);
        } catch (Exception e) {
            Util.log("onJsEvent 解析失败: " + e.getMessage());
        }
    }

    private void forward(final JSONObject o) {
        final StatusListener l = listener;
        if (l != null) main.post(() -> l.onStatus(o.toString()));
    }

    private JSONObject callJs(String method, JSONObject arg, int timeoutSec) {
        if (webView == null) return err("APP_NOT_RUNNING: 请保持 DeepBridge 运行（DeepSeek 页面需存活）");
        try {
            String reqId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            arg.put("reqId", reqId);
            Pending p = new Pending();
            pending.put(reqId, p);
            eval("window.DSKB ? DSKB." + method + "(" + arg + ") : (function(){if(window.DSB){DSB.onEvent(JSON.stringify({type:'"
                    + method + "',reqId:'" + reqId + "',ok:false,error:'NO_BRIDGE'}))}})();");
            if (p.latch.await(timeoutSec, TimeUnit.SECONDS)) {
                return p.result == null ? err("NULL") : p.result;
            }
            pending.remove(reqId);
            return err("TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("INTERRUPTED");
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public boolean isBusy() { return !pending.isEmpty(); }

    public JSONObject sendPrompt(String prompt) { return sendPrompt(prompt, 200); }

    public JSONObject sendPrompt(String prompt, int timeoutSec) {
        synchronized (domLock) {
            try {
                JSONObject arg = new JSONObject();
                arg.put("sessionId", "");
                arg.put("prompt", prompt);
                return callJs("send", arg, timeoutSec);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }

    /**
     * 带附件发送：先逐个把文件注入官网输入区（由官网自行上传/解析/挂载），全部就绪后再发送文本。
     * 任意一个附件挂载失败即返回错误，避免「图没传上去却发了文字」。
     */
    public JSONObject sendWithFiles(String prompt, List<MediaFile> files, int timeoutSec) {
        synchronized (domLock) {
            try {
                if (files != null) {
                    for (MediaFile f : files) {
                        JSONObject arg = new JSONObject();
                        arg.put("name", f.name);
                        arg.put("mime", f.mime);
                        arg.put("b64", f.base64());
                        JSONObject r = callJs("attachFile", arg, 150);
                        if (!r.optBoolean("ok")) return r;
                    }
                }
                JSONObject arg = new JSONObject();
                arg.put("sessionId", "");
                arg.put("prompt", prompt);
                return callJs("send", arg, timeoutSec);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }

    public JSONObject newChat() {
        synchronized (domLock) {
            try {
                return callJs("newChat", new JSONObject(), 15);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg == null ? "unknown" : msg);
        } catch (Exception ignored) {}
        return o;
    }
}
