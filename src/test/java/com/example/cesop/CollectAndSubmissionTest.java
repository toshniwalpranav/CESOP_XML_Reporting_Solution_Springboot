package com.example.cesop;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cesop.collect.CollectException;
import com.example.cesop.collect.CollectModel.CollectConfig;
import com.example.cesop.collect.CollectModel.CollectReport;
import com.example.cesop.collect.CollectService;
import com.example.cesop.config.CesopProperties;
import com.example.cesop.model.CesopModel.*;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.service.*;
import com.example.cesop.submission.*;
import com.example.cesop.xml.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class CollectAndSubmissionTest {

    final CesopProperties props = new CesopProperties(null, null, null, null);
    final CesopValidationService validator;
    final CesopXmlReader reader = new CesopXmlReader();
    final CesopXmlWriter writer = new CesopXmlWriter(props);
    final CesopExportService exporter;
    final CesopResubmissionService resub;
    final CollectService collect;

    CollectAndSubmissionTest() throws Exception {
        validator = new CesopValidationService(props, new DefaultResourceLoader());
        exporter = new CesopExportService(writer, validator);
        resub = new CesopResubmissionService(reader, exporter);
        collect = new CollectService(exporter, 25, "DE");
    }

    static final Psp PSP = new Psp("BIC", null, "TRSYDEFFXXX", "360 TREASURY SYSTEMS AG", "LEGAL");
    static final DipHeader HDR = new DipHeader("TEST", "BZST-CERT", "BZ123456789", null, null, "a");
    static CollectConfig cfg(Integer threshold) {
        return new CollectConfig(HDR, null, PSP, 4, 2024, null, threshold, null);
    }

    static byte[] sampleCsv() throws Exception {
        return Files.readAllBytes(Path.of("examples/payments-sample.csv"));
    }

    @Test
    void thresholdSeparatesReportableFromNot() throws Exception {
        CollectReport r = collect.preview(sampleCsv(), cfg(25));
        assertEquals(41, r.rowsRead());
        assertEquals(0, r.rowsWithErrors());
        assertEquals(1, r.outsidePeriod());
        assertEquals(1, r.payerOutsideEu());
        assertEquals(3, r.notCrossBorder());
        assertEquals(2, r.payeesFound());
        assertEquals(1, r.payeesReportable());
        assertEquals("CESOP100", r.messageTypeIndic());
        // payee A has 31 cross-border rows but only 30 count (refund excluded)
        var a = r.payees().get(0);
        assertEquals(31, a.crossBorderPayments());
        assertEquals(30, a.countedForThreshold());
        assertTrue(a.reportable());
        assertFalse(r.payees().get(1).reportable());
    }

    @Test
    void exportProducesValidCesopXmlWithRefundNegative() throws Exception {
        var g = collect.export(sampleCsv(), cfg(25), false);
        assertTrue(validator.validate(g.xml()).valid());
        Message m = reader.read(g.xml(), 1).message();
        assertEquals("CESOP100", m.messageTypeIndic());
        assertEquals(1, m.payees().size());
        Payee p = m.payees().get(0);
        assertEquals(31, p.transactions().size());
        Transaction refund = p.transactions().get(30);
        assertTrue(refund.refund());
        assertTrue(refund.amount().signum() < 0);
        assertEquals("DE32503302010298200040", p.accounts().get(0).value());
    }

    @Test
    void nobodyOverThresholdGivesNilReport() throws Exception {
        var g = collect.export(sampleCsv(), cfg(100), false);
        assertTrue(validator.validate(g.xml()).valid());
        Message m = reader.read(g.xml(), 1).message();
        assertEquals("CESOP102", m.messageTypeIndic());
        assertTrue(m.payees().isEmpty());
    }

    @Test
    void badRowStopsExportUnlessIgnored() {
        String csv = "transaction_id,date_time,amount,currency,payer_country,payee_name,payee_account,payment_method\n"
                + "T1,2024-11-01T10:00:00Z,abc,EUR,FR,Shop,DE32503302010298200040,Card payment\n";
        CollectException ex = assertThrows(CollectException.class, () -> collect.export(csv.getBytes(), cfg(0), false));
        assertEquals(1, ex.getReport().rowsWithErrors());
        assertTrue(ex.getReport().rowErrors().get(0).startsWith("line 2"));
        assertDoesNotThrowLike(() -> collect.export(csv.getBytes(), cfg(0), true));
    }

    @Test
    void semicolonCsvAndMissingColumnsAreHandled() {
        String ok = "transaction_id;date_time;amount;currency;payer_country;payee_name;payee_account;payment_method\n"
                + "T1;2024-11-01 10:00:00;12,50;EUR;FR;Shop;DE32503302010298200040;Card payment\n";
        CollectReport r = collect.preview(ok.getBytes(), cfg(0));
        assertEquals(0, r.rowsWithErrors());
        assertEquals(1, r.payeesReportable());
        assertThrows(IllegalArgumentException.class, () -> collect.preview("a,b\n1,2\n".getBytes(), cfg(0)));
    }

    @Test
    void submissionStoresTracksAndBuildsCorrection() throws Exception {
        Path tmp = Files.createTempDirectory("cesop-test");
        SubmissionStore store = new SubmissionStore(tmp.resolve("s").toString());
        SubmissionService svc = new SubmissionService(validator, reader, store, resub,
                List.of(new OutboxChannel(tmp.resolve("o").toString())), "outbox");

        byte[] xml = Files.readAllBytes(Path.of("src/test/resources/samples/Valid_initial_message.xml"));
        SubmissionRecord rec = svc.submit(xml);
        assertEquals("READY_FOR_UPLOAD", rec.status());
        assertEquals("1feeb77a-339f-4228-a12f-5f2d295def8e", rec.messageRefId());
        assertTrue(Files.exists(tmp.resolve("o").resolve(rec.messageRefId() + ".xml")));
        assertThrows(IllegalArgumentException.class, () -> svc.submit(xml)); // duplicate

        assertEquals("ACCEPTED", svc.recordResponse(rec.messageRefId(), "accepted", "OK").status());
        assertEquals(1, svc.list().size());

        var corr = svc.correction(rec.messageRefId(), new ResubmissionRequest(ResubmissionAction.DELETE_PAYEE, null,
                "660f1ecb-1764-40b8-8153-72793e2af465", null, null, null, null));
        assertEquals("CESOP101", reader.read(corr.xml(), 1).message().messageTypeIndic());

        // a rejected message cannot be corrected
        svc.recordResponse(rec.messageRefId(), "REJECTED", "bad");
        assertThrows(IllegalArgumentException.class, () -> svc.correction(rec.messageRefId(),
                new ResubmissionRequest(ResubmissionAction.DELETE_PAYEE, null, "x", null, null, null, null)));
    }

    @Test
    void invalidFileAndUnsafeIdsAreRejected() throws Exception {
        Path tmp = Files.createTempDirectory("cesop-test2");
        SubmissionService svc = new SubmissionService(validator, reader, new SubmissionStore(tmp.resolve("s").toString()),
                resub, List.of(new OutboxChannel(tmp.resolve("o").toString())), "outbox");
        assertThrows(ValidationFailedException.class, () -> svc.submit("<x/>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> SubmissionStore.checkId("../../etc/passwd"));
        assertThrows(NotFoundException.class, () -> svc.get("does-not-exist"));
    }

    private static void assertDoesNotThrowLike(org.junit.jupiter.api.function.Executable e) {
        try { e.execute(); } catch (Throwable t) { throw new AssertionError("unexpected: " + t, t); }
    }
}
