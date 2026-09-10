package com.hiweny.deepbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.ScaleAnimation;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String DS_URL = "https://chat.deepseek.com/";
    private static final int TAB_CONTROL = 0;
    private static final int TAB_WEB = 1;
    private static final String APP_VERSION = "v1.4";

    private View controlPanel;
    private View webPanel;
    private LinearLayout navIconBoxControl, navIconBoxWeb;
    private TextView navIconControl, navIconWeb, navLabelControl, navLabelWeb;
    private Theme theme;
    private TextView tvDeepseek, tvWechat, tvRuntime, tvStatRounds, tvStatSummary;
    private View tvDotDs, tvDotWechat;
    private WebView webView;
    private String bridgeJs = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean dsLoggedIn = false;
    private boolean dsInputReady = false;
    private int currentTab = 0;
    private volatile boolean loginCancelled = false;

    private final Runnable statsTask = new Runnable() {
        @Override public void run() {
            if (currentTab == TAB_CONTROL && tvRuntime != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(prefs().getString("ilink_token", null) != null ? "🟢 微信令牌" : "🔴 微信令牌").append("  ");
                sb.append(BotService.pollAlive ? "🟢 轮询中" : "🔴 轮询停止");
                sb.append("\n📥 上次收到: ").append(agoText(BotService.lastMsgRecv));
                sb.append("   📤 上次回复: ").append(agoText(BotService.lastReplyOk));
                sb.append("\n📊 消息统计: 收 ").append(BotService.msgReceived).append(" / 发 ").append(BotService.msgReplied).append('\n');
                if (!BotService.lastError.isEmpty()) sb.append("⚠️ 最近错误: ").append(BotService.lastError);
                tvRuntime.setText(sb.toString());
            }
            handler.postDelayed(this, 3000L);
        }
    };

    private final Runnable probeTask = new Runnable() {
        @Override public void run() {
            DeepSeekController.get().injectBridge();
            DeepSeekController.get().probe();
            handler.postDelayed(this, 15000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = Theme.of(this);
        buildUi();
        loadBridgeJs();
        setupWebView();
        requestNotifPermission();
        requestBatteryWhitelist();
        BotService.setStatusUi(this::setWechatStateMain);
        SharedPreferences sp = prefs();
        if (sp.getBoolean("keep_screen", true)) getWindow().addFlags(128);
        if (sp.getString("ilink_token", null) != null) {
            setWechatState("已连接（启动轮询…）");
            BotService.start(this);
        } else {
            setWechatState("未连接");
        }
        refreshStats();
        handler.postDelayed(statsTask, 3000L);
    }

    private void setWechatStateMain(String s) {
        runOnUiThread(() -> setWechatState(s));
    }

    @Override
    protected void onDestroy() {
        BotService.setStatusUi(null);
        DeepSeekController.get().detach(webView);
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == TAB_WEB) switchTab(TAB_CONTROL);
        else moveTaskToBack(true);
    }

    private SharedPreferences prefs() { return getSharedPreferences("deepbridge", MODE_PRIVATE); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable roundedBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable btnBg() {
        GradientDrawable g = roundedBg(theme.btnBg(), 16);
        g.setStroke(dp(1), theme.btnStroke());
        return g;
    }

    private Drawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this,
                theme.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert));
    }

    private void addPressAnim(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start();
                return false;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
            return false;
        });
    }

    private void fadeIn(View v) {
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(180);
        v.startAnimation(a);
    }

    private void breathe(View v) {
        AlphaAnimation a = new AlphaAnimation(0.35f, 1f);
        a.setDuration(1100);
        a.setRepeatCount(-1);
        a.setRepeatMode(AlphaAnimation.REVERSE);
        v.startAnimation(a);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg());
        FrameLayout container = new FrameLayout(this);
        controlPanel = buildControlPanel();
        container.addView(controlPanel, new FrameLayout.LayoutParams(-1, -1));
        webPanel = buildWebPanel();
        webPanel.setVisibility(View.GONE);
        container.addView(webPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateNavStyle();
    }

    private View buildWebPanel() {
        FrameLayout f = new FrameLayout(this);
        webView = new WebView(this);
        f.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private View buildBottomNav() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.headerBg());
        View divider = new View(this);
        divider.setBackgroundColor(theme.divider());
        root.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(24), dp(7), dp(24), dp(9));
        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout itemC = buildNavItem("🎛", "控制台", v -> switchTab(TAB_CONTROL));
        LinearLayout itemW = buildNavItem("💬", "对话页", v -> switchTab(TAB_WEB));
        itemC.setTag("c");
        itemW.setTag("w");
        row.addView(itemC, itemLp);
        row.addView(itemW, itemLp);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private LinearLayout buildNavItem(String icon, String label, final View.OnClickListener onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setPadding(dp(22), dp(5), dp(22), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.accent());
        g.setAlpha(0);
        g.setCornerRadius(dp(20));
        pill.setBackground(g);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(21f);
        ic.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(ic);
        col.addView(pill);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(11.5f);
        lb.setGravity(android.view.Gravity.CENTER);
        lb.setPadding(0, dp(3), 0, 0);
        col.addView(lb);
        if ("🎛".equals(icon)) { navIconBoxControl = pill; navIconControl = ic; navLabelControl = lb; }
        else { navIconBoxWeb = pill; navIconWeb = ic; navLabelWeb = lb; }
        col.setOnClickListener(v -> {
            onClick.onClick(v);
            animateNavPill(pill);
        });
        addPressAnim(col);
        return col;
    }

    private void animateNavPill(LinearLayout pill) {
        ScaleAnimation a = new ScaleAnimation(0.7f, 1f, 0.7f, 1f, 1, 0.5f, 1, 0.5f);
        a.setDuration(200);
        pill.startAnimation(a);
    }

    private void switchTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        boolean control = tab == TAB_CONTROL;
        controlPanel.setVisibility(control ? View.VISIBLE : View.GONE);
        webPanel.setVisibility(control ? View.GONE : View.VISIBLE);
        fadeIn(control ? controlPanel : webPanel);
        updateNavStyle();
        if (control) refreshStats();
    }

    private void updateNavStyle() {
        boolean c = currentTab == TAB_CONTROL;
        styleNav(navIconBoxControl, navIconControl, c);
        styleNav(navIconBoxWeb, navIconWeb, !c);
    }

    private void styleNav(LinearLayout pill, TextView label, boolean active) {
        ((GradientDrawable) pill.getBackground()).setAlpha(active ? 36 : 0);
        label.setTextColor(active ? theme.accent() : theme.textSub());
        label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        pill.animate().scaleX(active ? 1.05f : 1f).scaleY(active ? 1.05f : 1f).setDuration(150).start();
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(theme.textSub());
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }

    private View buildControlPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, dp(14), pad, dp(16));
        scroll.addView(box);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView logo = new TextView(this);
        logo.setText("✨");
        logo.setTextSize(26f);
        breathe(logo);
        header.addView(logo);
        TextView title = new TextView(this);
        title.setText(" DeepBridge");
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(theme.text());
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ver = new TextView(this);
        ver.setText(APP_VERSION);
        ver.setTextSize(11f);
        ver.setTextColor(theme.textSub());
        header.addView(ver);
        box.addView(header);

        TextView sub = new TextView(this);
        sub.setText("微信 ClawBot × DeepSeek · 长期记忆 + 图片/文件桥接");
        sub.setTextSize(12f);
        sub.setTextColor(theme.textSub());
        sub.setPadding(0, dp(2), 0, 0);
        box.addView(sub);
        box.addView(spacer(dp(10)));

        LinearLayout connCard = card();
        box.addView(connCard);
        connCard.addView(cardTitle("连接状态"));
        LinearLayout wechatRow = new LinearLayout(this);
        wechatRow.setOrientation(LinearLayout.HORIZONTAL);
        wechatRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        wechatRow.setPadding(dp(4), dp(2), dp(4), dp(2));
        tvDotWechat = new View(this);
        tvDotWechat.setBackground(oval(theme.red()));
        wechatRow.addView(tvDotWechat, navDotLp());
        tvWechat = new TextView(this);
        tvWechat.setTextColor(theme.text());
        tvWechat.setTextSize(14f);
        tvWechat.setTypeface(Typeface.DEFAULT_BOLD);
        wechatRow.addView(tvWechat, new LinearLayout.LayoutParams(0, -2, 1f));
        connCard.addView(wechatRow);
        LinearLayout dsRow = new LinearLayout(this);
        dsRow.setOrientation(LinearLayout.HORIZONTAL);
        dsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dsRow.setPadding(dp(4), dp(2), dp(4), dp(2));
        tvDotDs = new View(this);
        tvDotDs.setBackground(oval(theme.amber()));
        dsRow.addView(tvDotDs, navDotLp());
        tvDeepseek = new TextView(this);
        tvDeepseek.setTextColor(theme.textSub());
        tvDeepseek.setTextSize(13f);
        dsRow.addView(tvDeepseek, new LinearLayout.LayoutParams(0, -2, 1f));
        connCard.addView(dsRow);

        LinearLayout runCard = card();
        box.addView(runCard);
        runCard.addView(cardTitle("运行状态（实时）"));
        tvRuntime = new TextView(this);
        tvRuntime.setTextSize(12.5f);
        tvRuntime.setTextColor(theme.text());
        tvRuntime.setLineSpacing(dp(3), 1f);
        tvRuntime.setPadding(dp(4), dp(2), dp(4), dp(2));
        runCard.addView(tvRuntime);

        LinearLayout memCard = card();
        box.addView(memCard);
        memCard.addView(cardTitle("记忆概览（所有用户）"));
        tvStatRounds = statLine(memCard);
        tvStatSummary = statLine(memCard);
        box.addView(spacer(dp(10)));

        LinearLayout swCard = card();
        box.addView(swCard);
        swCard.addView(cardTitle("快捷开关"));
        swCard.addView(makeSwitch("多条消息模式", "AI 用 \\ 分隔多条回复，依次发送到微信", "multi_msg", true));
        swCard.addView(makeSwitch("时间注入", "在 Prompt 中注入当前时间", "time_inject", true));
        swCard.addView(makeSwitch("文件传输", "把微信发来的图片/文件自动上传给 DeepSeek", "file_transfer", true));
        swCard.addView(makeSwitch("屏幕常亮", "保活关键开关（配合电池白名单）", "keep_screen", true));
        box.addView(spacer(dp(10)));

        box.addView(cardTitle("操作"));
        String[][] actions = {
                {"🔗", "连接微信"}, {"⛔", "断开微信"},
                {"🗜", "手动压缩"}, {"🧠", "记忆管理"},
                {"🩺", "自检诊断"}, {"✨", "新建对话"},
                {"⚙", "更多设置"}, {"📜", "运行日志"}};
        View.OnClickListener[] listeners = {
                v -> showQrDialog(), v -> disconnectWechat(),
                v -> manualCompress(), v -> showMemoryManager(),
                v -> runDiagnostics(), v -> newChatFromUi(),
                v -> showSettings(), v -> showLog()};
        box.addView(buildActionGrid(actions, listeners));
        box.addView(spacer(dp(4)));

        TextView helpEntry = new TextView(this);
        helpEntry.setText("❓ 使用帮助");
        helpEntry.setTextSize(13f);
        helpEntry.setTextColor(theme.accent());
        helpEntry.setPadding(dp(4), dp(10), dp(4), dp(6));
        helpEntry.setOnClickListener(v -> showHelp());
        box.addView(helpEntry);

        TextView tip = new TextView(this);
        tip.setText("提示：首次使用请到「对话页」登录 DeepSeek 并手动选择模式与开关；\n微信可直接发图片/文件；收到消息但没回复时，看「运行状态」或「自检诊断」定位问题");
        tip.setTextSize(11f);
        tip.setTextColor(theme.textSub());
        tip.setPadding(dp(4), dp(8), dp(4), 0);
        tip.setLineSpacing(dp(3), 1f);
        box.addView(tip);
        return scroll;
    }

    private LinearLayout.LayoutParams navDotLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
        lp.rightMargin = dp(8);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        return lp;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundedBg(theme.cardBg(), 18));
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView statLine(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextSize(13f);
        t.setTextColor(theme.text());
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        parent.addView(t);
        return t;
    }

    private Switch makeSwitch(final String name, String desc, final String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(name);
        sw.setTextSize(14f);
        sw.setTextColor(theme.text());
        sw.setChecked(prefs().getBoolean(key, def));
        sw.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        if (Build.VERSION.SDK_INT >= 23) {
            sw.setThumbTintList(ColorStateList.valueOf(theme.accent()));
            sw.setTrackTintList(ColorStateList.valueOf(theme.divider()));
        }
        sw.setOnCheckedChangeListener((button, checked) -> {
            prefs().edit().putBoolean(key, checked).apply();
            if ("keep_screen".equals(key)) {
                if (checked) getWindow().addFlags(128); else getWindow().clearFlags(128);
            }
            toast(desc + (checked ? " 已开启" : " 已关闭"));
        });
        return sw;
    }

    private View buildActionGrid(String[][] items, View.OnClickListener[] listeners) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(2), 0, 0);
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.topMargin = dp(8);
            for (int c = 0; c < 2; c++) {
                int idx = i + c;
                if (idx >= items.length) continue;
                LinearLayout btn = new LinearLayout(this);
                btn.setOrientation(LinearLayout.HORIZONTAL);
                btn.setGravity(android.view.Gravity.CENTER_VERTICAL);
                btn.setBackground(btnBg());
                btn.setPadding(dp(14), dp(12), dp(14), dp(12));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (c == 0) blp.rightMargin = dp(10);
                btn.setLayoutParams(blp);
                TextView ic = new TextView(this);
                ic.setText(items[idx][0]);
                ic.setTextSize(18f);
                btn.addView(ic);
                TextView lb = new TextView(this);
                lb.setText(items[idx][1]);
                lb.setTextSize(14f);
                lb.setTextColor(theme.text());
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                tlp.leftMargin = dp(10);
                btn.addView(lb, tlp);
                btn.setOnClickListener(listeners[idx]);
                addPressAnim(btn);
                row.addView(btn);
            }
            root.addView(row, rowLp);
        }
        return root;
    }

    private void refreshStats() {
        int users = 0, msgs = 0, summaryChars = 0;
        for (ConversationEngine.Conv c : ConversationEngine.get(this).allConvs()) {
            users++;
            msgs += c.history.length();
            summaryChars += c.summary == null ? 0 : c.summary.length();
        }
        tvStatRounds.setText("👥 用户数 " + users + "    💬 窗口消息 " + msgs + " 条");
        tvStatSummary.setText("🧠 记忆摘要合计 " + summaryChars + " 字"
                + (summaryChars > 0 ? "（「记忆管理」可查看/清空）" : "（暂无，超过阈值自动生成）"));
    }

    private String agoText(long t) {
        if (t == 0) return "—";
        long sec = (System.currentTimeMillis() - t) / 1000;
        if (sec < 60) return sec + "秒前";
        if (sec < 3600) return (sec / 60) + "分前";
        return (sec / 3600) + "时前";
    }

    private void runDiagnostics() {
        DeepSeekController.get().probe();
        StringBuilder sb = new StringBuilder();
        boolean hasToken = prefs().getString("ilink_token", null) != null;
        sb.append(hasToken ? "✅" : "❌").append(" 微信连接（bot_token）\n");
        if (hasToken) {
            sb.append(BotService.pollAlive ? "✅" : "❌").append(" 长轮询线程");
            sb.append(BotService.pollAlive ? "（运行中，上次心跳 " + agoText(BotService.lastPollOk) + "）" : "（已停止，请重启 App 或重新连接微信）");
            sb.append('\n');
        }
        sb.append(DeepSeekController.get().isAttached() ? "✅" : "❌").append(" DeepSeek 页面宿主（WebView）\n");
        sb.append(dsLoggedIn ? "✅" : "❌").append(" DeepSeek 登录状态").append(dsLoggedIn ? "" : "（请切到「对话页」登录）").append('\n');
        sb.append(dsInputReady ? "✅" : "⏳").append(" 输入框就绪").append(dsInputReady ? "" : "（页面可能还在加载）").append('\n');
        sb.append(isIgnoringBattery() ? "✅" : "⚠️").append(" 电池优化白名单").append(isIgnoringBattery() ? "" : "（未开启，后台可能被冻结）").append('\n');
        sb.append("文件传输: ").append(ConversationEngine.get(this).fileTransfer() ? "开启" : "关闭").append('\n');
        sb.append("\n收到 ").append(BotService.msgReceived).append(" 条 / 回复 ").append(BotService.msgReplied).append(" 条\n");
        if (!BotService.lastError.isEmpty()) sb.append("最近错误：").append(BotService.lastError);
        showText("自检诊断", sb.toString());
    }

    private void setWechatState(String s) {
        tvWechat.setText("微信  " + s);
        boolean ok = s.contains("已连接");
        int color = ok ? theme.green() : ((s.contains("过期") || s.contains("失败")) ? theme.red() : theme.amber());
        tvDotWechat.setBackground(oval(color));
        tvDotWechat.clearAnimation();
        if (ok) breathe(tvDotWechat);
    }

    private void setDsState(String s, boolean ok) {
        tvDeepseek.setText("DeepSeek  " + s);
        tvDotDs.setBackground(oval(ok ? theme.green() : theme.amber()));
        tvDotDs.clearAnimation();
        if (ok) breathe(tvDotDs);
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setUserAgentString(DESKTOP_UA);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "DSB");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Util.log("页面加载完成: " + url);
                DeepSeekController.get().injectBridge();
                handler.postDelayed(probeTask, 1200L);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(0);
        webView.loadUrl(DS_URL);
    }

    private void loadBridgeJs() {
        try (InputStream in = getAssets().open("bridge.js");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            bridgeJs = bos.toString("UTF-8");
            DeepSeekController.get().attach(webView, bridgeJs, this::onDsStatus);
        } catch (Exception e) {
            Util.log("加载 bridge.js 失败: " + e.getMessage());
            Toast.makeText(this, "桥接脚本加载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void onDsStatus(String s) {
        try {
            JSONObject o = new JSONObject(s);
            String type = o.optString("type");
            if ("probe".equals(type) || "boot".equals(type) || "pageReply".equals(type)) {
                if ("pageReply".equals(type)) {
                    Util.log("页面手动回复 len=" + o.optString("content").length()
                            + (o.optBoolean("recalled") ? " [撤回已拦截]" : ""));
                }
            }
            dsLoggedIn = o.optBoolean("loggedIn", false);
            dsInputReady = o.optBoolean("hasInput", dsInputReady);
            updateDsStatus();
        } catch (Exception ignored) {}
    }

    private void updateDsStatus() {
        if (!dsLoggedIn) setDsState("未登录（请到「对话页」登录）", false);
        else if (dsInputReady) setDsState("就绪 ✓（模式与开关请在对话页手动选择）", true);
        else setDsState("已登录，等待输入框…", false);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onEvent(String json) { DeepSeekController.get().onJsEvent(json); }
    }

    // ---------------- 微信扫码连接 ----------------

    private void showQrDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(8));
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new LinearLayout.LayoutParams(dp(300), dp(300)));
        final TextView tip = new TextView(this);
        tip.setTextSize(13f);
        tip.setTextColor(theme.textSub());
        tip.setGravity(android.view.Gravity.CENTER);
        tip.setText("正在获取二维码…");
        box.addView(tip);
        final AlertDialog dialog = dialogBuilder().setTitle("微信 ClawBot 扫码连接").setView(box)
                .setNegativeButton("取消", (d, w) -> { loginCancelled = true; d.dismiss(); }).create();
        dialog.show();
        loginCancelled = false;
        bg.submit(() -> runLoginFlow(iv, tip, dialog));
    }

    private void runLoginFlow(final ImageView iv, final TextView tip, final AlertDialog dialog) {
        int rounds = 0;
        while (!loginCancelled && rounds < 4) {
            String base = ILinkClient.DEFAULT_BASE;
            JSONObject qr = ILinkClient.getQrCode(ILinkClient.DEFAULT_BASE);
            String content = qr.optString("qrcode_img_content", "");
            String qrcode = qr.optString("qrcode", "");
            if (content.isEmpty() || qrcode.isEmpty()) {
                postTip(tip, "获取二维码失败: " + qr.optString("__error", "empty") + "，重试中…");
                sleep(3000);
                continue;
            }
            final Bitmap bmp = renderQr(content);
            if (bmp == null) {
                postTip(tip, "二维码渲染失败，重试中…");
                sleep(3000);
                continue;
            }
            runOnUiThread(() -> {
                iv.setImageBitmap(bmp);
                tip.setText("请用微信「扫一扫」扫描此二维码\n并在手机上确认授权");
            });
            rounds++;
            long expireAt = System.currentTimeMillis() + 110000;
            while (!loginCancelled && System.currentTimeMillis() < expireAt) {
                JSONObject st = ILinkClient.pollQrStatus(ILinkClient.DEFAULT_BASE, qrcode);
                if (st.has("__error")) { sleep(2500); continue; }
                String botToken = st.optString("bot_token", "");
                if (!botToken.isEmpty()) {
                    String baseurl = st.optString("baseurl", "");
                    if (!baseurl.isEmpty()) base = baseurl;
                    prefs().edit()
                            .putString("ilink_token", botToken)
                            .putString("ilink_baseurl", base)
                            .putString("ilink_cursor", "")
                            .putString("ilink_bot_id", st.optString("ilink_bot_id", ""))
                            .apply();
                    Util.log("微信扫码连接成功，token 已持久化（免重复扫码）");
                    runOnUiThread(() -> {
                        if (dialog.isShowing()) dialog.dismiss();
                        setWechatState("已连接");
                        Toast.makeText(this, "微信连接成功，后续无需重复扫码", Toast.LENGTH_SHORT).show();
                        BotService.start(this);
                    });
                    return;
                }
                String status = st.optString("status", "");
                if ("expired".equals(status)) { postTip(tip, "二维码已过期，自动刷新…"); break; }
                else if ("scaned".equals(status)) postTip(tip, "已扫码，请在手机上点击确认…");
            }
        }
        if (!loginCancelled) postTip(tip, "连接超时，请重新点击「连接微信」");
    }

    private void postTip(final TextView t, final String s) {
        runOnUiThread(() -> t.setText(s));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private Bitmap renderQr(String content) {
        try {
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 720, 720, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Util.log("QR 渲染失败: " + e.getMessage());
            return null;
        }
    }

    private void disconnectWechat() {
        prefs().edit().remove("ilink_token").remove("ilink_cursor").apply();
        BotService.stop(this);
        setWechatState("未连接");
        Toast.makeText(this, "已断开微信连接", Toast.LENGTH_SHORT).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- 记忆压缩 ----------------

    private void manualCompress() {
        if (prefs().getString("ilink_token", null) == null) {
            toast("尚未连接微信，无对话可压缩");
            return;
        }
        dialogBuilder().setTitle("手动压缩记忆")
                .setMessage("将立即对所有用户的对话历史执行记忆压缩（压缩提示词通过当前网页发送给 DeepSeek，期间请勿操作对话页）。继续？")
                .setPositiveButton("开始", (d, w) -> {
                    toast("压缩已开始，完成后自动通知");
                    bg.submit(this::runManualCompress);
                })
                .setNegativeButton("取消", null).show();
    }

    private void runManualCompress() {
        final int[] count = {0};
        for (ConversationEngine.Conv c : ConversationEngine.get(this).allConvs()) {
            if (c.history.length() != 0 || (c.summary != null && !c.summary.isEmpty())) {
                Util.log("手动压缩(" + maskUid(c.userId) + "): " + BotService.compressConv(this, c));
                count[0]++;
            }
        }
        Util.log("手动压缩完成，共处理 " + count[0] + " 个会话");
        runOnUiThread(() -> { toast("压缩完成，处理 " + count[0] + " 个会话"); refreshStats(); });
    }

    private void newChatFromUi() {
        toast("正在新建对话…");
        bg.submit(() -> {
            final JSONObject r = DeepSeekController.get().newChat();
            Util.log("手动新建对话: " + (r.optBoolean("ok") ? "成功" : "失败:" + r.optString("error")));
            runOnUiThread(() -> {
                toast(r.optBoolean("ok") ? "已新建对话（请切到对话页确认）" : "新建失败，请切到对话页手动新建");
                switchTab(TAB_WEB);
            });
        });
    }

    private String maskUid(String uid) {
        if (uid == null || uid.length() <= 10) return uid;
        return uid.substring(0, 6) + "…" + uid.substring(uid.length() - 6);
    }

    // ---------------- 记忆管理 ----------------

    private void showMemoryManager() {
        final List<ConversationEngine.Conv> all = ConversationEngine.get(this).allConvs();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(box);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button clearAll = smallBtn("🗑 清空全部上下文");
        clearAll.setOnClickListener(v -> confirmClear(null));
        top.addView(clearAll, new LinearLayout.LayoutParams(0, -2, 1f));
        Button preview = smallBtn("👁 Prompt 预览");
        preview.setOnClickListener(v -> showPromptPreview());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
        plp.leftMargin = dp(8);
        top.addView(preview, plp);
        box.addView(top);
        box.addView(spacer(dp(8)));
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无会话记录\n（连接微信并开始对话后，这里会显示每个用户的记忆状态）");
            empty.setTextSize(13f);
            empty.setTextColor(theme.textSub());
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            box.addView(empty);
        }
        for (final ConversationEngine.Conv conv : all) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(roundedBg(theme.btnBg(), 14));
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
            ilp.topMargin = dp(8);
            item.setLayoutParams(ilp);
            TextView name = new TextView(this);
            name.setText("👤 " + maskUid(conv.userId));
            name.setTextSize(13f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(theme.text());
            item.addView(name);
            TextView meta = new TextView(this);
            meta.setText("轮数 " + conv.totalRounds + " · 窗口 " + conv.history.length() + " 条 · 摘要 "
                    + ((conv.summary == null || conv.summary.isEmpty()) ? "无" : conv.summary.length() + " 字"));
            meta.setTextSize(12f);
            meta.setTextColor(theme.textSub());
            meta.setPadding(0, dp(2), 0, dp(6));
            item.addView(meta);
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            Button b1 = smallBtn("压缩");
            b1.setOnClickListener(v -> {
                toast("压缩中…");
                bg.submit(() -> {
                    final String r = BotService.compressConv(this, conv);
                    Util.log("记忆管理-压缩(" + maskUid(conv.userId) + "): " + r);
                    runOnUiThread(() -> { toast(r); refreshStats(); });
                });
            });
            Button b2 = smallBtn("摘要");
            b2.setOnClickListener(v -> showText("记忆摘要 · " + maskUid(conv.userId),
                    (conv.summary == null || conv.summary.isEmpty()) ? "（暂无摘要，触发压缩后生成）" : conv.summary));
            Button b3 = smallBtn("清空");
            b3.setOnClickListener(v -> confirmClear(conv));
            LinearLayout.LayoutParams opLp = new LinearLayout.LayoutParams(0, -2, 1f);
            opLp.rightMargin = dp(6);
            ops.addView(b1, opLp);
            ops.addView(b2, opLp);
            ops.addView(b3, new LinearLayout.LayoutParams(0, -2, 1f));
            item.addView(ops);
            box.addView(item);
        }
        dialogBuilder().setTitle("记忆管理").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private Button smallBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(theme.text());
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinimumHeight(dp(34));
        b.setBackground(btnBg());
        addPressAnim(b);
        return b;
    }

    private void confirmClear(final ConversationEngine.Conv conv) {
        final ConversationEngine engine = ConversationEngine.get(this);
        String msg = conv == null
                ? "将清空【所有用户】的本地对话上下文与记忆摘要（不影响微信连接与 DeepSeek 账号）。确定？"
                : "将清空该用户的本地对话上下文与记忆摘要。确定？";
        dialogBuilder().setTitle("清空本地上下文").setMessage(msg)
                .setPositiveButton("清空", (d, w) -> {
                    if (conv == null) {
                        String r = engine.clearAll();
                        Util.log("清空全部: " + r);
                        toast(r);
                    } else {
                        engine.reset(conv);
                        Util.log("清空用户上下文: " + maskUid(conv.userId));
                        toast("已清空该用户上下文");
                    }
                    refreshStats();
                })
                .setNegativeButton("取消", null).show();
    }

    private void showPromptPreview() { showText("下一次发送的 Prompt（预览）", ConversationEngine.get(this).previewPrompt()); }

    private void showText(String title, String body) {
        TextView t = new TextView(this);
        t.setTextSize(12f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(theme.text());
        t.setText(body);
        t.setTextIsSelectable(true);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        dialogBuilder().setTitle(title).setView(scroll).setPositiveButton("关闭", null).show();
    }

    // ---------------- 设置 ----------------

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), 0);
        final EditText persona = new EditText(this);
        persona.setText(prefs().getString("persona", ConversationEngine.get(this).persona()));
        persona.setMinLines(4);
        persona.setGravity(android.view.Gravity.TOP);
        persona.setTextSize(13f);
        box.addView(label("角色人设（系统提示词）"));
        box.addView(persona);
        final EditText ctxRounds = numberField(String.valueOf(prefs().getInt("ctx_rounds", 8)));
        box.addView(label("上下文窗口轮数（默认 8）"));
        box.addView(ctxRounds);
        final EditText compressRounds = numberField(String.valueOf(prefs().getInt("compress_rounds", 12)));
        box.addView(label("自动记忆压缩阈值轮数（默认 12）"));
        box.addView(compressRounds);
        final EditText rotateRounds = numberField(String.valueOf(prefs().getInt("rotate_rounds", 100)));
        box.addView(label("DeepSeek 会话轮换阈值轮数（默认 100）"));
        box.addView(rotateRounds);
        final EditText summaryMax = numberField(String.valueOf(prefs().getInt("summary_max", 3000)));
        box.addView(label("记忆摘要上限字数（自适应压缩，硬上限，默认 3000）"));
        box.addView(summaryMax);
        final CheckBox battery = new CheckBox(this);
        battery.setText("电池优化白名单" + (isIgnoringBattery() ? "（已开启 ✓）" : "（后台保活，强烈建议开启）"));
        battery.setChecked(isIgnoringBattery());
        battery.setTextSize(13f);
        battery.setOnCheckedChangeListener((b, checked) -> {
            if (checked) requestBatteryWhitelist();
            battery.setText("电池优化白名单" + (isIgnoringBattery() ? "（已开启 ✓）" : "（后台保活，强烈建议开启）"));
        });
        box.addView(battery);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        dialogBuilder().setTitle("更多设置").setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit()
                            .putString("persona", persona.getText().toString().trim())
                            .putInt("ctx_rounds", parseInt(ctxRounds, 8))
                            .putInt("compress_rounds", parseInt(compressRounds, 12))
                            .putInt("rotate_rounds", parseInt(rotateRounds, 100))
                            .putInt("summary_max", Math.max(300, Math.min(3000, parseInt(summaryMax, 3000))))
                            .apply();
                    toast("已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    private EditText numberField(String val) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(val);
        return e;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(theme.textSub());
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private void showLog() {
        TextView t = new TextView(this);
        t.setTextSize(11f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(theme.text());
        t.setText(Util.logText());
        t.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        dialogBuilder().setTitle("运行日志").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void showHelp() {
        String text = "【首次使用】\n" +
                "1. 切到「对话页」登录 DeepSeek，手动选好模式与 DeepThink、联网开关\n" +
                "2. 回到控制台点「连接微信」，扫码授权 ClawBot\n" +
                "3. 在微信里找 ClawBot 联系人直接聊天，也可直接发图片/文件\n\n" +
                "【图片/文件传输】\n" +
                "在微信里给 ClawBot 发送图片或文件（PDF/Word/Excel/TXT/代码/图片等），App 会自动从微信下载解密、注入 DeepSeek 官网输入区并随你的文字一起发送；可配一句问题，如「总结一下这个文件」。/文件开|关 可控制总开关。\n\n" +
                "【收不到回复怎么排查】\n" +
                "1. 看控制台「运行状态」：轮询是否运行、上次收到/回复时间\n" +
                "2. 点「自检诊断」逐项检查（令牌/轮询/登录/输入框/电池白名单）\n" +
                "3. 看运行日志，微信发 /状态 查看统计\n\n" +
                "【工作原理】\n" +
                "微信消息（含附件下载解密）→ 本地构造 Prompt（人设+时间+记忆摘要+上下文窗口+多条规则）→ 附件注入官网文件框、文本自动填入并发送 → 截取回复（思考过滤、撤回拦截）→ 拆分多条 → 发回微信\n\n" +
                "【多条消息】\n" +
                "快捷开关（或微信 /多条开|关）。AI 用单个反斜杠 \\ 分隔多条回复，App 依次发送；反斜杠后跟小写字母（如 LaTeX \\frac）不会被误拆。\n\n" +
                "【长期记忆】\n" +
                "超过阈值自动把更早的对话压缩成摘要（篇幅按信息量自适应，上限可在「更多设置」调整，默认最多 3000 字）；可手动压缩、查看、清空，达到轮数阈值自动 New chat 轮换并保留摘要。\n\n" +
                "【后台保活】\n电池白名单 + 前台服务 + WakeLock + 屏幕常亮；返回键退到后台不会关闭桥接。\n\n" +
                "【微信指令】\n/帮助 /状态 /压缩 /重置 /人设 新人设 /多条开 /多条关 /文件开 /文件关";
        TextView t = new TextView(this);
        t.setTextSize(13f);
        t.setTextColor(theme.text());
        t.setText(text);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        dialogBuilder().setTitle("使用说明").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    private boolean isIgnoringBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < 23 || isIgnoringBattery()) return;
        try {
            try {
                startActivity(new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
            }
        } catch (Exception ignored) {}
    }
}
