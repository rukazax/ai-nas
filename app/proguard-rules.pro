# Минификация отключена (isMinifyEnabled=false), правила на будущее.
-keep class jcifs.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class com.github.sardine.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn jcifs.**
-dontwarn net.schmizz.**
-dontwarn com.github.sardine.**
-keepclassmembers class * extends java.lang.Enum { *; }
