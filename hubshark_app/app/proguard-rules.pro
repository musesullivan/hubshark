# ProGuard rules for HubShark Client
-keep class com.hubshark.client.network.** { *; }
-keep class libXray.** { *; }
-keep class libxray.** { *; }
-keep class com.hubshark.client.vpn.HevTunController { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Optional compile-time annotations and alternative desktop TLS providers are
# referenced by dependencies but are not packaged or selected on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
