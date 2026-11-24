# WebMessageListener + injected client JS; no JavascriptInterface keeps required.

# DeftNavigation opens Custom Tabs through reflection (Class.forName / getMethod).
-keep class androidx.browser.customtabs.CustomTabsIntent { public *; }
-keep class androidx.browser.customtabs.CustomTabsIntent$Builder { public *; }
