package com.novacode.subagent;

import com.novacode.tool.FileStateCache;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.impl.BashTool;
import com.novacode.tool.impl.EditFileTool;
import com.novacode.tool.impl.GlobTool;
import com.novacode.tool.impl.GrepTool;
import com.novacode.tool.impl.ReadFileTool;
import com.novacode.tool.impl.WriteFileTool;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 子 Agent 工具过滤（第 13 章 F7）。多层防线，按序应用：
 * <ul>
 *   <li>第 1 层：全局禁止（{@link #ALWAYS_DISALLOWED}，防无限嵌套 / 越权）。</li>
 *   <li>第 2 层：角色黑名单（{@code spec.disallowedTools}）。</li>
 *   <li>第 3 层：后台白名单（仅 isAsync 时，{@link #BACKGROUND_ALLOWED}）。</li>
 *   <li>第 4 层：角色白名单（{@code spec.tools} 交集，空/仅 "*" 时不收窄）。</li>
 * </ul>
 * 定义式子 Agent 的内置六工具重建<strong>全新实例 + 全新 FileStateCache</strong>（隔离
 * 「先读后写」状态）；其余工具（MCP / Agent / use_skill）复用实例。Fork 式子 Agent 用
 * {@link #cloneForFork} 复用全部实例，保 schema 字节一致以命中 prompt cache。
 */
public final class ToolFilter {

    /** 第 15 章：协作工具。普通子 Agent / fork 一律拿不到；队员由
     *  {@link com.novacode.teams.TeammateRunner#buildTeammateRegistry} 显式补回
     *  SendMessage + Task*（TeamCreate/TeamDelete/TaskStop 仍不给队员）。 */
    public static final Set<String> COLLAB_TOOLS = Set.of(
            "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "TeamCreate", "TeamDelete", "TaskStop");

    /** 所有子 Agent 一律拿不到的工具（含协作工具）。 */
    private static final Set<String> ALWAYS_DISALLOWED = buildAlwaysDisallowed();

    /** 后台执行的子 Agent 额外受限的工具白名单。 */
    private static final Set<String> BACKGROUND_ALLOWED = Set.of(
            "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep");

    private ToolFilter() {}

    private static Set<String> buildAlwaysDisallowed() {
        Set<String> s = new HashSet<>(Set.of("Agent", "use_skill"));
        s.addAll(COLLAB_TOOLS);
        return s;
    }

    /** 定义式子 Agent 过滤（非后台）。 */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, null);
    }

    /** 按 spec 过滤，isAsync=true 时再叠加后台白名单。 */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec, boolean isAsync) {
        return filterForAgent(source, spec, isAsync, null);
    }

    /** 按 spec 过滤，isAsync=true 时再叠加后台白名单；cwd 非空时注入到重建的内置工具实例（第 14 章 F5）。 */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec, boolean isAsync, Path cwd) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());
        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        FileStateCache freshCache = new FileStateCache();
        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();
            if (ALWAYS_DISALLOWED.contains(name)) continue;
            if (disallowed.contains(name)) continue;
            if (isAsync && !BACKGROUND_ALLOWED.contains(name)) continue;
            if (hasWhitelist && !allowed.contains(name)) continue;
            filtered.register(freshBuiltin(tool, freshCache, cwd));
        }
        return filtered;
    }

    /** 队员基础工具集：按通用角色过滤（协作工具已被 ALWAYS_DISALLOWED 挡掉），
     *  再由 TeammateRunner 显式补回 SendMessage + Task*。 */
    public static ToolRegistry filterForTeammate(ToolRegistry source, Path cwd) {
        return filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE, false, cwd);
    }

    /** Fork 专用：复制父注册表全部工具（不过滤），遇到 AgentTool 标记 querySource 拦截嵌套 fork；
     *  协作工具剔除（普通子 Agent 不得参与团队）。 */
    public static ToolRegistry cloneForFork(ToolRegistry source) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            if (COLLAB_TOOLS.contains(tool.name())) continue;
            if (tool instanceof AgentTool at) {
                forked.register(at.cloneWithQuerySource("agent:builtin:fork"));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }

    /** 内置六工具重建全新实例（共享同一份全新 FileStateCache），并注入 cwd；其余复用。 */
    private static Tool freshBuiltin(Tool tool, FileStateCache cache, Path cwd) {
        if (tool instanceof ReadFileTool) {
            var n = new ReadFileTool(); n.setFileStateCache(cache); if (cwd != null) n.setCwd(cwd); return n;
        }
        if (tool instanceof WriteFileTool) {
            var n = new WriteFileTool(); n.setFileStateCache(cache); if (cwd != null) n.setCwd(cwd); return n;
        }
        if (tool instanceof EditFileTool) {
            var n = new EditFileTool(); n.setFileStateCache(cache); if (cwd != null) n.setCwd(cwd); return n;
        }
        if (tool instanceof BashTool) { var n = new BashTool(); if (cwd != null) n.setCwd(cwd); return n; }
        if (tool instanceof GlobTool) { var n = new GlobTool(); if (cwd != null) n.setCwd(cwd); return n; }
        if (tool instanceof GrepTool) { var n = new GrepTool(); if (cwd != null) n.setCwd(cwd); return n; }
        return tool;
    }
}
