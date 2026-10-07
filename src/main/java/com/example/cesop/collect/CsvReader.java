package com.example.cesop.collect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small RFC-4180 style CSV reader. Auto-detects , ; or tab. Header names are lower-cased, spaces become _. */
public final class CsvReader {
    private CsvReader() {}

    public record Row(int line, Map<String, String> values) {}
    public record Parsed(List<String> header, List<Row> rows) {}

    private record Rec(int line, List<String> cells) {}

    public static Parsed parse(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        char delim = detect(text);

        List<Rec> recs = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        List<String> cur = new ArrayList<>();
        boolean quoted = false;
        int line = 1, start = 1, n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == (char) 34) {
                    if (i + 1 < n && text.charAt(i + 1) == (char) 34) { f.append((char) 34); i++; }
                    else quoted = false;
                } else {
                    if (c == (char) 10) line++;
                    f.append(c);
                }
            } else if (c == (char) 34) {
                quoted = true;
            } else if (c == delim) {
                cur.add(f.toString()); f.setLength(0);
            } else if (c == (char) 13) {
                // ignore
            } else if (c == (char) 10) {
                cur.add(f.toString()); f.setLength(0);
                recs.add(new Rec(start, cur));
                cur = new ArrayList<>();
                line++; start = line;
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !cur.isEmpty()) { cur.add(f.toString()); recs.add(new Rec(start, cur)); }

        recs.removeIf(r -> r.cells().stream().allMatch(s -> s.isBlank()));
        if (recs.isEmpty()) throw new IllegalArgumentException("CSV is empty");

        List<String> header = new ArrayList<>();
        for (String h : recs.get(0).cells()) header.add(h.trim().toLowerCase().replace(" ", "_"));
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < recs.size(); r++) {
            Map<String, String> m = new HashMap<>();
            List<String> cells = recs.get(r).cells();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                String v = cells.get(c).trim();
                if (!v.isEmpty()) m.put(header.get(c), v);
            }
            rows.add(new Row(recs.get(r).line(), m));
        }
        return new Parsed(header, rows);
    }

    private static char detect(String text) {
        int end = text.indexOf((char) 10);
        String first = end < 0 ? text : text.substring(0, end);
        long semi = first.chars().filter(ch -> ch == (int) 59).count();
        long comma = first.chars().filter(ch -> ch == (int) 44).count();
        long tab = first.chars().filter(ch -> ch == (int) 9).count();
        if (tab > semi && tab > comma) return (char) 9;
        return semi > comma ? (char) 59 : (char) 44;
    }
}
