package com.example.cesop.submission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Writes the file into an outbox folder, ready to be uploaded manually or picked up by another tool. */
@Component
public class OutboxChannel implements SubmissionChannel {

    private final Path dir;

    public OutboxChannel(@Value("${cesop.submission.outbox-dir:data/outbox}") String dir) {
        this.dir = Path.of(dir);
    }

    @Override
    public String name() {
        return "outbox";
    }

    @Override
    public Outcome submit(SubmissionRecord record, byte[] xml) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(SubmissionStore.checkId(record.messageRefId()) + ".xml");
        Files.write(target, xml);
        return new Outcome("READY_FOR_UPLOAD",
                "Saved to " + target + ". Upload it in the tax authority portal, then record the answer with /response.");
    }
}
