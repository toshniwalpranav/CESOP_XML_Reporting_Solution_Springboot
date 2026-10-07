package com.example.cesop.submission;

import java.io.IOException;

/**
 * Pluggable transport to a tax authority. Only the file outbox is implemented, because the real
 * interface of each authority (for example BZSt DIP) needs its own credentials, certificates and
 * technical specification. Add one class per country and select it with cesop.submission.channel.
 */
public interface SubmissionChannel {

    record Outcome(String status, String note) {}

    String name();

    Outcome submit(SubmissionRecord record, byte[] xml) throws IOException;
}
