# Правила ProGuard (не используются для debug-сборки)
# Samsung Remote использует org.json (встроен в Android) и OkHttp — особых правил не требуется.
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
