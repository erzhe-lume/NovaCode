package com.novacode.teams;

import com.novacode.agent.Agent;
import com.novacode.model.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 团队管理器（第 15 章 F1/F2/F4/F6/N1/N4）。
 *
 * <p>持有进程内团队注册表（{@code teams}），并把团队配置持久化到
 * {@code ~/.novacode/teams/<slug>/}（config.json / tasks.json / inboxes/）。团队可落盘、
 * 可跨进程/跨重启按名重建。</p>
 *
 * <p><b>运行后端检测（F2）</b>：{@link #detectBackendFromEnv} 按环境优先级返回
 * tmux → iTerm2 → 进程内；Windows 恒为 IN_PROCESS（pwsh 无 POSIX 命令的护栏）。
 * 后端不可用的显式报错由 {@link SpawnDispatcher} 负责（不静默降级）。</p>
 */
public final class TeamManager {

    /** Lead 的默认代理 id（Lead 不在成员花名册里，用这个名字开收件箱）。 */
    public static final String LEAD_NAME = "lead";

    /** 成员运行后端。 */
    public enum TeamMode { IN_PROCESS, TMUX, ITERM }

    private final Map<String, Team> teams = new LinkedHashMap<>();

    public TeamManager() {}

    /** 团队根目录：{@code ~/.novacode/teams}。 */
    public static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.home"), ".novacode", "teams");
    }

    /** 按环境优先级选后端：tmux 非空 → TMUX；否则 iterm 会话非空 → ITERM；否则 IN_PROCESS。 */
    public static TeamMode detectBackendFromEnv(String tmux, String itermSessionId) {
        if (tmux != null && !tmux.isEmpty()) return TeamMode.TMUX;
        if (itermSessionId != null && !itermSessionId.isEmpty()) return TeamMode.ITERM;
        return TeamMode.IN_PROCESS;
    }

    /** 实际运行环境的后端检测：Windows 恒 IN_PROCESS。 */
    public static TeamMode detectBackend() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("windows")) return TeamMode.IN_PROCESS;
        return detectBackendFromEnv(System.getenv("TMUX"), System.getenv("ITERM_SESSION_ID"));
    }

    // ── Team ──────────────────────────────────────────────────────────────

    /** 一个长期团队：成员花名册 + 共享邮箱 + 共享任务板。 */
    public static final class Team {
        private final String name;
        private final TeamMode mode;
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;
        private final SharedTaskStore taskStore;
        private String leadAgentId;
        private String description;
        private long createdAt;

        Team(String name, TeamMode mode, FileMailBox mailBox, SharedTaskStore taskStore) {
            this.name = name;
            this.mode = mode;
            this.mailBox = mailBox;
            this.taskStore = taskStore;
        }

        public String name() { return name; }
        public TeamMode mode() { return mode; }
        public FileMailBox mailBox() { return mailBox; }
        public SharedTaskStore taskStore() { return taskStore; }
        public String leadAgentId() { return leadAgentId; }
        void setLeadAgentId(String s) { this.leadAgentId = s; }
        public String description() { return description; }
        void setDescription(String s) { this.description = s; }
        public long createdAt() { return createdAt; }
        void setCreatedAt(long t) { this.createdAt = t; }

        /** Lead 的收件箱投递标识（默认 {@link TeamManager#LEAD_NAME}）。 */
        public String leadInbox() {
            return leadAgentId == null || leadAgentId.isBlank() ? LEAD_NAME : leadAgentId;
        }

        public boolean hasMember(String name) { return members.containsKey(name); }
        public Member getMember(String name) { return members.get(name); }
        public List<Member> members() { return new ArrayList<>(members.values()); }
        public List<String> memberNames() { return new ArrayList<>(members.keySet()); }

        /** 登记成员 + 全局名字注册表登记（两段式投递第一段）。 */
        public void addMember(Member m) {
            members.put(m.name, m);
            AgentNameRegistry.getInstance().register(m.name, m.name);
        }

        public boolean startMember(String name) {
            Member m = members.get(name);
            if (m == null) return false;
            m.active = true;
            return true;
        }

        public void stopMember(String name) {
            Member m = members.get(name);
            if (m != null) stopMember(m);
        }

        /** 中止一个成员（置 inactive + 打断其循环线程）。 */
        public void stopMember(Member m) {
            m.active = false;
            Thread t = m.thread;
            if (t != null && t.isAlive()) t.interrupt();
        }

        public void stopAll() {
            for (Member m : members()) stopMember(m);
        }

        public void sendMessage(String recipient, MailMessage msg) { mailBox.send(recipient, msg); }
    }

    // ── Member ────────────────────────────────────────────────────────────

    /** 团队成员：运行句柄 + 共享可变对话（磁盘可恢复的上下文，F6）。 */
    public static final class Member {
        public final String name;
        public volatile Agent agent;
        public volatile List<ChatMessage> history = new ArrayList<>();
        public volatile boolean active;
        public volatile Thread thread;
        public volatile TeammateProgress progress = TeammateProgress.initial();
        public String agentType;
        public String model;
        public String worktreePath;
        public long joinedAt;
        public boolean needsApproval;

        public Member(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("member name required");
            this.name = name;
            this.joinedAt = System.currentTimeMillis();
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /** 建团队并落盘。同名已存在则抛出（去重由上层 {@link TeamTools#uniqueTeamName} 负责）。 */
    public Team createTeam(String name, TeamMode mode, String leadAgentId, String description) {
        if (teams.containsKey(name)) {
            throw new IllegalStateException("team already exists: " + name);
        }
        Path dir = teamsBaseDir().resolve(TeamFile.sanitizeTeamName(name));
        Team team = new Team(name, mode,
                new FileMailBox(dir.resolve("inboxes")),
                new SharedTaskStore(dir.resolve("tasks.json")));
        team.setLeadAgentId(leadAgentId == null ? LEAD_NAME : leadAgentId);
        team.setDescription(description);
        team.setCreatedAt(System.currentTimeMillis());
        teams.put(name, team);
        persist(team);
        return team;
    }

    /** 取团队：内存命中优先，否则从磁盘重建（N4）。没有返回 null。 */
    public Team getTeam(String name) {
        Team t = teams.get(name);
        if (t != null) return t;
        return rebuildFromDisk(name);
    }

    /** 取团队，没有则创建。 */
    public Team getOrCreateTeam(String name, TeamMode mode, String leadAgentId, String description) {
        Team t = getTeam(name);
        return t != null ? t : createTeam(name, mode, leadAgentId, description);
    }

    /** 删团队：停所有成员 + 注销 + 删目录。 */
    public boolean deleteTeam(String name) {
        Team t = teams.remove(name);
        if (t == null) t = rebuildFromDisk(name);
        if (t == null) return false;
        t.stopAll();
        for (String mn : t.memberNames()) AgentNameRegistry.getInstance().unregister(mn);
        deleteDir(teamsBaseDir().resolve(TeamFile.sanitizeTeamName(name)));
        return true;
    }

    public List<String> listTeams() { return new ArrayList<>(teams.keySet()); }
    public Collection<Team> teams() { return teams.values(); }
    public boolean hasTeam(String name) { return teams.containsKey(name); }

    /** 停止全部成员（退出时调用）。 */
    public void closeAll() {
        for (Team t : teams.values()) t.stopAll();
    }

    /** 补成员元信息并落盘。 */
    public void setMemberMeta(Team team, String name, String agentType, String model,
                              String worktreePath, boolean needsApproval) {
        Member m = team.getMember(name);
        if (m == null) return;
        m.agentType = agentType;
        m.model = model;
        m.worktreePath = worktreePath;
        m.needsApproval = needsApproval;
        persist(team);
    }

    /** 解析发送者所属团队：lead 或未知名字 → defaultTeamName；成员 → 其团队名。 */
    public String senderTeamName(String senderName, String defaultTeamName) {
        if (senderName == null || senderName.isEmpty() || LEAD_NAME.equals(senderName)) {
            return defaultTeamName;
        }
        for (Team t : teams.values()) {
            if (t.hasMember(senderName)) return t.name();
        }
        return defaultTeamName;
    }

    /** 落盘团队配置。 */
    public void persist(Team team) {
        Path dir = teamsBaseDir().resolve(TeamFile.sanitizeTeamName(team.name()));
        List<TeamFile.MemberInfo> infos = new ArrayList<>();
        for (Member m : team.members()) {
            infos.add(new TeamFile.MemberInfo(m.name, m.agentType, m.model, m.worktreePath,
                    m.needsApproval, m.joinedAt));
        }
        TeamFile.write(dir.resolve("config.json"), new TeamFile.TeamData(
                team.name(), team.mode().name(), team.leadAgentId(),
                team.description(), team.createdAt(), infos));
    }

    private Team rebuildFromDisk(String name) {
        Path dir = teamsBaseDir().resolve(TeamFile.sanitizeTeamName(name));
        TeamFile.TeamData data = TeamFile.read(dir.resolve("config.json"));
        if (data == null) return null;
        TeamMode mode;
        try {
            mode = TeamMode.valueOf(data.mode());
        } catch (Exception e) {
            mode = TeamMode.IN_PROCESS;
        }
        Team team = new Team(data.name(), mode,
                new FileMailBox(dir.resolve("inboxes")),
                new SharedTaskStore(dir.resolve("tasks.json")));
        team.setLeadAgentId(data.leadAgentId() == null ? LEAD_NAME : data.leadAgentId());
        team.setDescription(data.description());
        team.setCreatedAt(data.createdAt());
        if (data.members() != null) {
            for (TeamFile.MemberInfo mi : data.members()) {
                if (mi.name() == null) continue;
                Member m = new Member(mi.name());
                m.agentType = mi.agentType();
                m.model = mi.model();
                m.worktreePath = mi.worktreePath();
                m.needsApproval = mi.needsApproval();
                m.joinedAt = mi.joinedAt();
                team.addMember(m);
            }
        }
        teams.put(team.name(), team);
        return team;
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
