package com.novacode.hook;

import com.novacode.permission.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 条件求值器（第 12 章 spec F3）。叶子操作符 ==/!=/=~/=*，组合 &&/|| 二选一不混用。
 * glob 匹配复用权限规则 {@link Rule#globToRegex}：file_path 走路径语义（** 跨目录）。
 */
public final class HookCondition {

    private HookCondition() {}

    /** 条件是否同时混用 && 与 ||（spec F3「不混用」→ 校验错误）。 */
    public static boolean mixesOperators(String condition) {
        return condition != null && condition.contains("&&") && condition.contains("||");
    }

    /** 求值条件表达式；空串无条件为真。 */
    public static boolean evaluate(String condition, HookContext ctx) {
        String cond = condition.strip();
        if (cond.isEmpty()) return true;

        List<Token> tokens = splitComposite(cond);
        if (tokens != null && tokens.size() > 1) {
            boolean result = evaluate(tokens.get(0).expr, ctx);
            for (int i = 1; i < tokens.size(); i++) {
                boolean rhs = evaluate(tokens.get(i).expr, ctx);
                result = "&&".equals(tokens.get(i).op) ? (result && rhs) : (result || rhs);
            }
            return result;
        }
        return evaluateLeaf(cond, ctx);
    }

    private record Token(String op, String expr) {}

    /** 按顶层 && / || 切分；无复合返回 null。 */
    private static List<Token> splitComposite(String s) {
        List<Token> out = new ArrayList<>();
        int start = 0;
        String currentOp = "";
        for (int i = 0; i < s.length() - 1; i++) {
            String pair = s.substring(i, i + 2);
            if ("&&".equals(pair) || "||".equals(pair)) {
                out.add(new Token(currentOp, s.substring(start, i).strip()));
                currentOp = pair;
                start = i + 2;
                i++;
            }
        }
        out.add(new Token(currentOp, s.substring(start).strip()));
        return out.size() <= 1 ? null : out;
    }

    static boolean evaluateLeaf(String condition, HookContext ctx) {
        // != 须先于 == 探测
        for (String op : new String[]{"!=", "=~", "=*", "=="}) {
            int idx = condition.indexOf(op);
            if (idx >= 0) {
                String left = condition.substring(0, idx).strip();
                String right = stripQuotes(condition.substring(idx + op.length()).strip());
                String val = resolveVar(left, ctx);
                boolean pathSemantics = "file_path".equals(left);
                return switch (op) {
                    case "==" -> val.equals(right);
                    case "!=" -> !val.equals(right);
                    case "=~" -> {
                        try {
                            yield Pattern.compile(stripSlashes(right)).matcher(val).find();
                        } catch (PatternSyntaxException e) {
                            yield false;
                        }
                    }
                    case "=*" -> {
                        try {
                            yield Rule.globToRegex(right, pathSemantics).matcher(val).matches();
                        } catch (PatternSyntaxException e) {
                            yield false;
                        }
                    }
                    default -> false;
                };
            }
        }
        // 无操作符 → 变量非空为真
        return !resolveVar(condition.strip(), ctx).isEmpty();
    }

    static String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool" -> ctx.toolName() != null ? ctx.toolName() : "";
            case "event" -> ctx.event() != null ? ctx.event().value() : "";
            case "file_path" -> ctx.filePath() != null ? ctx.filePath() : "";
            case "message" -> ctx.message() != null ? ctx.message() : "";
            default -> {
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    Object v = ctx.toolArgs().get(name.substring("args.".length()));
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '/' && last == '/')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String stripSlashes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '/' && s.charAt(s.length() - 1) == '/') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
