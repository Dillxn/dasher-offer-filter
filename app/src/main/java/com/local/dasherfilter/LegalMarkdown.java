package com.local.dasherfilter;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bundled texts' Markdown, read for the legal pages ({@link LegalActivity}): blocks (headings, paragraphs,
 * bulleted and numbered lists, quotes, rules) and, inside them, **bold**, *italic*, `code`, [links](https://…) and
 * plain web addresses and emails (Linkify). Every mark is drawn, never shown: no reader sees a literal asterisk.
 * Unmatched marks (a lone "*" in "5 * 3") stay as written.
 */
final class LegalMarkdown {
    private LegalMarkdown() {}

    enum Kind { TITLE, HEADING, SUBHEADING, PARAGRAPH, BULLET, NUMBERED, QUOTE, RULE }

    /** One block of the text, its words still in inline Markdown. */
    static final class Block {
        final Kind kind;
        /** For a list item, how deep it is nested (0 at the margin); else 0. */
        final int depth;
        /** For a numbered item, its number as written ("1."); else empty. */
        final String marker;
        final String text;

        Block(Kind kind, int depth, String marker, String text) {
            this.kind = kind;
            this.depth = depth;
            this.marker = marker;
            this.text = text;
        }

        @Override public String toString() {
            return kind + (depth > 0 ? "/" + depth : "") + (marker.isEmpty() ? "" : " " + marker) + ": " + text;
        }
    }

    private static final Pattern BULLET = Pattern.compile("^( *)[-*+] +(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^( *)(\\d{1,3})[.)] +(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) +(.*?)( +#+)? *$");
    private static final Pattern RULE = Pattern.compile("^ *([-*_])( *\\1){2,} *$");

    /** The text's blocks, in order. A blank line ends a paragraph or list item; others join with a space. */
    static List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder words = new StringBuilder();
        Kind kind = null;
        int depth = 0;
        String marker = "";
        for (String raw : (text == null ? "" : text).replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.replace('\t', ' ');
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            boolean blank = line.trim().isEmpty();
            boolean rule = RULE.matcher(line).matches();
            boolean starts = blank || rule || heading.matches() || (bullet.matches() && !rule) || numbered.matches()
                    || line.startsWith(">");
            if (starts && kind != null) {
                blocks.add(new Block(kind, depth, marker, words.toString().trim()));
                words.setLength(0);
                kind = null;
            }
            if (blank) continue;
            if (rule) {
                blocks.add(new Block(Kind.RULE, 0, "", ""));
            } else if (heading.matches()) {
                int level = heading.group(1).length();
                blocks.add(new Block(level == 1 ? Kind.TITLE : level == 2 ? Kind.HEADING : Kind.SUBHEADING, 0, "",
                        heading.group(2).trim()));
            } else if (bullet.matches()) {
                kind = Kind.BULLET;
                depth = bullet.group(1).length() / 2;
                marker = "";
                words.append(bullet.group(2));
            } else if (numbered.matches()) {
                kind = Kind.NUMBERED;
                depth = numbered.group(1).length() / 2;
                marker = numbered.group(2) + ".";
                words.append(numbered.group(3));
            } else if (line.startsWith(">")) {
                kind = Kind.QUOTE;
                depth = 0;
                marker = "";
                words.append(line.replaceFirst("^> ?", ""));
            } else {
                if (kind == null) {
                    kind = Kind.PARAGRAPH;
                    depth = 0;
                    marker = "";
                }
                // Two trailing spaces are Markdown's line break; otherwise lines of one block join with a space.
                if (words.length() > 0) words.append(words.charAt(words.length() - 1) == '\n' ? "" : " ");
                words.append(raw.endsWith("  ") ? line.trim() + "\n" : line.trim());
            }
        }
        if (kind != null) blocks.add(new Block(kind, depth, marker, words.toString().trim()));
        return blocks;
    }

    /**
     * {@code markdown}'s words with its inline marks drawn: bold, italic and code as spans, [text](address) as a link
     * on its text, and plain web addresses and emails linked too.
     */
    static SpannableStringBuilder inline(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        List<Object[]> links = new ArrayList<>();
        append(markdown == null ? "" : markdown, 0, markdown == null ? 0 : markdown.length(), out, links);
        // Linkify replaces every link span it finds, so the written links go on after it, over any it found inside.
        Linkify.addLinks(out, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        for (Object[] link : links) {
            int start = (Integer) link[0];
            int end = (Integer) link[1];
            for (URLSpan found : out.getSpans(start, end, URLSpan.class)) out.removeSpan(found);
            out.setSpan(new URLSpan((String) link[2]), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static final String ESCAPABLE = "\\`*_[](){}#+-.!<>|";

    private static void append(String s, int from, int to, SpannableStringBuilder out, List<Object[]> links) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1 && end < to) {
                    int start = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < to && s.charAt(i + 1) == c) {
                int end = closing(s, c, 2, i + 2, to);
                if (end > i + 2 && opens(s, i, 2, from, to, c)) {
                    int start = out.length();
                    append(s, i + 2, end, out, links);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                int end = closing(s, c, 1, i + 1, to);
                if (end > i + 1 && opens(s, i, 1, from, to, c)) {
                    int start = out.length();
                    append(s, i + 1, end, out, links);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i + 1 && close + 1 < to && s.charAt(close + 1) == '(') {
                    int end = s.indexOf(')', close + 2);
                    String address = end > close + 2 && end < to ? s.substring(close + 2, end).trim() : "";
                    if (!address.isEmpty() && !address.contains(" ")) {
                        int start = out.length();
                        append(s, i + 1, close, out, links);
                        links.add(new Object[] {start, out.length(), address});
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (c == '<') {
                int end = s.indexOf('>', i + 1);
                String inside = end > i + 1 && end < to ? s.substring(i + 1, end) : "";
                if (inside.matches("(?i)(https?://|mailto:)\\S+") || inside.matches("[^\\s@<>]+@[^\\s@<>]+\\.[a-zA-Z]{2,}")) {
                    int start = out.length();
                    out.append(inside);
                    links.add(new Object[] {start, out.length(),
                            inside.contains("://") || inside.startsWith("mailto:") ? inside : "mailto:" + inside});
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    /** Whether the {@code width} marks at {@code at} open an emphasis: not followed by a space, nor inside a word for _. */
    private static boolean opens(String s, int at, int width, int from, int to, char mark) {
        int after = at + width;
        if (after >= to || Character.isWhitespace(s.charAt(after))) return false;
        return mark != '_' || at == from || !Character.isLetterOrDigit(s.charAt(at - 1));
    }

    /** Where the emphasis opened before {@code from} closes (the first of its {@code width} marks), or -1. */
    private static int closing(String s, char mark, int width, int from, int to) {
        for (int j = from; j + width <= to; j++) {
            if (s.charAt(j) == '\\') {
                j++;
                continue;
            }
            if (s.charAt(j) == '`') {
                int end = s.indexOf('`', j + 1);
                if (end > j && end < to) {
                    j = end;
                    continue;
                }
            }
            boolean run = true;
            for (int k = 0; k < width; k++) run &= s.charAt(j + k) == mark;
            if (!run) continue;
            // A single mark must not be half of a double one (that is bold, inside or around this emphasis).
            boolean longer = (j + width < to && s.charAt(j + width) == mark) || (j > from && s.charAt(j - 1) == mark);
            if (width == 1 && longer) {
                // Step over the whole run of marks.
                while (j + 1 < to && s.charAt(j + 1) == mark) j++;
                continue;
            }
            if (Character.isWhitespace(s.charAt(j - 1))) continue;
            if (mark == '_' && j + width < to && Character.isLetterOrDigit(s.charAt(j + width))) continue;
            return j;
        }
        return -1;
    }
}
