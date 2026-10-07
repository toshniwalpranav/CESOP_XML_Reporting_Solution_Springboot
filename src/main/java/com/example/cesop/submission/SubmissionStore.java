package com.example.cesop.submission;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps every message on disk as ID.xml and ID.properties. No database needed. */
@Component
public class SubmissionStore {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private final Path dir;

    public SubmissionStore(@Value("${cesop.submission.dir:data/submissions}") String dir) {
        this.dir = Path.of(dir);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** MessageRefId becomes a file name, so it must not contain path characters. */
    public static String checkId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("MessageRefId contains characters that are not allowed in a file name: " + id);
        }
        return id;
    }

    public boolean exists(String id) {
        return Files.exists(dir.resolve(checkId(id) + ".properties"));
    }

    public void saveXml(String id, byte[] xml) {
        try {
            Files.write(dir.resolve(checkId(id) + ".xml"), xml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void saveRecord(SubmissionRecord r) {
        try (Writer w = Files.newBufferedWriter(dir.resolve(checkId(r.messageRefId()) + ".properties"), StandardCharsets.UTF_8)) {
            r.toProperties().store(w, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<SubmissionRecord> find(String id) {
        Path f = dir.resolve(checkId(id) + ".properties");
        if (!Files.exists(f)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            return Optional.of(SubmissionRecord.fromProperties(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] xml(String id) {
        Path f = dir.resolve(checkId(id) + ".xml");
        try {
            if (!Files.exists(f)) throw new NotFoundException("No stored message " + id);
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<SubmissionRecord> list() {
        List<SubmissionRecord> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".properties")).toList()) {
                String name = p.getFileName().toString();
                find(name.substring(0, name.length() - ".properties".length())).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(SubmissionRecord::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }
}
