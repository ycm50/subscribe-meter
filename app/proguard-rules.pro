# ── kotlinx.serialization ────────────────────────────────────────────────────
# R8 full mode 会剥掉未直接引用的序列化器,这里按官方建议保留生成物。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 序列化器由编译器插件生成,靠 Companion.serializer() 反射取得,必须保留。
-keepclassmembers class com.ycm50.subscribemeter.** {
    *** Companion;
}
-keepclasseswithmembers class com.ycm50.subscribemeter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ycm50.subscribemeter.**$$serializer {
    *;
}

# 数据类字段名参与 JSON 键名推导,保留字段与构造器签名。
-keepclassmembers,allowobfuscation class com.ycm50.subscribemeter.data.** {
    <fields>;
    <init>(...);
}
-keep class com.ycm50.subscribemeter.data.AppConfig { *; }
-keep class com.ycm50.subscribemeter.data.ProviderConfig { *; }
-keep class com.ycm50.subscribemeter.data.SnapshotRecord { *; }
-keep class com.ycm50.subscribemeter.data.WindowRecord { *; }
-keep class com.ycm50.subscribemeter.data.SnapshotFile { *; }
-keep class com.ycm50.subscribemeter.data.SecretEntry { *; }
-keep class com.ycm50.subscribemeter.data.SecretFile { *; }
-keep class com.ycm50.subscribemeter.data.ProviderListOnly { *; }

# ── 通用 ────────────────────────────────────────────────────────────────────
# 行号保留,崩溃栈仍可读(不保留源文件名,避免泄露本机路径)。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
