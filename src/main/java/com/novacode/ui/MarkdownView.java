package com.novacode.ui;

import com.novacode.tui.tea.ANSI256Color;
import com.novacode.tui.tea.Style;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown → ANSI 终端渲染（assistant 消息专用）。
 *
 * <p>{@link Style#render} 以硬 reset（ESC[0m）结尾，嵌套样式会吞掉外层颜色，
 * 因此这里所有 styled 片段都是平铺的 —— 每个片段独立携带自己的转义序列。
 * 支持：标题（紫色加粗）、围栏/缩进代码块（青色）、有序/无序列表（悬挂缩进）、
 * 引用块（▌ 前缀）、行内代码（青色）、加粗、链接（目标地址暗色）。其余块降级为正文。</p>
 */
public final class MarkdownView {

    private static final Parser PARSER = Parser.builder().build();

    private static final ANSI256Color NORMAL = new ANSI256Color(252);
    private static final ANSI256Color BRIGHT = new ANSI256Color(255);
    private static final ANSI256Color HEADING = new ANSI256Color(99);
    private static final ANSI256Color CODE = new ANSI256Color(80);
    private static final ANSI256Color DIM = new ANSI256Color(242);

    private static final Style TEXT = Style.newStyle().foreground(NORMAL);
    private static final Style STRONG = Style.newStyle().foreground(BRIGHT).bold(true);
    private static final Style CODE_S = Style.newStyle().foreground(CODE);
    private static final Style DIM_S = Style.newStyle().foreground(DIM);
    private static final Style HEADING_S = Style.newStyle().foreground(HEADING).bold(true);

    private MarkdownView() {}

    /** 渲染为 styled 行列表（段落内换行已展开为独立行）。 */
    public static List<String> renderLines(String markdown) {
        List<String> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return out;
        Node root = PARSER.parse(markdown);
        for (Node b = root.getFirstChild(); b != null; b = b.getNext()) {
            renderBlock(b, out, "");
        }
        // 行内换行展开，保证每条 entry 恰好一行（viewport 计数依赖）
        List<String> flat = new ArrayList<>(out.size());
        for (String line : out) {
            for (String p : line.split("\n", -1)) flat.add(p);
        }
        return flat;
    }

    private static void renderBlock(Node block, List<String> out, String indent) {
        switch (block) {
            case Heading h -> out.add(indent + HEADING_S.render(inline(h.getFirstChild()).trim()));
            case FencedCodeBlock f -> renderCode(f.getLiteral(), f.getInfo(), out, indent);
            case IndentedCodeBlock i -> renderCode(i.getLiteral(), null, out, indent);
            case Paragraph p -> out.add(indent + inline(p.getFirstChild()));
            case BlockQuote bq -> {
                for (Node c = bq.getFirstChild(); c != null; c = c.getNext()) {
                    renderBlock(c, out, indent + DIM_S.render("▌ "));
                }
            }
            case ThematicBreak tb -> out.add(indent + DIM_S.render("─".repeat(24)));
            case HtmlBlock hb -> out.add(indent + DIM_S.render(hb.getLiteral().strip()));
            case BulletList bl -> {
                for (Node li = bl.getFirstChild(); li != null; li = li.getNext()) {
                    renderListItem(li, out, indent, "•  ");
                }
            }
            case OrderedList ol -> {
                int n = 1;
                for (Node li = ol.getFirstChild(); li != null; li = li.getNext(), n++) {
                    renderListItem(li, out, indent, n + ". ");
                }
            }
            default -> {
                // 未知块降级：尝试按段落渲染其子内联
                if (block.getFirstChild() != null) {
                    String s = inline(block.getFirstChild());
                    if (!s.isBlank()) out.add(indent + s);
                }
            }
        }
    }

    private static void renderCode(String literal, String lang, List<String> out, String indent) {
        if (lang != null && !lang.isBlank()) {
            out.add(indent + DIM_S.render("‹" + lang.trim() + "›"));
        }
        if (literal == null) return;
        String[] lines = literal.split("\n", -1);
        // 尾部多余的空行不渲染（fenced block 字面量通常带一个结尾换行）
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) end--;
        for (int i = 0; i < end; i++) {
            out.add(indent + CODE_S.render("│ " + lines[i]));
        }
    }

    private static void renderListItem(Node item, List<String> out, String indent, String marker) {
        if (!(item instanceof ListItem)) {
            out.add(indent + TEXT.render(marker) + inline(item.getFirstChild()));
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
            renderBlock(c, rendered, "");
        }
        for (int i = 0; i < rendered.size(); i++) {
            out.add(i == 0
                    ? indent + TEXT.render(marker) + rendered.get(i)
                    : indent + " ".repeat(marker.length()) + rendered.get(i));
        }
        if (rendered.isEmpty()) out.add(indent + TEXT.render(marker.trim()));
    }

    // ── 行内渲染（平铺 span）────────────────────────────────────────────

    private static String inline(Node n) {
        var sb = new StringBuilder();
        for (Node c = n; c != null; c = c.getNext()) sb.append(inlineNode(c));
        return sb.toString();
    }

    private static String inlineNode(Node node) {
        if (node instanceof Text t) return TEXT.render(t.getLiteral());
        if (node instanceof Code c) return CODE_S.render(c.getLiteral());
        if (node instanceof StrongEmphasis s) return flatten(s.getFirstChild(), STRONG);
        if (node instanceof Emphasis e) return flatten(e.getFirstChild(), TEXT);
        if (node instanceof Link l) {
            String text = flatten(l.getFirstChild(), TEXT);
            String dest = l.getDestination();
            return text + (dest == null || dest.isBlank() ? "" : DIM_S.render(" (" + dest + ")"));
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) return "\n";
        return "";
    }

    /** 强调内容平铺渲染：每个子片段独立套用 {@code st}，不吃掉彼此的颜色。 */
    private static String flatten(Node n, Style st) {
        var sb = new StringBuilder();
        for (Node c = n; c != null; c = c.getNext()) {
            String plain = plainInline(c);
            if (!plain.isEmpty()) sb.append(st.render(plain));
        }
        return sb.toString();
    }

    private static String plainInline(Node n) {
        var sb = new StringBuilder();
        for (Node c = n; c != null; c = c.getNext()) {
            if (c instanceof Text t) sb.append(t.getLiteral());
            else if (c instanceof Code code) sb.append(code.getLiteral());
            else if (c instanceof StrongEmphasis s) sb.append(plainInline(s.getFirstChild()));
            else if (c instanceof Emphasis e) sb.append(plainInline(e.getFirstChild()));
            else if (c instanceof Link l) sb.append(plainInline(l.getFirstChild()));
            else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) sb.append('\n');
        }
        return sb.toString();
    }
}
