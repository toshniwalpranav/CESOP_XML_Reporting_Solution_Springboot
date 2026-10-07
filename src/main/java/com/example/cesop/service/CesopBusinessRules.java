package com.example.cesop.service;

import static com.example.cesop.xml.Dom.*;

import com.example.cesop.model.Issue;
import com.example.cesop.model.Issue.Severity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Business rules from the "CESOP XSD User Guide v6.00", section 4 (error codes 10xxx - 45xxx).
 * Only rules that can be decided from ONE message are checked here (the EU Validation Module
 * calls them "stateless"). Rules that need the CESOP data store (for example "MessageRefId is
 * unique over time", "CorrMessageRefId refers to a known message") cannot be checked here.
 *
 * Each issue uses the official rule id as its code (for example RP-BR-0030) and names the
 * official error code in the message.
 *
 * Written from the public rule descriptions; no code of the EU Validation Module is used.
 */
final class CesopBusinessRules {

    private CesopBusinessRules() {}

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Pattern IBAN_FORMAT = Pattern.compile("^[A-Z]{2}[0-9]{2}[0-9A-Za-z]{10,30}$");
    private static final Set<String> NOT_OTHER = Set.of("IBAN", "BIC", "OBAN");

    /** IBAN length per country (SWIFT IBAN registry). Countries not listed: only the checksum is tested. */
    private static final Map<String, Integer> IBAN_LENGTH = new HashMap<>();
    static {
        String data = "AD24 AE23 AL28 AT20 AZ28 BA20 BE16 BG22 BH22 BR29 BY28 CH21 CR22 CY28 CZ24 DE22 DK18 DO28 "
                + "EE20 EG29 ES24 FI18 FO18 FR27 GB22 GE22 GI23 GL18 GR27 GT28 HR21 HU28 IE22 IL23 IQ23 IS26 IT27 "
                + "JO30 KW30 KZ20 LB28 LC32 LI21 LT20 LU20 LV21 MC27 MD24 ME22 MK19 MR27 MT31 MU30 NL18 NO15 PK24 "
                + "PL28 PS29 PT25 QA29 RO24 RS22 SA24 SC31 SE24 SI19 SK24 SM27 ST25 SV28 TL23 TN24 TR26 UA29 VA22 "
                + "VG24 XK20";
        for (String t : data.split(" ")) IBAN_LENGTH.put(t.substring(0, 2), Integer.parseInt(t.substring(2)));
    }

    static void check(Element root, String path, String msgIndic, List<Issue> out) {
        Element ms = kid(root, CESOP, "MessageSpec");
        Element body = kid(root, CESOP, "PaymentDataBody");
        if (ms == null || body == null) return;
        String mp = path + "/MessageSpec";

        // MH-BR-0030: reporting period not before Q1 2024
        Element period = kid(ms, CESOP, "ReportingPeriod");
        Integer quarter = null, year = null;
        if (period != null) {
            quarter = intOrNull(text(period, CESOP, "Quarter"));
            year = intOrNull(text(period, CESOP, "Year"));
            if (year != null && year < 2024) {
                out.add(err("MH-BR-0030", mp + "/ReportingPeriod",
                        "The reporting period must not be earlier than Q1 2024 (error code 10030)"));
            }
        }
        // MH-BR-0060: CorrMessageRefId must be a UUID v4
        String corr = text(ms, CESOP, "CorrMessageRefId");
        if (corr != null && !UUID_V4.matcher(corr).matches()) {
            out.add(err("MH-BR-0060", mp + "/CorrMessageRefId",
                    "CorrMessageRefId must be a UUID version 4 (error code 10060): " + corr));
        }

        List<Element> payees = kids(body, CESOP, "ReportedPayee");
        Map<String, Integer> payeeKeys = new HashMap<>();

        for (int i = 0; i < payees.size(); i++) {
            Element p = payees.get(i);
            String pp = path + "/PaymentDataBody/ReportedPayee[" + (i + 1) + "]";
            Element doc = kid(p, CESOP, "DocSpec");
            String docIndic = doc == null ? null : text(doc, CM, "DocTypeIndic");

            // MH-BR-0080: a correction message can only contain CESOP2 / CESOP3
            if ("CESOP101".equals(msgIndic) && "CESOP1".equals(docIndic)) {
                out.add(err("MH-BR-0080", pp + "/DocSpec",
                        "A correction message (CESOP101) can only contain DocTypeIndic CESOP2 and/or CESOP3 (error code 10080)"));
            }

            String payeeCountry = text(p, CESOP, "Country");
            List<Element> accounts = kids(p, CESOP, "AccountIdentifier");
            boolean hasRepresentative = kid(p, CESOP, "Representative") != null;

            // RP-BR-0080: account identifier OR representative, not both
            if (!accounts.isEmpty() && hasRepresentative) {
                out.add(err("RP-BR-0080", pp,
                        "AccountIdentifier and Representative cannot both be provided (error code 40080)"));
            }

            accountRules(accounts, pp, out);

            // CM-BR-0150: same payee (same name and same account) under two ReportedPayee elements
            if (!"CESOP3".equals(docIndic)) {
                String key = payeeKey(p, accounts);
                if (key != null) {
                    Integer first = payeeKeys.putIfAbsent(key, i + 1);
                    if (first != null) {
                        out.add(err("CM-BR-0150", pp,
                                "The same payee (same name and account) is also reported in ReportedPayee[" + first
                                        + "]; all its transactions must be under one ReportedPayee (error code 20150)"));
                    }
                }
            }

            // transaction rules
            List<Element> txs = kids(p, CESOP, "ReportedTransaction");
            for (int j = 0; j < txs.size(); j++) {
                transactionRules(txs.get(j), pp + "/ReportedTransaction[" + (j + 1) + "]", payeeCountry, quarter, year, out);
            }
        }
    }

    // ------------------------------------------------------------------ account rules

    private static void accountRules(List<Element> accounts, String pp, List<Issue> out) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            Element a = accounts.get(i);
            String ap = pp + "/AccountIdentifier[" + (i + 1) + "]";
            String type = attr(a, "type");
            String value = text(a);
            if (type != null) types.add(type);

            // RP-BR-0060: CountryCode and type are mandatory when an AccountIdentifier is provided
            if (value != null && attr(a, "CountryCode") == null) {
                out.add(err("RP-BR-0060", ap, "Attribute CountryCode is mandatory on AccountIdentifier (error code 40060)"));
            }

            // CM-BR-0140 / RP-BR-0110: 'Other' type needs its specification, and must not name IBAN/BIC/OBAN
            String other = attr(a, "accountIdentifierOther");
            if ("Other".equals(type) && other == null) {
                out.add(err("CM-BR-0140", ap, "accountIdentifierOther is required when type=\"Other\" (error code 20140)"));
            }
            if (type != null && !"Other".equals(type) && other != null) {
                out.add(err("CM-BR-0140", ap, "accountIdentifierOther is only allowed when type=\"Other\" (error code 20140)"));
            }
            if (other != null && NOT_OTHER.contains(other.trim().toUpperCase())) {
                out.add(err("RP-BR-0110", ap, "accountIdentifierOther must not be IBAN, BIC or OBAN (error code 40110)"));
            }

            // RP-BR-0020 / RP-BR-0030: IBAN format and checksum
            if ("IBAN".equals(type) && value != null) {
                String iban = value.replace(" ", "");
                if (!IBAN_FORMAT.matcher(iban).matches()) {
                    out.add(err("RP-BR-0020", ap, "Wrong IBAN format: 2 letters, 2 digits, then 10 to 30 letters or digits (error code 40020)"));
                } else {
                    String cc = iban.substring(0, 2);
                    Integer len = IBAN_LENGTH.get(cc);
                    if (len != null && iban.length() != len) {
                        out.add(err("RP-BR-0030", ap, "IBAN length is " + iban.length() + " but " + cc + " IBANs have " + len + " characters (error code 40030)"));
                    } else if (!mod97(iban)) {
                        out.add(err("RP-BR-0030", ap, "IBAN check digits are not correct (error code 40030)"));
                    }
                }
            }
        }

        // RP-BR-0100: allowed account identifier combinations for one payee
        if (!types.isEmpty()) {
            boolean ok;
            Set<String> set = new TreeSet<>(types);
            if (types.size() == 1) {
                ok = set.equals(Set.of("IBAN")) || set.equals(Set.of("OBAN")) || set.equals(Set.of("Other"));
            } else if (types.size() == 2 && set.size() == 2 && set.contains("BIC")) {
                set.remove("BIC");
                ok = set.equals(Set.of("IBAN")) || set.equals(Set.of("OBAN")) || set.equals(Set.of("Other"));
            } else {
                ok = false;
            }
            if (!ok) {
                out.add(err("RP-BR-0100", pp,
                        "Allowed: one IBAN, one OBAN or one Other, optionally together with the BIC of the issuing PSP; found " + types + " (error code 40100)"));
            }
        }
    }

    private static boolean mod97(String iban) {
        String s = (iban.substring(4) + iban.substring(0, 4)).toUpperCase();
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c >= 'A' && c <= 'Z') digits.append(c - 'A' + 10);
            else return false;
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static String payeeKey(Element p, List<Element> accounts) {
        String name = text(p, CESOP, "Name");
        if (name == null || accounts.isEmpty()) return null;
        Set<String> acc = new TreeSet<>();
        for (Element a : accounts) {
            String v = text(a);
            if (v != null) acc.add(v.replace(" ", "").toUpperCase());
        }
        if (acc.isEmpty()) return null;
        return name.trim().toLowerCase() + "|" + acc;
    }

    // ------------------------------------------------------------------ transaction rules

    private static void transactionRules(Element t, String tp, String payeeCountry,
                                         Integer quarter, Integer year, List<Issue> out) {
        // RP-BR-0010: must be cross-border (payee country differs from payer member state)
        String payer = text(t, CESOP, "PayerMS");
        if (payer != null && payeeCountry != null && normalise(payer).equals(normalise(payeeCountry))) {
            out.add(err("RP-BR-0010", tp + "/PayerMS",
                    "Not a cross-border payment: payee country and PayerMS are both " + payer + " (error code 40010)"));
        }

        // RT-BR-0060: amount must not be zero
        String amt = text(t, CESOP, "Amount");
        if (amt != null) {
            try {
                if (new BigDecimal(amt).signum() == 0) {
                    out.add(err("RT-BR-0060", tp + "/Amount", "The amount cannot be zero (error code 45060)"));
                }
            } catch (NumberFormatException ignored) { /* reported by the basic checks */ }
        }

        // RT-BR-0090: CorrTransactionIdentifier only for refunds
        String corrTx = text(t, CESOP, "CorrTransactionIdentifier");
        String refund = attr(t, "IsRefund");
        if (corrTx != null && !("true".equals(refund) || "1".equals(refund))) {
            out.add(err("RT-BR-0090", tp, "CorrTransactionIdentifier is given, so IsRefund must be true (error code 45090)"));
        }

        // RT-BR-0080: same date type more than once; RT-BR-0030: at least one date inside the reporting period
        List<Element> dates = kids(t, CESOP, "DateTime");
        Set<String> seen = new HashSet<>();
        boolean anyInPeriod = false;
        boolean anyParsed = false;
        for (Element d : dates) {
            String type = attr(d, "transactionDateType");
            if (type != null && !seen.add(type)) {
                out.add(err("RT-BR-0080", tp + "/DateTime",
                        "The same date type (" + type + ") is given more than once (error code 45080)"));
            }
            Instant when = parseInstant(text(d));
            if (when != null) {
                anyParsed = true;
                if (quarter != null && year != null) {
                    var z = when.atOffset(ZoneOffset.UTC);
                    if (z.getYear() == year && (z.getMonthValue() - 1) / 3 + 1 == quarter) anyInPeriod = true;
                }
            }
        }
        if (anyParsed && quarter != null && year != null && !anyInPeriod) {
            out.add(err("RT-BR-0030", tp + "/DateTime",
                    "No DateTime of this transaction is inside the reporting period Q" + quarter + "/" + year + " (error code 45030)"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String normalise(String cc) { return "EL".equals(cc) ? "GR" : cc; }

    private static Integer intOrNull(String s) {
        try { return s == null ? null : Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Instant parseInstant(String s) {
        if (s == null) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        return null;
    }

    private static Issue err(String code, String path, String msg) { return new Issue(Severity.ERROR, code, path, msg); }
}
