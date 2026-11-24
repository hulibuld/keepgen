# R8 混淆规则
#
# 目的不是「让人破不了」，是「让 AI 挂后台半小时搞不定」。
# 经验丰富的人照样能破，这是客户端应用的本质限制。
#
# 混淆后能达成的效果：
#   · 类名 / 方法名 / 字段名全部变成 a、b、c
#   · strings classes.dex 扫不到 Lcom/keepgen/hulibuld/License;
#   · AI 读代码时看到的是一堆无意义符号，得先花时间还原语义

# ---- 四大组件（按名字从 manifest 引用，不能改）----
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgent
-keep public class * extends android.accessibilityservice.AccessibilityService

# ---- 自定义 View（XML 里按类名引用）----
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---- 主 Activity 和入口（内部符号照常混淆）----
# v22：只锁类名 + 构造器，成员照常混淆。
#
# 原来写的是 { *; }，把 MainActivity 的 2000 多行成员全部保留原名，
# strings classes.dex | grep doGenerate 一步就能定位到闸门 —— 混淆收益被吃掉一半。
#
# 必须继续用 -keep class <全限定名>，不能用 -keepclassmembers：
# 下面有 -repackageclasses ''，一旦类名也被允许改名，R8 会把 MainActivity
# 搬出 com.keepgen.hulibuld，manifest 里的 .MainActivity 解析不到，
# 装上点开就是 ClassNotFoundException。
-keep class com.keepgen.hulibuld.MainActivity { <init>(...); }
-keep class com.keepgen.hulibuld.KeepAuto { <init>(...); }
-keep class com.keepgen.hulibuld.ApkProvider { <init>(...); }

# ---- 常量类（避免被当成无用代码删掉）----
-keep class com.keepgen.hulibuld.BuildConfig { *; }

# ---- Android 框架回调 ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---- 保留必要属性 ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ---- 行号保留（崩溃栈还能看），但去掉源文件名 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 混淆强度 ----
# 允许混淆到默认包
-repackageclasses ''
# 允许更激进的优化
-allowaccessmodification
# 不保留任何调试信息
-dontwarn **

# ---- 显式去掉日志（release 包里不该有）----
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
