package com.example.cesop.submission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Status history of one stored CESOP message. status: STORED, READY_FOR_UPLOAD, SUBMITTED, ACCEPTED, REJECTED. */
public record SubmissionRecord(String messageRefId, String messageTypeIndic, String transmittingCountry,
                               Integer quarter, Integer year, String status, String channel, String note,
                               String responseMessage, List<String> payeeDocRefIds,
                               String createdAt, String updatedAt) {

    public SubmissionRecord withStatus(String status, String channel, String note, String now) {
        return new SubmissionRecord(messageRefId, messageTypeIndic, transmittingCountry, quarter, year, status,
                channel, note, responseMessage, payeeDocRefIds, createdAt, now);
    }

    public SubmissionRecord withResponse(String status, String response, String now) {
        return new SubmissionRecord(messageRefId, messageTypeIndic, transmittingCountry, quarter, year, status,
                this.channel, note, response, payeeDocRefIds, createdAt, now);
    }

    Properties toProperties() {
        Properties p = new Properties();
        put(p, "messageRefId", messageRefId);
        put(p, "messageTypeIndic", messageTypeIndic);
        put(p, "transmittingCountry", transmittingCountry);
        put(p, "quarter", quarter == null ? null : quarter.toString());
        put(p, "year", year == null ? null : year.toString());
        put(p, "status", status);
        put(p, "channel", channel);
        put(p, "note", note);
        put(p, "responseMessage", responseMessage);
        put(p, "payeeDocRefIds", payeeDocRefIds == null ? null : String.join(",", payeeDocRefIds));
        put(p, "createdAt", createdAt);
        put(p, "updatedAt", updatedAt);
        return p;
    }

    static SubmissionRecord fromProperties(Properties p) {
        String docs = p.getProperty("payeeDocRefIds");
        List<String> ids = docs == null || docs.isBlank() ? new ArrayList<>() : Arrays.asList(docs.split(","));
        String q = p.getProperty("quarter"), y = p.getProperty("year");
        return new SubmissionRecord(p.getProperty("messageRefId"), p.getProperty("messageTypeIndic"),
                p.getProperty("transmittingCountry"), q == null ? null : Integer.valueOf(q),
                y == null ? null : Integer.valueOf(y), p.getProperty("status"), p.getProperty("channel"),
                p.getProperty("note"), p.getProperty("responseMessage"), ids,
                p.getProperty("createdAt"), p.getProperty("updatedAt"));
    }

    private static void put(Properties p, String k, String v) {
        if (v != null) p.setProperty(k, v);
    }
}
