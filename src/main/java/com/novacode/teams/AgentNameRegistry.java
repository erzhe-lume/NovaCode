package com.novacode.teams;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 全局队员名注册表（第 15 章 F4 两段式投递的第一段）。
 *
 * <p>把队员名解析成邮箱投递标识；队员加入团队时 {@link TeamManager.Team#addMember}
 * 自动登记（身份映射 name→name），外部进程/别名可覆盖。进程内单例，线程安全。</p>
 */
public final class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    private final ConcurrentMap<String, String> addresses = new ConcurrentHashMap<>();

    private AgentNameRegistry() {}

    public static AgentNameRegistry getInstance() { return INSTANCE; }

    /** 登记名字→投递标识（覆盖旧值）。 */
    public void register(String name, String address) {
        if (name == null || name.isBlank()) return;
        addresses.put(name, address);
    }

    /** 解析名字到投递标识；未登记返回 null。 */
    public String resolve(String name) {
        return name == null ? null : addresses.get(name);
    }

    /** 注销名字。 */
    public void unregister(String name) {
        if (name != null) addresses.remove(name);
    }
}
