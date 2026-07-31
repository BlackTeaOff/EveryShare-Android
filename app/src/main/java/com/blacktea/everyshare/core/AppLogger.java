package com.blacktea.everyshare.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppLogger {
    private static final Logger log = LoggerFactory.getLogger(AppLogger.class);

    // 线程安全的全局日志容器
    public static final List<String> logs = new CopyOnWriteArrayList<>();
    public static Runnable onLogAdded;

    public static void info(String format, Object... arguments) {
        log.info(format, arguments);
        String formatted = format;
        for (Object arg : arguments) {
            // 💡 核心修复：增加非空防空指针机制，如果是 null 替换为 "null" 字符串
            String argStr = (arg == null) ? "null" : arg.toString();
            formatted = formatted.replaceFirst("\\{\\}", java.util.regex.Matcher.quoteReplacement(argStr));
        }
        append("[INFO] " + formatted);
    }

    public static void error(String msg, Throwable e) {
        log.error(msg, e);
        append("[ERROR] " + msg + (e != null ? ": " + e.getMessage() : ""));
    }

    private static void append(String msg) {
        logs.add(msg);
        if (logs.size() > 100) {
            logs.remove(0);
        }
        if (onLogAdded != null) {
            onLogAdded.run();
        }
    }
}