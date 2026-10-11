package com.local.dasherfilter;

import android.content.Context;
import java.util.List;
import java.util.function.UnaryOperator;
import org.json.JSONObject;

/** Self-contained, short ASCII lines fit inside existing text parts; strict numeric/enum allowlist at every send. */
final class DispatchStudyPayload {
    static final String PREFIX = "[offer-timing-v1] ";
    static final int MAX_CHARS = 10_000;

    static String section(List<DispatchStudy.Row> rows, long epoch, int dropped) {
        StringBuilder out = new StringBuilder();
        int omitted = Math.max(0, rows.size() - 10);
        for (int i = omitted; i < rows.size(); i++) {
            try {
                String line = PREFIX + rows.get(i).json(epoch).put("omittedRows", omitted + dropped).toString() + "\n";
                if (out.length() + line.length() > MAX_CHARS - 150) { omitted++; continue; }
                out.append(line);
            } catch (Exception invalid) { omitted++; }
        }
        if (out.length() == 0) return "";
        return out.toString();
    }

    /** Removes withdrawn, stale or malformed study lines without touching the user's other diagnostics. */
    static String current(Context c, String text) {
        return transform(text, line -> {
            try {
                JSONObject j = new JSONObject(line.substring(PREFIX.length()));
                DispatchStudy.Row row = DispatchStudy.Row.parse(j);
                long e = j.getLong("consentEpoch");
                return DispatchStudyStore.allowed(c, e) ? PREFIX + row.json(e) : "";
            } catch (Exception invalid) { return ""; }
        });
    }

    /** Mask the ordinary report as before. Study lines contain only allowlisted numbers/enums, never raw text. */
    static String remask(String text, UnaryOperator<String> mask) {
        if (!text.contains(PREFIX)) return mask.apply(text);
        StringBuilder study = new StringBuilder();
        String plain = transform(text, line -> {
            try {
                JSONObject j = new JSONObject(line.substring(PREFIX.length()));
                DispatchStudy.Row row = DispatchStudy.Row.parse(j);
                study.append(PREFIX).append(row.json(j.getLong("consentEpoch"))).append('\n');
            } catch (Exception invalid) { /* A malformed section is omitted, never returned raw. */ }
            return "";
        });
        return mask.apply(plain) + study;
    }

    private static String transform(String text, UnaryOperator<String> row) {
        if (text == null || !text.contains(PREFIX)) return text;
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(PREFIX)) {
                String safe = line.startsWith(PREFIX) ? row.apply(line) : "";
                if (safe.isEmpty()) continue;
                out.append(safe);
            } else out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }
    private DispatchStudyPayload() {}
}
