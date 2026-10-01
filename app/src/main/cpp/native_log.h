// Komiho (2026-10-01): 原生诊断日志环。
//
// 目的：让「设置-高级-导出诊断日志」在**无法使用 adb 的设备**（鸿蒙等）上也能拿到
// Waifu2xNative / Waifu2xJNI 的输出。
//
// 为什么需要它：App 侧的 exh.log.DiagnosticLogBuffer 是**进程内**内存缓冲，挂在 Kotlin 的
// logcat() 上；原生日志走 __android_log_print 只进系统 logcat，缓冲读不到它。于是导出文件里
// 恰好缺掉了判断批次调度（Fused Vulkan scheduling / adaptive batch）与纯耗时（processing
// completed in N ms）的那几行。
//
// 做法：原生日志同时写一份到本进程的环里，由 Kotlin 侧 Waifu2x.nativeLogs() 取走，和
// android.util.Log / logcat() 两个来源按 epoch 毫秒合并排序。
//
// 每行格式（与 Kotlin 侧 DiagnosticLogBuffer.getLogsMerged 的解析约定一致，改一处必须同步另一处）：
//   <epochMillis>|<V/D/I/W/E>|<tag>|<message>
#pragma once

#include <string>

/**
 * 同时写 logcat 与进程内环。
 *
 * [android_priority] 取 android/log.h 的 ANDROID_LOG_* 常量（决定 logcat 级别与输出里的单字母）。
 * 线程安全；消息里的换行会被替换成空格（环是**按行**格式化的，绝不能带 '\n'）。
 */
void mihonsy_native_log(int android_priority, const char *tag, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

/** 当前环内容的快照，每行一条（格式见文件头）。 */
std::string mihonsy_native_log_dump();
