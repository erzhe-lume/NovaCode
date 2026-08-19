package com.novacode.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令注册中心（第 10 章 spec F1/F6）。
 *
 * <p>启动期检测名称或别名冲突（大小写不敏感），冲突即抛未检查异常上抛至入口
 * 的全局异常处理退出进程（spec F1/N3），不延迟到运行时。</p>
 */
public class CommandRegistry {

    private final Map<String, CommandSpec> byName = new LinkedHashMap<>();
    private final List<CommandSpec> order = new ArrayList<>();

    public void register(CommandSpec spec) {
        Set<String> seen = new HashSet<>();
        for (String key : allKeys(spec)) {
            String k = norm(key);
            if (byName.containsKey(k) || !seen.add(k)) {
                throw new IllegalStateException("命令别名冲突: '" + key + "'（大小写不敏感）");
            }
        }
        for (String key : allKeys(spec)) {
            byName.put(norm(key), spec);
        }
        order.add(spec);
    }

    /** 按名查找（小写）；未命中返回 null。 */
    public CommandSpec lookup(String name) {
        return name == null ? null : byName.get(norm(name));
    }

    /** 非隐藏命令，按注册顺序。 */
    public List<CommandSpec> visible() {
        return order.stream().filter(s -> !s.hidden()).toList();
    }

    /** 前缀补全候选（小写、排除隐藏、去重排序）。 */
    public List<String> complete(String prefix) {
        String p = prefix == null ? "" : norm(prefix);
        return byName.keySet().stream()
                .filter(k -> !byName.get(k).hidden())
                .filter(k -> k.startsWith(p))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> allKeys(CommandSpec spec) {
        var keys = new ArrayList<String>();
        keys.add(spec.name());
        keys.addAll(spec.aliases());
        return keys;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
