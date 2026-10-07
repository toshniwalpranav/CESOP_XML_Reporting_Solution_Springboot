package com.example.cesop;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cesop.config.CesopProperties;
import com.example.cesop.model.CesopModel.*;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.service.*;
import com.example.cesop.xml.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class SampleFilesTest {

    static final Path DIR = Path.of("src/test/resources/samples");
    final CesopProperties props = new CesopProperties(null, null, null, null);
    final CesopValidationService validator;
    final CesopXmlReader reader = new CesopXmlReader();
    final CesopXmlWriter writer = new CesopXmlWriter(props);
    final CesopResubmissionService resub;

    SampleFilesTest() throws Exception {
        validator = new CesopValidationService(props, new DefaultResourceLoader());
        resub = new CesopResubmissionService(reader, new CesopExportService(writer, validator));
    }

    @Test
    void allOfficialSamplesAreValid() throws Exception {
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                ValidationResult r = validator.validate(Files.readAllBytes(f));
                assertTrue(r.valid(), f.getFileName() + " -> " + r.issues());
            }
        }
    }

    @Test
    void readThenWriteRoundTripStaysValid() throws Exception {
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                ExportRequest model = reader.read(Files.readAllBytes(f), 1);
                var g = writer.write(model);
                ValidationResult r = validator.validate(g.xml());
                assertTrue(r.valid(), f.getFileName() + " roundtrip -> " + r.issues());
            }
        }
    }

    @Test
    void detectsBrokenMessage() {
        String bad = """
            <?xml version="1.0"?>
            <cesop:CESOP xmlns:cesop="urn:ec.europa.eu:taxud:fiscalis:cesop:v1" version="4.03">
              <cesop:MessageSpec><cesop:MessageType>PMT</cesop:MessageType>
              <cesop:MessageTypeIndic>CESOP101</cesop:MessageTypeIndic></cesop:MessageSpec>
              <cesop:PaymentDataBody/>
            </cesop:CESOP>""";
        ValidationResult r = validator.validate(bad.getBytes());
        assertFalse(r.valid());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("CORR_MESSAGE_REF")));
    }

    @Test
    void deletionResubmissionMatchesSampleShape() throws Exception {
        byte[] original = Files.readAllBytes(DIR.resolve("Valid_initial_message.xml"));
        var g = resub.build(original, new ResubmissionRequest(ResubmissionAction.DELETE_PAYEE, null,
                "660f1ecb-1764-40b8-8153-72793e2af465", null, null, null, null), false);
        ExportRequest out = reader.read(g.xml(), 1);
        assertEquals("CESOP101", out.message().messageTypeIndic());
        assertEquals("1feeb77a-339f-4228-a12f-5f2d295def8e", out.message().corrMessageRefId());
        Payee p = out.message().payees().get(0);
        assertEquals("CESOP3", p.docTypeIndic());
        assertEquals("660f1ecb-1764-40b8-8153-72793e2af465", p.corrDocRefId());
        assertTrue(p.transactions().isEmpty());
    }

    @Test
    void refundCorrectionPatchesTransaction() throws Exception {
        byte[] original = Files.readAllBytes(DIR.resolve("Valid_initial_message.xml"));
        var g = resub.build(original, new ResubmissionRequest(ResubmissionAction.CORRECT_PAYEE, null,
                "660f1ecb-1764-40b8-8153-72793e2af465", null, null,
                List.of(new TransactionPatch("13b024c3-f93c-4e47-8fc4-c932c577c03f", true, null)), null), false);
        Payee p = reader.read(g.xml(), 1).message().payees().get(0);
        assertEquals("CESOP2", p.docTypeIndic());
        assertTrue(p.transactions().get(0).refund());
        assertEquals(0, new BigDecimal("-100.00").compareTo(p.transactions().get(0).amount()));
    }

    @Test
    void pspCorrectionHasNoPayees() throws Exception {
        byte[] original = Files.readAllBytes(DIR.resolve("Valid_initial_message.xml"));
        Psp psp = new Psp("BIC", null, "TRSYDEFFXXX", "360 TREASURY SYSTEMS Germany SA", "LEGAL");
        var g = resub.build(original, new ResubmissionRequest(ResubmissionAction.CORRECT_PSP, null,
                null, psp, null, null, null), false);
        Message m = reader.read(g.xml(), 1).message();
        assertTrue(m.payees().isEmpty());
        assertEquals("360 TREASURY SYSTEMS Germany SA", m.reportingPsp().name());
    }

    @Test
    void unknownTargetIsRejected() throws Exception {
        byte[] original = Files.readAllBytes(DIR.resolve("Valid_initial_message.xml"));
        assertThrows(IllegalArgumentException.class, () -> resub.build(original,
                new ResubmissionRequest(ResubmissionAction.DELETE_PAYEE, null, "nope", null, null, null, null), false));
    }
}
