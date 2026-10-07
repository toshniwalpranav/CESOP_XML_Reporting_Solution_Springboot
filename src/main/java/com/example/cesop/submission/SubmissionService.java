package com.example.cesop.submission;

import com.example.cesop.model.CesopModel.*;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.service.CesopResubmissionService;
import com.example.cesop.service.CesopValidationService;
import com.example.cesop.service.ValidationFailedException;
import com.example.cesop.xml.CesopXmlReader;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Step 5: store each message, hand it to a channel, track the tax authority answer, build corrections from history. */
@Service
public class SubmissionService {

    private final CesopValidationService validator;
    private final CesopXmlReader reader;
    private final SubmissionStore store;
    private final CesopResubmissionService resubmission;
    private final SubmissionChannel channel;

    public SubmissionService(CesopValidationService validator, CesopXmlReader reader, SubmissionStore store,
                             CesopResubmissionService resubmission, List<SubmissionChannel> channels,
                             @Value("${cesop.submission.channel:outbox}") String channelName) {
        this.validator = validator;
        this.reader = reader;
        this.store = store;
        this.resubmission = resubmission;
        this.channel = channels.stream().filter(c -> c.name().equals(channelName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown cesop.submission.channel: " + channelName));
    }

    public SubmissionRecord submit(byte[] xml) {
        ValidationResult r = validator.validate(xml);
        if (!r.valid()) throw new ValidationFailedException(r);

        Message m = reader.read(xml, 1).message();
        String id = SubmissionStore.checkId(m.messageRefId());
        if (store.exists(id)) throw new IllegalArgumentException("A message with MessageRefId " + id + " is already stored");

        String now = now();
        List<String> docIds = m.payees() == null ? List.of()
                : m.payees().stream().map(Payee::docRefId).collect(Collectors.toList());
        SubmissionRecord rec = new SubmissionRecord(id, m.messageTypeIndic(), m.transmittingCountry(), m.quarter(),
                m.year(), "STORED", channel.name(), null, null, docIds, now, now);
        store.saveXml(id, xml);
        try {
            SubmissionChannel.Outcome o = channel.submit(rec, xml);
            rec = rec.withStatus(o.status(), channel.name(), o.note(), now());
        } catch (IOException e) {
            rec = rec.withStatus("STORED", channel.name(), "Channel failed: " + e.getMessage(), now());
        }
        store.saveRecord(rec);
        return rec;
    }

    public List<SubmissionRecord> list() {
        return store.list();
    }

    public SubmissionRecord get(String id) {
        return store.find(id).orElseThrow(() -> new NotFoundException("No stored message " + id));
    }

    public byte[] xml(String id) {
        get(id);
        return store.xml(id);
    }

    public SubmissionRecord recordResponse(String id, String status, String message) {
        SubmissionRecord rec = get(id);
        String s = status == null ? "" : status.toUpperCase();
        if (!s.equals("ACCEPTED") && !s.equals("REJECTED")) {
            throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
        }
        SubmissionRecord updated = rec.withResponse(s, message, now());
        store.saveRecord(updated);
        return updated;
    }

    /** Builds a CESOP101 from the STORED original, so the caller no longer has to supply the old file. */
    public Generated correction(String id, ResubmissionRequest req) {
        SubmissionRecord rec = get(id);
        if ("REJECTED".equals(rec.status())) {
            throw new IllegalArgumentException("Message " + id + " was rejected, so there is nothing to correct. Fix it and submit it as a new message.");
        }
        return resubmission.build(store.xml(id), req, false);
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
