package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * From 0.6.3 no start of Dasher by this app asks for the other half (launch adjacent), from any path, dash or not: the
 * owner's "dd is stuck again" reports of 10, 11 and 12 October 2026 followed Split taps that started Dasher's launcher
 * launch-adjacent, and Dasher's half stayed on its red launch screen (why it stayed is not established). The one
 * launch-adjacent start left is this app's own task beside Dasher, once Dasher is up past its launch screen
 * (OfferFilterService.startOwnBeside). This suite reads the app's code, comments and words in strings left out, so a
 * second launch-adjacent start, the retired DasherSplit.dasher(Context) (Dasher's launcher made launch-adjacent),
 * Android's split action or a task ID read from Android's windows cannot come back unnoticed.
 */
public final class LaunchAdjacentSourceTest {
    private static final String PACKAGE = "app/src/main/java/com/local/dasherfilter/";
    private static final String FLAG = "FLAG_ACTIVITY_LAUNCH_ADJACENT";

    @Test
    public void theOnlyLaunchAdjacentStartIsThisAppsOwnSplitPane() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path source : mainJava()) {
            String code = codeOnly(read(source));
            for (int at = code.indexOf(FLAG); at >= 0; at = code.indexOf(FLAG, at + FLAG.length())) {
                found.add(source.getFileName() + ":" + line(code, at));
            }
        }
        assertEquals("one launch-adjacent start in the app: " + found, 1, found.size());
        assertTrue(found.get(0), found.get(0).startsWith("DasherSplit.java:"));

        String split = codeOnly(read(new File(root(), PACKAGE + "DasherSplit.java").toPath()));
        int[] body = methodBody(split, "private static String paneFirst(");
        int flag = split.indexOf(FLAG);
        assertTrue("the flag is set inside paneFirst's own body", flag > body[0] && flag < body[1]);
        // On this app's own pane's intent, with exactly a new task beside it: nothing reset, cleared or reordered.
        String statement = statementAround(split, flag).replaceAll("\\s+", "");
        assertEquals("pane.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)", statement);
        String inBody = split.substring(body[0], body[1]);
        assertTrue("it is this app's own split pane's intent",
                inBody.contains("Intent pane = new Intent(activity, SplitPaneActivity.class)"));
        assertFalse("never Dasher's launcher", inBody.contains("launcher("));
        // The screen reader's own start beside Dasher is gone.
        String service = codeOnly(read(new File(root(), PACKAGE + "OfferFilterService.java").toPath()));
        assertFalse("no startOwnBeside", service.contains("startOwnBeside("));
    }

    @Test
    public void dasherSplitNoLongerMakesDashersLauncherLaunchAdjacent() throws Exception {
        String split = codeOnly(read(new File(root(), PACKAGE + "DasherSplit.java").toPath()));
        assertFalse("no dasher(Context) in DasherSplit's code",
                Pattern.compile("\\bdasher\\s*\\(\\s*(final\\s+)?Context\\b").matcher(split).find());
        Class<?> type = Class.forName("com.local.dasherfilter.DasherSplit", false,
                LaunchAdjacentSourceTest.class.getClassLoader());
        for (Method method : type.getDeclaredMethods()) {
            assertFalse("DasherSplit." + method.getName() + " is retired", method.getName().equals("dasher"));
        }
    }

    @Test
    public void neitherAndroidsSplitActionNorATaskIdFromItsWindowsIsUsed() throws IOException {
        List<String> found = new ArrayList<>();
        Pattern banned = Pattern.compile("\\bGLOBAL_ACTION_TOGGLE_SPLIT_SCREEN\\b|\\bgetTaskId\\s*\\(");
        for (Path source : mainJava()) {
            String code = codeOnly(read(source));
            Matcher matcher = banned.matcher(code);
            while (matcher.find()) found.add(source.getFileName() + ":" + line(code, matcher.start()));
        }
        assertEquals(new ArrayList<String>(), found);
    }

    // ---- Reading the sources ----

    private static File root() {
        return new File("../app").isDirectory() ? new File("..") : new File(".");
    }

    private static List<Path> mainJava() throws IOException {
        File main = new File(root(), PACKAGE);
        assertTrue("the app's sources are where this suite looks", new File(main, "DasherSplit.java").isFile());
        try (Stream<Path> files = Files.walk(main.toPath())) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).sorted()
                    .collect(Collectors.toList());
            assertTrue("found the app's sources: " + sources.size(), sources.size() > 100);
            return sources;
        }
    }

    private static String read(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    /**
     * The source with every comment, string and character literal blanked (each character a space, line ends kept), so
     * offsets and line numbers stay those of the file and only code is matched.
     */
    static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') { code.append(' '); i++; }
            } else if (c == '/' && next == '*') {
                code.append("  ");
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    code.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) { code.append("  "); i += 2; }
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                code.append("   ");
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\\' && i + 1 < n) { code.append("  "); i += 2; continue; }
                    code.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) { code.append("   "); i += 3; }
            } else if (c == '"' || c == '\'') {
                code.append(c);
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < n) { code.append("  "); i += 2; continue; }
                    code.append(' ');
                    i++;
                }
                if (i < n && source.charAt(i) == c) { code.append(c); i++; }
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    /** The offsets of a method's body, from its opening brace to its matching closing brace, in code only. */
    private static int[] methodBody(String code, String head) {
        int at = code.indexOf(head);
        assertTrue("found " + head, at >= 0);
        assertEquals("declared once: " + head, at, code.lastIndexOf(head));
        int open = code.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return new int[] {open, i};
        }
        throw new AssertionError("unbalanced braces after " + head);
    }

    /** The statement holding {@code at}: from the previous statement's end (or a brace) to its own semicolon. */
    private static String statementAround(String code, int at) {
        int start = at;
        while (start > 0 && ";{}".indexOf(code.charAt(start - 1)) < 0) start--;
        int end = code.indexOf(';', at);
        String statement = code.substring(start, end).trim();
        // An if's condition before it, as in "if (own != null) own.setFlags(…)".
        Matcher condition = Pattern.compile("^if\\s*\\((.*?)\\)\\s*(?=\\w+\\.setFlags)", Pattern.DOTALL)
                .matcher(statement);
        return condition.find() ? statement.substring(condition.end()) : statement;
    }

    private static int line(String code, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }
}
