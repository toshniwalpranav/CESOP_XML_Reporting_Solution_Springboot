package com.example.cesop;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cesop.collect.CollectModel.CollectConfig;
import com.example.cesop.collect.CollectService;
import com.example.cesop.config.CesopProperties;
import com.example.cesop.model.CesopModel.*;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.service.*;
import com.example.cesop.xml.CesopXmlReader;
import com.example.cesop.xml.CesopXmlWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Tests for the official BZSt/EU XSD files (DIP v2 + CESOP v4.03) and the EU business rules. */
class OfficialXsdAndRulesTest {

    static final List<String> OFFICIAL_XSDS = List.of(
            "classpath:xsd/Amtlicher_Datensatz_CESOP/dip.xsd",
            "classpath:xsd/Amtlicher_Datensatz_CESOP/cesop_PaymentData.xsd");   // BOTH are needed: dip:data is xs:anyType
    static final Path SAMPLES = Path.of("src/test/resources/samples");

    final CesopValidationService withXsd;
    final CesopValidationService builtInOnly;

    OfficialXsdAndRulesTest() throws Exception {
        withXsd = new CesopValidationService(new CesopProperties(OFFICIAL_XSDS, null, null, null), new DefaultResourceLoader());
        builtInOnly = new CesopValidationService(new CesopProperties(null, null, null, null), new DefaultResourceLoader());
    }

    static String sample() throws Exception {
        return Files.readString(SAMPLES.resolve("Valid_initial_message.xml"), StandardCharsets.UTF_8);
    }

    static boolean has(ValidationResult r, String code) {
        return r.issues().stream().anyMatch(i -> code.equals(i.code()));
    }

    static ValidationResult check(CesopValidationService v, String xml) {
        return v.validate(xml.getBytes(StandardCharsets.UTF_8));
    }

    static String change(String xml, String from, String to) {
        assertTrue(xml.contains(from), "sample does not contain: " + from);
        return xml.replace(from, to);
    }

    @Test
    void officialXsdIsSwitchedOn() {
        assertTrue(withXsd.xsdEnabled());
        assertFalse(builtInOnly.xsdEnabled());
    }

    @Test
    void allOfficialSamplesPassTheOfficialXsdAndRules() throws Exception {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                ValidationResult r = withXsd.validate(Files.readAllBytes(f));
                assertTrue(r.valid(), f.getFileName() + " -> " + r.issues());
            }
        }
    }

    @Test
    void officialXsdCatchesWrongQuarterAndMessageType() throws Exception {
        String xml = change(change(sample(), "<cesop:Quarter>4</cesop:Quarter>", "<cesop:Quarter>7</cesop:Quarter>"),
                "CESOP100</cesop:MessageTypeIndic>", "CESOP999</cesop:MessageTypeIndic>");
        ValidationResult r = check(withXsd, xml);
        assertFalse(r.valid());
        assertTrue(has(r, "XSD"), r.issues().toString());
    }

    @Test
    void generatedReportPassesTheOfficialXsd() throws Exception {
        CesopProperties props = new CesopProperties(OFFICIAL_XSDS, null, null, null);
        CesopExportService exporter = new CesopExportService(new CesopXmlWriter(props), withXsd);
        CollectService collect = new CollectService(exporter, 25, "DE");
        CollectConfig cfg = new CollectConfig(
                new DipHeader("TEST", "BZST-CERT", "BZ123456789", null, null, "a"), null,
                new Psp("BIC", null, "TRSYDEFFXXX", "360 TREASURY SYSTEMS AG", "LEGAL"), 4, 2024, null, 25, null);
        // export() validates against the official XSD and throws when the file is not valid
        var g = collect.export(Files.readAllBytes(Path.of("examples/payments-sample.csv")), cfg, false);
        ValidationResult r = withXsd.validate(g.xml());
        assertTrue(r.valid(), r.issues().toString());
    }

    // ---------------------------------------------------------------- EU business rules

    @Test
    void ruleIbanChecksum() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), "DE32503302010298200040", "DE33503302010298200040"));
        assertTrue(has(r, "RP-BR-0030"), r.issues().toString());
    }

    @Test
    void ruleMustBeCrossBorder() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), ">FR</cesop:PayerMS>", ">DE</cesop:PayerMS>"));
        assertTrue(has(r, "RP-BR-0010"), r.issues().toString());
    }

    @Test
    void ruleRefundMustBeNegative() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), "IsRefund=\"false\"", "IsRefund=\"true\""));
        assertTrue(has(r, "RT-BR-0010"), r.issues().toString());
    }

    @Test
    void ruleReportingPeriodNotBefore2024() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), "<cesop:Year>2024</cesop:Year>", "<cesop:Year>2023</cesop:Year>"));
        assertTrue(has(r, "MH-BR-0030"), r.issues().toString());
    }

    @Test
    void ruleMessageRefIdMustBeUuidV4() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), "1feeb77a-339f-4228-a12f-5f2d295def8e", "1feeb77a-339f-1228-a12f-5f2d295def8e"));
        assertTrue(has(r, "MH-BR-0050"), r.issues().toString());
    }

    @Test
    void ruleCorrectionMessageCannotContainNewPayee() throws Exception {
        ValidationResult r = check(builtInOnly, change(sample(), "CESOP100</cesop:MessageTypeIndic>", "CESOP101</cesop:MessageTypeIndic>"));
        assertTrue(has(r, "MH-BR-0080"), r.issues().toString());
    }

    @Test
    void ruleSamePayeeTwiceInOneMessage() throws Exception {
        String xml = sample();
        int s = xml.indexOf("<cesop:ReportedPayee>");
        int e = xml.indexOf("</cesop:ReportedPayee>") + "</cesop:ReportedPayee>".length();
        String payee = xml.substring(s, e);
        // second copy gets new DocRefId / TransactionIdentifier so only CM-BR-0150 is triggered
        String copy = payee.replace("660f1ecb-1764-40b8-8153-72793e2af465", "770f1ecb-1764-40b8-8153-72793e2af466")
                .replace("13b024c3-f93c-4e47-8fc4-c932c577c03f", "23b024c3-f93c-4e47-8fc4-c932c577c040");
        ValidationResult r = check(builtInOnly, xml.substring(0, e) + copy + xml.substring(e));
        assertTrue(has(r, "CM-BR-0150"), r.issues().toString());
    }

    @Test
    void correctionFilesPassTheOfficialXsd() throws Exception {
        CesopProperties props = new CesopProperties(OFFICIAL_XSDS, null, null, null);
        CesopResubmissionService resub = new CesopResubmissionService(new CesopXmlReader(),
                new CesopExportService(new CesopXmlWriter(props), withXsd));
        byte[] original = Files.readAllBytes(SAMPLES.resolve("Valid_initial_message.xml"));
        String payeeDoc = "660f1ecb-1764-40b8-8153-72793e2af465";
        // build() validates against the official XSD + business rules and throws when the result is not valid
        var del = resub.build(original, new ResubmissionRequest(ResubmissionAction.DELETE_PAYEE, null, payeeDoc, null, null, null, null), false);
        assertTrue(withXsd.validate(del.xml()).valid());
        var refund = resub.build(original, new ResubmissionRequest(ResubmissionAction.CORRECT_PAYEE, null, payeeDoc, null, null,
                List.of(new TransactionPatch("13b024c3-f93c-4e47-8fc4-c932c577c03f", true, null)), null), false);
        assertTrue(withXsd.validate(refund.xml()).valid());
        Psp psp = new Psp("BIC", null, "TRSYDEFFXXX", "360 TREASURY SYSTEMS Germany SA", "LEGAL");
        var pspFix = resub.build(original, new ResubmissionRequest(ResubmissionAction.CORRECT_PSP, null, null, psp, null, null, null), false);
        assertTrue(withXsd.validate(pspFix.xml()).valid());
    }
}
