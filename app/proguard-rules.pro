# Default Kotlin/Compose rules are sufficient for this app.

# protobuf-lite 生成类靠反射按名字读写 xxx_ 字段（bitField0_、name_、packageName_…）。
# protobuf-javalite 4.36.1 不再自带该规则、firebase-perf 也未提供，若不显式保留，
# R8 会把字段名混淆成单字母，Firebase Performance 编码指标时抛 NoSuchFieldException，
# 在 AppStartTrace.onAppEnteredForeground 回调里崩掉整个进程。
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
