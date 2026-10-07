package com.example.cesop.collect;

import com.example.cesop.collect.CollectModel.*;
import com.example.cesop.model.CesopModel.*;
import com.example.cesop.service.CesopExportService;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Steps 1 + 2 of a CESOP solution: read payments from CSV, decide which payees are reportable
 * (cross-border + threshold), and build the report model that the existing exporter turns into XML.
 */
@Service
public class CollectService {

    static final Set<String> EU = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "EL",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");
    private static final List<String> REQUIRED = List.of("transaction_id", "date_time", "amount", "currency",
            "payer_country", "payee_name", "payee_account", "payment_method");

    public record Result(CollectReport report, ExportRequest request) {}

    private static final class Pay {
        Transaction tx;
        String name, country, accType, account, accCountry, vat, vatCountry, email, web, addrFree, addrCountry;
    }

    private static final class Group {
        Pay first;
        final List<Transaction> txs = new ArrayList<>();
        int counted;
    }

    private final CesopExportService exporter;
    private final int defaultThreshold;
    private final String defaultCountry;

    public CollectService(CesopExportService exporter,
                          @Value("${cesop.collect.default-threshold:25}") int defaultThreshold,
                          @Value("${cesop.default-transmitting-country:DE}") String defaultCountry) {
        this.exporter = exporter;
        this.defaultThreshold = defaultThreshold;
        this.defaultCountry = defaultCountry;
    }

    // ------------------------------------------------------------------ public API

    public CollectReport preview(byte[] csv, CollectConfig cfg) {
        return analyse(csv, cfg).report();
    }

    public Generated export(byte[] csv, CollectConfig cfg, boolean ignoreRowErrors) {
        Result r = analyse(csv, cfg);
        if (r.report().rowsWithErrors() > 0 && !ignoreRowErrors) throw new CollectException(r.report());
        return exporter.export(r.request(), false);
    }

    public Result analyse(byte[] csv, CollectConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("config is required");
        if (cfg.quarter() == null || cfg.quarter() < 1 || cfg.quarter() > 4) throw new IllegalArgumentException("config.quarter must be 1-4");
        if (cfg.year() == null) throw new IllegalArgumentException("config.year is required");
        if (cfg.reportingPsp() == null) throw new IllegalArgumentException("config.reportingPsp is required");
        int threshold = cfg.threshold() == null ? defaultThreshold : cfg.threshold();
        boolean countRefunds = Boolean.TRUE.equals(cfg.countRefundsForThreshold());

        CsvReader.Parsed parsed = CsvReader.parse(csv);
        List<String> missing = new ArrayList<>(REQUIRED);
        missing.removeAll(parsed.header());
        if (!missing.isEmpty()) throw new IllegalArgumentException("CSV is missing required column(s): " + missing);

        List<String> errors = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        int outside = 0, payerOut = 0, notCross = 0, crossRows = 0;

        for (CsvReader.Row row : parsed.rows()) {
            Pay p;
            try {
                p = toPay(row.values());
            } catch (IllegalArgumentException e) {
                errors.add("line " + row.line() + ": " + e.getMessage());
                continue;
            }
            OffsetDateTime when = Instant.parse(p.tx.dateTime()).atOffset(ZoneOffset.UTC);
            int q = (when.getMonthValue() - 1) / 3 + 1;
            if (when.getYear() != cfg.year() || q != cfg.quarter()) { outside++; continue; }
            if (!EU.contains(p.tx.payerMS())) { payerOut++; continue; }
            if (p.tx.payerMS().equals(p.accCountry)) { notCross++; continue; }

            crossRows++;
            Group g = groups.computeIfAbsent(p.accType + "|" + p.account, k -> new Group());
            if (g.first == null) g.first = p;
            g.txs.add(p.tx);
            if (countRefunds || !Boolean.TRUE.equals(p.tx.refund())) g.counted++;
        }

        List<PayeeSummary> summaries = new ArrayList<>();
        List<Payee> payees = new ArrayList<>();
        for (Group g : groups.values()) {
            boolean reportable = g.counted > threshold;
            Pay f = g.first;
            summaries.add(new PayeeSummary(f.accType, f.account, f.name, f.accCountry, g.txs.size(), g.counted, reportable));
            if (reportable) payees.add(toPayee(f, g.txs));
        }

        String indic = payees.isEmpty() ? "CESOP102" : "CESOP100";
        CollectReport report = new CollectReport(parsed.rows().size(), errors.size(), outside, payerOut, notCross,
                crossRows, threshold, groups.size(), payees.size(), indic, summaries, errors);
        Message m = new Message(cfg.transmittingCountry() == null ? defaultCountry : cfg.transmittingCountry(),
                indic, null, null, cfg.sendingPsp(), cfg.quarter(), cfg.year(), null, cfg.reportingPsp(), payees);
        return new Result(report, new ExportRequest(cfg.header(), m));
    }

    // ------------------------------------------------------------------ row mapping

    private Payee toPayee(Pay f, List<Transaction> txs) {
        Address address = f.addrFree == null ? null
                : new Address(f.addrCountry == null ? f.country : f.addrCountry, "CESOP303", f.addrFree, null);
        List<Vat> vats = f.vat == null ? List.of()
                : List.of(new Vat(f.vatCountry == null ? f.country : f.vatCountry, f.vat));
        return new Payee(f.name, "BUSINESS", f.country, address, f.email, f.web, vats,
                List.of(new Account(f.accCountry, f.accType, f.account)), txs, "CESOP1", null, null);
    }

    private Pay toPay(Map<String, String> v) {
        Pay p = new Pay();
        String id = req(v, "transaction_id");
        Instant when = parseWhen(req(v, "date_time"));
        BigDecimal amount = parseAmount(req(v, "amount"));
        String ccy = req(v, "currency").toUpperCase();
        boolean refund = parseBool(v.get("is_refund"), "is_refund");
        if (refund) amount = amount.abs().negate(); // refunds are reported as negative amounts
        String payer = req(v, "payer_country").toUpperCase();

        p.name = req(v, "payee_name");
        p.accType = v.getOrDefault("payee_account_type", "IBAN");
        p.account = req(v, "payee_account").replace(" ", "").toUpperCase();
        String ac = v.get("payee_account_country");
        if (ac == null && "IBAN".equalsIgnoreCase(p.accType) && p.account.length() >= 2) ac = p.account.substring(0, 2);
        if (ac == null) throw new IllegalArgumentException("payee_account_country is required when the account is not an IBAN");
        p.accCountry = ac.toUpperCase();
        p.country = v.containsKey("payee_country") ? v.get("payee_country").toUpperCase() : p.accCountry;
        p.vat = v.get("payee_vat");
        p.vatCountry = v.containsKey("payee_vat_country") ? v.get("payee_vat_country").toUpperCase() : null;
        p.email = v.get("payee_email");
        p.web = v.get("payee_web");
        p.addrFree = v.get("payee_address");
        p.addrCountry = v.containsKey("payee_address_country") ? v.get("payee_address_country").toUpperCase() : null;

        p.tx = new Transaction(refund, id, when.truncatedTo(ChronoUnit.SECONDS).toString(), v.get("date_type"),
                amount, ccy, req(v, "payment_method"), v.get("payment_method_other"),
                parseBool(v.get("initiated_physical"), "initiated_physical"), payer, v.get("payer_source"), v.get("psp_role"));
        return p;
    }

    private static String req(Map<String, String> v, String key) {
        String s = v.get(key);
        if (s == null) throw new IllegalArgumentException("missing value for " + key);
        return s;
    }

    private static BigDecimal parseAmount(String s) {
        try {
            String t = s.replace(" ", "");
            if (t.contains(",") && !t.contains(".")) t = t.replace(",", ".");
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount is not a number: " + s);
        }
    }

    private static boolean parseBool(String s, String col) {
        if (s == null) return false;
        String t = s.toLowerCase();
        if (t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("y")) return true;
        if (t.equals("false") || t.equals("0") || t.equals("no") || t.equals("n")) return false;
        throw new IllegalArgumentException(col + " must be true/false: " + s);
    }

    static Instant parseWhen(String raw) {
        String s = raw.trim().replace(" ", "T");
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        try { return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        throw new IllegalArgumentException("date_time is not ISO format: " + raw);
    }
}
