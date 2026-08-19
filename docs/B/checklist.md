# 第 5 章：系统提示工程化 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [x] F1 模块化组装：7 固定模块按优先级输出（验证：T9 Test C — 组装结果以稳定块为前缀、无空槽残留）
- [x] F2 稳定/易变分离：`buildStableModules()` 不含环境信息，跨环境字节一致（验证：T9 Test C — 含 "Working directory" 检测为 false）
- [x] F3 环境块在完整 prompt 末尾，含工作目录/平台/Shell/git/版本/模型/日期（验证：T9 Test C — full 含 env 且以稳定块开头）
- [x] F4 双重强化：6 工具 description + UsingTools 模块均含"专用工具优先、编辑前先读"（验证：grep 6 工具全含强化措辞 + PromptSections 源码）
- [x] F5 `setSystemSuffix` 完全移除，无残留引用（验证：grep `setSystemSuffix|systemSuffix` 无匹配）
- [x] F6 system-reminder 每轮注入 plan mode history，不污染持久历史与 system 缓存（验证：T9 Test D — planMode 注入、exec 模式无注入）
- [x] F7 plan 提醒频率符合 `REMINDER_INTERVAL=5` 公式（验证：临时 main — 1/2/5 full、6/25 sparse、26/51 full）
- [x] F8 DeepSeek 缓存字段解析：cached_tokens 优先、顶层 hit/miss 兜底（验证：T9 Test E — 三种 payload 全部断言通过）

## 编译与测试
- [x] `mvn compile` 无明显警告（验证：编译输出无 Java 警告，补了 ConfigLoader serialVersionUID + TypeReference）
- [x] `mvn clean package -DskipTests` 成功（验证：BUILD SUCCESS，novacode-1.0.0.jar 4.4MB）
- [x] 临时测试清理完毕，无 `.tmp_test` 残留

## 端到端场景
- [x] 场景 1（启动）：无头启动渲染完整 UI 无崩溃；`launch.bat` 实机确认待用户
- [ ] 场景 2（plan 注入）：无头已验证；实机 `launch.bat` + `/plan` 观察待用户
- [ ] 场景 3（缓存 smoke）：解析逻辑已验证；实机两轮对话看 `nova_cache.log` 待用户
- [x] 场景 4（非 git 降级）：T9 Test B — isGitRepo=false、gitBranch 空、不报错

## 验收报告记录
- 通过（12/14）：F1-F8、编译×3、场景 1（无头）、场景 4
- 待用户实机确认：场景 2、场景 3（需真实 DeepSeek 会话 + launch.bat）
