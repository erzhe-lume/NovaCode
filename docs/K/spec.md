# 第 14 章 Git Worktree 隔离 Spec

## 背景

第 13 章实现了子 Agent 委派：主 Agent 通过统一的 `Agent` 工具派生子 Agent（定义式 / Fork 式），
每个子 Agent 有独立的对话上下文、工具集和权限引擎。但所有子 Agent 与主 Agent 共享同一个**工作目录**
（JVM 的 `user.dir`）。当主 Agent 与多个子 Agent 并行改文件时，它们会互相覆盖、读到彼此的半成品，
文件读缓存（`FileStateCache`）也无法区分「谁读了哪个文件」。

本章引入 **Git Worktree 隔离**：在同一仓库里为每个子 Agent 开一个独立工作目录，让它们
同时改文件而不互相影响，同时通过绝对路径做缓存 key 实现天然的按目录隔离。

## 目标

- 用 Git 原生多工作目录机制，在同一个仓库里为子 Agent 开辟互相隔离的工作目录。
- 用严格的路径名安全校验防止 LLM 输入造成的路径穿越。
- 提供 create（含 fast-resume）/ enter / exit / delete 完整生命周期。
- 创建后做环境初始化（复制本地配置、配置 git hooks、软链大依赖目录、复制被忽略但需要的文件）。
- **不通过 chdir 切换工作目录**，而是把工作目录作为显式 cwd 传给每个工具调用；
  所有路径相关缓存都用绝对路径做 key，天然按目录隔离，切换时无需清缓存。
- 子 Agent 角色通过 frontmatter `isolation: worktree` 声明隔离模式；退出时按「是否有改动」决定保留还是删除。

## 功能需求

- **F1 多工作目录隔离**：使用 Git 原生 worktree（同一仓库、多个目录、共享对象库、各自独立分支）。
  工作目录放置在仓库内一个不被跟踪的位置（`.novacode/worktrees/`），每个 worktree 有独立分支。

- **F2 路径名安全校验**：worktree 目录名经过严格校验——限制字符集与长度、拒绝 `.` 与 `..` 段、
  允许 `/` 做嵌套，从而阻止 LLM 输入的路径穿越（如 `../../etc`、绝对路径、超长名、Windows 保留字符）。

- **F3 完整生命周期**：create（目录已存在时走 fast-resume：只读文件系统、不调用 git）、
  enter、exit、delete 四个环节齐全，均可独立调用。

- **F4 创建后环境初始化**：create 成功后自动做四件事——复制本地配置
  （`.novacode/settings.local.json`）、配置子目录 git hooks（`core.hooksPath`）、
  软链大依赖目录（按配置清单）、按 `.worktreeinclude` 规则复制被 gitignore 但需要的文件。

- **F5 显式 cwd 传参**：不通过 `chdir` 切换进程工作目录，而是把工作目录作为显式 cwd 传入每个工具
  （文件工具的路径解析、Bash 的进程工作目录都基于该 cwd 解析相对路径并规范化为绝对路径）。
  所有路径相关缓存（文件读缓存等）以绝对路径为 key，天然按目录隔离，切换目录无需清缓存。

- **F6 子 Agent 隔离模式**：子 Agent 角色定义新增 `isolation: worktree` 字段；
  enter 时自动创建目录并注入路径通知（告诉子 Agent 它被隔离在哪），
  完成时按「是否有改动」决定保留（keep）还是删除（remove）。

- **F7 改动保护与后台清理**：exit 时若有未提交改动或未推送提交，默认拒绝删除（保留）；
  后台周期性清理过期临时目录，清理前做三层安全过滤（临时目录名匹配 → 超时阈值 → fail-closed 改动检查）。

## 非功能需求

- **N1 安全第一**：任何校验失败、git 命令异常都 fail-closed——宁可保留/拒绝，也不误删。
- **N2 无全局状态切换**：切换 worktree 不依赖进程级 `chdir`，也不依赖 `ThreadLocal` 之类的隐式上下文，
  并发安全、可测试。
- **N3 容错**：git 不可用、worktree 已存在、软链失败、配置文件缺失等都不应让主流程崩溃，
  逐项降级并记录。
- **N4 语言无关 / 行为聚焦**：本章 spec 与 plan/task 分层，spec 只描述行为，具体类名、方法、文件路径
  分别落到 plan 与 task。

## 不做的事

- Worktree 的合并策略（`git merge` / 上层的合并编排）。
- 跨目录代码同步（worktree 之间的 diff/patch 搬运）。
- 多 Agent 并行编排（团队协作，留给后续章节）。
- 远程 / 云端隔离（`isolation: remote`，仅 `worktree` 在范围内）。

## 验收标准

- **AC1**（F1）：给定一个临时 git 仓库，创建两个 worktree 后，二者各自有独立目录与独立分支，
  在 A 中写文件不会出现在 B 中。
- **AC2**（F2）：`../../etc`、`/abs/path`、`a//b`、`.`、`..`、超长名、含 `*`/`?` 等非法名一律被拒绝；
  `a/b/c`、`my.worktree-1` 等合法名通过，并产出规范分支名 `worktree-<扁平名>`。
- **AC3**（F3）：目录已存在时 create 走 fast-resume（不调用 git，只读文件系统），返回一致的 head commit；
  删除一个空 worktree 后目录消失。
- **AC4**（F4）：配置了 `.worktreeinclude` 与软链清单后，create 会复制清单文件、软链大目录、
  复制 settings.local.json、设置 core.hooksPath。
- **AC5**（F5）：同一路径在两个不同 cwd 下被解析成两个不同绝对路径；Bash 在指定 cwd 下执行；
  文件读缓存以绝对路径为 key，切换 cwd 后无需清缓存即可正确区分。
- **AC6**（F6）：声明 `isolation: worktree` 的角色被派发时，自动创建 worktree、注入路径通知；
  有改动时保留、无改动时删除。
- **AC7**（F7）：带未提交改动的 worktree 拒绝删除；带未推送提交的 worktree 拒绝删除；
  无改动且超时的临时目录被后台清理删除。
