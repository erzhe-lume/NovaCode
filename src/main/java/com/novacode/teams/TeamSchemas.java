package com.novacode.teams;

import java.util.Map;

/**
 * 团队工具 schema 包装（第 15 章）：与标准工具一致，{@code schema()} 返回
 * {@code {name, description, input_schema}} 完整形态。团队工具把输入 schema 单独构造
 * 后经此包装——漏包装会让 {@link com.novacode.tool.ToolRegistry#getAllSchemas} 在 openai
 * 协议下 {@code Map.of} 收到 null 值而 NPE。
 */
final class TeamSchemas {
    private TeamSchemas() {}

    /** 包装输入 schema 为完整工具 schema。 */
    static Map<String, Object> wrap(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "input_schema", inputSchema);
    }
}
