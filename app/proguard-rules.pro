# Keep the JavascriptInterface bridge intact for WebView
-keepclassmembers class com.projectorguy.app.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.projectorguy.app.AndroidBridge { *; }
