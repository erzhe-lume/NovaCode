package com.novacode.ui;

import com.novacode.tui.tea.ANSI256Color;
import com.novacode.tui.tea.Style;

/** Terminal styles for NovaCode TUI. Adapted from MewCode's palette. */
public final class Styles {

    private Styles() {}

    // Color palette
    private static final ANSI256Color BRAND_PURPLE = new ANSI256Color(99);
    private static final ANSI256Color DIM_TEXT = new ANSI256Color(242);
    private static final ANSI256Color MUTED_TEXT = new ANSI256Color(245);
    private static final ANSI256Color NORMAL_TEXT = new ANSI256Color(252);
    private static final ANSI256Color BRIGHT_TEXT = new ANSI256Color(255);
    private static final ANSI256Color GREEN_TEXT = new ANSI256Color(78);
    private static final ANSI256Color RED_TEXT = new ANSI256Color(203);
    private static final ANSI256Color YELLOW_TEXT = new ANSI256Color(214);
    private static final ANSI256Color CYAN_TEXT = new ANSI256Color(80);

    // Banner
    public static final Style banner = Style.newStyle().foreground(BRAND_PURPLE).bold(true);
    public static final Style bannerDim = Style.newStyle().foreground(DIM_TEXT);

    // Prompt
    public static final Style prompt = Style.newStyle().foreground(CYAN_TEXT).bold(true);

    // Messages
    public static final Style userText = Style.newStyle().foreground(BRIGHT_TEXT).bold(true);
    public static final Style aiText = Style.newStyle().foreground(NORMAL_TEXT);
    public static final Style streamingText = Style.newStyle().foreground(NORMAL_TEXT);
    public static final Style aiMarker = Style.newStyle().foreground(BRAND_PURPLE).bold(true);

    // Errors
    public static final Style error = Style.newStyle().foreground(RED_TEXT);
    public static final Style system = Style.newStyle().foreground(DIM_TEXT);

    // Status bar
    public static final Style statusBar = Style.newStyle().foreground(DIM_TEXT);

    // Selector
    public static final Style selectLabel = Style.newStyle().foreground(BRAND_PURPLE).bold(true);
    public static final Style selectedItem = Style.newStyle().foreground(CYAN_TEXT).bold(true);
    public static final Style normalItem = Style.newStyle().foreground(MUTED_TEXT);

    // Inline helpers
    private static final Style inlineCyan = Style.newStyle().foreground(CYAN_TEXT);
    private static final Style inlineDim = Style.newStyle().foreground(DIM_TEXT);
    private static final Style inlineGreen = Style.newStyle().foreground(GREEN_TEXT);
    private static final Style inlineRed = Style.newStyle().foreground(RED_TEXT);
    private static final Style inlineYellow = Style.newStyle().foreground(YELLOW_TEXT);
    private static final Style inlinePurple = Style.newStyle().foreground(BRAND_PURPLE);

    public static String cyan(String s) { return inlineCyan.render(s); }
    public static String dim(String s) { return inlineDim.render(s); }
    public static String green(String s) { return inlineGreen.render(s); }
    public static String red(String s) { return inlineRed.render(s); }
    public static String yellow(String s) { return inlineYellow.render(s); }
    public static String purple(String s) { return inlinePurple.render(s); }
}
