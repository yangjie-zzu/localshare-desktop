# Don't touch third party libraries
-dontwarn !com.freefjay.localshare.**
-keep class !com.freefjay.localshare.** { *; }
-keep class com.freefjay.localshare.desktop.model.** { *; }
-optimizations !library/gson