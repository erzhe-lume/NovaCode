package com.novacode.hook;

import java.util.Map;

/**
 * Hook 执行上下文（第 12 章 spec F3）。携带当前事件信息 + 模板变量替换。
 */
public record HookContext(HookEvent event, String toolName, Map<String, Object> toolArgs,
                          String filePath, String message, String error) {

    /** 从工具参数中提取文件路径：file 类工具用 file_path，Glob/Grep 用 path。 */
    public static String filePathOf(Map<String, Object> args) {
        if (args == null) return null;
        Object v = args.get("file_path");
        if (v == null) v = args.get("path");
        return v != null ? String.valueOf(v) : null;
    }

    /** 模板变量替换：${event} ${tool} ${file_path} ${message} ${error} ${args.xxx}。 */
    public String expand(String template) {
        if (template == null || !template.contains("${")) return template;
        String result = template;
        result = result.replace("${event}", event != null ? event.value() : "");
        result = result.replace("${tool}", toolName != null ? toolName : "");
        result = result.replace("${file_path}", filePath != null ? filePath : "");
        result = result.replace("${message}", message != null ? message : "");
        result = result.replace("${error}", error != null ? error : "");
        if (toolArgs != null) {
            for (var entry : toolArgs.entrySet()) {
                String placeholder = "${args." + entry.getKey() + "}";
                if (result.contains(placeholder)) {
                    result = result.replace(placeholder,
                            entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                }
            }
        }
        return result;
    }
}
