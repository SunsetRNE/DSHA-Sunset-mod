package com.deepseekharness.app.util;

/**
 * 日志脱敏：把会泄露到 logcat / 活动日志的敏感值打码。
 * 骨架版只覆盖最关键的 API key 环境变量形态；完整版按原 SensitiveData 回填（含备份路径等）。
 */
public final class SensitiveData {

    private SensitiveData() {
    }

    public static String redact(String s) {
        if (s == null) return null;
        return s.replaceAll("(?i)(DEEPSEEK_API_KEY=)[^\\s&;'\"<>]+", "$1***");
    }
}
