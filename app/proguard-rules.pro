-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-keepclassmembers class com.smokelog.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
