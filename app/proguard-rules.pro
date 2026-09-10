# 默认不混淆（minifyEnabled=false）。保留 JavascriptInterface 入口以防未来开启混淆。
-keepclassmembers class com.hiweny.deepbridge.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.google.zxing.** { *; }
