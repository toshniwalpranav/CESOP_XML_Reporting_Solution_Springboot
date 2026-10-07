package com.example.cesop.service;

import static com.example.cesop.xml.Dom.*;

import com.example.cesop.config.CesopProperties;
import com.example.cesop.model.Issue;
import com.example.cesop.model.Issue.Severity;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.xml.Dom;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Two layers:
 *  1. optional XSD validation against the official BZSt schemas (cesop.xsd-files),
 *  2. built-in structural + business rules derived from the DIP v2 / CESOP v4.03 sample set.
 *
 * The built-in rules are NOT a replacement for the EU Commission business rules; they cover
 * what the sample files demonstrate. Configure the XSDs for authoritative schema validation.
 */
@Service
public class CesopValidationService {

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"); // UUID version 4
    private static final Pattern CC_RE = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CCY_RE = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern BIC_RE = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Set<String> MESSAGE_INDICS = Set.of("CESOP100", "CESOP101", "CESOP102");
    private static final Set<String> DOC_INDICS = Set.of("CESOP1", "CESOP2", "CESOP3");
    private static final Set<String> PAYMENT_METHODS = Set.of(
            "Card payment", "Bank transfer", "Direct debit", "E-money", "Money remittance", "Other");
    private static final Set<String> ACCOUNT_TYPES = Set.of("IBAN", "OBAN", "BIC", "Other");

    private final Schema schema;

    public CesopValidationService(CesopProperties props, ResourceLoader loader) throws Exception {
        if (props.xsdFiles().isEmpty()) {
            this.schema = null;
        } else {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            List<Source> sources = new ArrayList<>();
            for (String loc : props.xsdFiles()) {
                Resource r = loader.getResource(loc);
                StreamSource ss = new StreamSource(r.getInputStream());
                ss.setSystemId(r.getURI().toString()); // lets xs:include/import resolve relatively
                sources.add(ss);
            }
            this.schema = sf.newSchema(sources.toArray(new Source[0]));
        }
    }

    public boolean xsdEnabled() {
        return schema != null;
    }

    // ------------------------------------------------------------------ entry point

    public ValidationResult validate(byte[] xml) {
        List<Issue> issues = new ArrayList<>();
        Document doc;
        try {
            doc = Dom.parse(xml);
        } catch (Exception e) {
            issues.add(new Issue(Severity.ERROR, "XML_NOT_WELL_FORMED", "/", e.getMessage()));
            return ValidationResult.of(issues);
        }
        if (schema != null) xsd(xml, issues);

        Element root = doc.getDocumentElement();
        if (DIP.equals(root.getNamespaceURI()) && "dip".equals(root.getLocalName())) {
            dip(root, issues);
        } else if (CESOP.equals(root.getNamespaceURI()) && "CESOP".equals(root.getLocalName())) {
            cesop(root, "/CESOP", issues);
        } else {
            issues.add(err("UNKNOWN_ROOT", "/", "Root must be dip:dip (" + DIP + ") or cesop:CESOP (" + CESOP + ")"));
        }
        return ValidationResult.of(issues);
    }

    // ------------------------------------------------------------------ XSD layer

    private void xsd(byte[] xml, List<Issue> issues) {
        try {
            Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) { add(Severity.WARNING, e); }
                public void error(SAXParseException e) { add(Severity.ERROR, e); }
                public void fatalError(SAXParseException e) { add(Severity.ERROR, e); }
                private void add(Severity s, SAXParseException e) {
                    issues.add(new Issue(s, "XSD", "line " + e.getLineNumber() + ", col " + e.getColumnNumber(),
                            e.getMessage()));
                }
            });
            v.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (Exception e) {
            issues.add(err("XSD_FAILURE", "/", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ DIP envelope

    private void dip(Element root, List<Issue> out) {
        if (!"2.0".equals(attr(root, "version"))) {
            out.add(warn("DIP_VERSION", "/dip", "Expected version=\"2.0\""));
        }
        Element header = kid(root, DIP, "header");
        if (header == null) {
            out.add(err("MISSING", "/dip", "dip:header is missing"));
        } else {
            String hp = "/dip/header";
            if (attr(header, "environment") == null) out.add(err("MISSING", hp, "attribute 'environment' is missing"));
            Element consignment = kid(header, DIP, "consignment");
            if (consignment == null) {
                out.add(err("MISSING", hp, "dip:consignment is missing"));
            } else {
                Element cust = kid(consignment, DIP, "customerIdentifier");
                if (text(cust, DIP, "identityProvider") == null) out.add(err("MISSING", hp + "/consignment/customerIdentifier", "identityProvider is missing"));
                if (text(cust, DIP, "identifier") == null) out.add(err("MISSING", hp + "/consignment/customerIdentifier", "identifier is missing"));
                String ct = text(consignment, DIP, "creationTime");
                if (ct == null) out.add(err("MISSING", hp + "/consignment", "creationTime is missing"));
                else if (!isLocalDateTime(ct)) out.add(err("FORMAT", hp + "/consignment/creationTime", "Not an ISO date-time: " + ct));
                String tt = text(consignment, DIP, "transferticketId");
                if (tt == null) out.add(err("MISSING", hp + "/consignment", "transferticketId is missing"));
                else if (!UUID_RE.matcher(tt).matches()) out.add(warn("FORMAT", hp + "/consignment/transferticketId", "Not a UUID: " + tt));
            }
            Element app = kid(header, DIP, "application");
            if (app == null || !"CESOP".equals(attr(app, "code"))) {
                out.add(err("APPLICATION", hp + "/application", "application code must be \"CESOP\""));
            }
        }

        Element body = kid(root, DIP, "body");
        List<Element> items = kids(body, DIP, "consignmentItem");
        if (items.isEmpty()) {
            out.add(err("MISSING", "/dip/body", "at least one dip:consignmentItem is required"));
            return;
        }
        Set<String> positions = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Element item = items.get(i);
            String ip = "/dip/body/consignmentItem[" + (i + 1) + "]";
            String pos = attr(item, "consignmentItemPosition");
            if (pos == null || !pos.matches("\\d+") || Integer.parseInt(pos) < 1) {
                out.add(err("CONSIGNMENT_POSITION", ip, "consignmentItemPosition must be a positive integer"));
            } else if (!positions.add(pos)) {
                out.add(err("CONSIGNMENT_POSITION", ip, "duplicate consignmentItemPosition " + pos));
            }
            if (text(item, DIP, "bopAccountId") == null) out.add(err("MISSING", ip, "bopAccountId is missing"));
            Element cesop = kid(kid(item, DIP, "data"), CESOP, "CESOP");
            if (cesop == null) out.add(err("MISSING", ip + "/data", "cesop:CESOP is missing"));
            else cesop(cesop, ip + "/data/CESOP", out);
        }
    }

    // ------------------------------------------------------------------ CESOP message

    private void cesop(Element c, String path, List<Issue> out) {
        String v = attr(c, "version");
        if (v == null) out.add(err("MISSING", path, "attribute 'version' is missing"));
        else if (!"4.03".equals(v)) out.add(warn("CESOP_VERSION", path, "Expected version 4.03 but found " + v));

        Element ms = kid(c, CESOP, "MessageSpec");
        Element body = kid(c, CESOP, "PaymentDataBody");
        if (ms == null) { out.add(err("MISSING", path, "MessageSpec is missing")); return; }
        if (body == null) { out.add(err("MISSING", path, "PaymentDataBody is missing")); return; }

        String mp = path + "/MessageSpec";
        String country = text(ms, CESOP, "TransmittingCountry");
        if (country == null) out.add(err("MISSING", mp, "TransmittingCountry is missing"));
        else if (!CC_RE.matcher(country).matches()) out.add(err("FORMAT", mp + "/TransmittingCountry", "Must be ISO 3166 alpha-2 upper case: " + country));

        if (!"PMT".equals(text(ms, CESOP, "MessageType"))) out.add(err("MESSAGE_TYPE", mp + "/MessageType", "MessageType must be \"PMT\""));

        String indic = text(ms, CESOP, "MessageTypeIndic");
        if (indic == null || !MESSAGE_INDICS.contains(indic)) {
            out.add(err("MESSAGE_TYPE_INDIC", mp + "/MessageTypeIndic", "Must be one of " + MESSAGE_INDICS + " but was " + indic));
        }
        String refId = text(ms, CESOP, "MessageRefId");
        if (refId == null) out.add(err("MISSING", mp, "MessageRefId is missing"));
        else if (!UUID_RE.matcher(refId).matches()) out.add(err("MH-BR-0050", mp + "/MessageRefId", "MessageRefId must be a UUID version 4 (error code 10050): " + refId));

        String corr = text(ms, CESOP, "CorrMessageRefId");
        if ("CESOP101".equals(indic) && corr == null) out.add(err("CORR_MESSAGE_REF", mp, "CorrMessageRefId is required for CESOP101 (correction)"));
        if ("CESOP100".equals(indic) && corr != null) out.add(err("CORR_MESSAGE_REF", mp, "CorrMessageRefId must not be present for CESOP100 (new)"));
        if (corr != null && corr.equals(refId)) out.add(err("CORR_MESSAGE_REF", mp, "CorrMessageRefId must differ from MessageRefId"));

        Element sending = kid(ms, CESOP, "SendingPSP");
        if (sending != null) psp(sending, mp + "/SendingPSP", out);

        Element period = kid(ms, CESOP, "ReportingPeriod");
        Integer quarter = null, year = null;
        if (period == null) {
            out.add(err("MISSING", mp, "ReportingPeriod is missing"));
        } else {
            quarter = intOrNull(text(period, CESOP, "Quarter"));
            year = intOrNull(text(period, CESOP, "Year"));
            if (quarter == null || quarter < 1 || quarter > 4) out.add(err("REPORTING_PERIOD", mp + "/ReportingPeriod/Quarter", "Quarter must be 1-4"));
            if (year == null || year < 2000 || year > 2100) out.add(err("REPORTING_PERIOD", mp + "/ReportingPeriod/Year", "Year must be a 4-digit year"));
        }
        String ts = text(ms, CESOP, "Timestamp");
        if (ts == null) out.add(err("MISSING", mp, "Timestamp is missing"));
        else if (parseInstant(ts) == null) out.add(err("FORMAT", mp + "/Timestamp", "Not an ISO date-time: " + ts));

        // ---- body
        String bp = path + "/PaymentDataBody";
        Element rp = kid(body, CESOP, "ReportingPSP");
        if (rp == null) out.add(err("MISSING", bp, "ReportingPSP is missing"));
        else psp(rp, bp + "/ReportingPSP", out);

        List<Element> payees = kids(body, CESOP, "ReportedPayee");
        if ("CESOP102".equals(indic) && !payees.isEmpty()) {
            out.add(err("NIL_REPORT", bp, "CESOP102 (no payments) must not contain ReportedPayee"));
        }
        if ("CESOP100".equals(indic) && payees.isEmpty()) {
            out.add(err("MISSING", bp, "CESOP100 requires at least one ReportedPayee (use CESOP102 for a nil report)"));
        }
        Set<String> docRefIds = new HashSet<>();
        Set<String> txIds = new HashSet<>();
        for (int i = 0; i < payees.size(); i++) {
            payee(payees.get(i), bp + "/ReportedPayee[" + (i + 1) + "]", indic, quarter, year, docRefIds, txIds, out);
        }
        // EU business rules from the CESOP XSD User Guide v6.00 (section 4) that can be checked on one message
        CesopBusinessRules.check(c, path, indic, out);
    }

    private void psp(Element e, String path, List<Issue> out) {
        Element id = kid(e, CESOP, "PSPId");
        String value = text(id);
        String type = attr(id, "PSPIdType");
        if (value == null) out.add(err("MISSING", path, "PSPId is missing"));
        if (type == null) {
            out.add(err("MISSING", path + "/PSPId", "attribute PSPIdType is missing"));
        } else if ("BIC".equals(type)) {
            if (value != null && !BIC_RE.matcher(value).matches()) out.add(err("FORMAT", path + "/PSPId", "Not a valid BIC: " + value));
            if (attr(id, "PSPIdOther") != null) out.add(err("PSP_ID_OTHER", path + "/PSPId", "PSPIdOther is only allowed when PSPIdType=\"Other\""));
        } else if ("Other".equals(type)) {
            if (attr(id, "PSPIdOther") == null) out.add(err("PSP_ID_OTHER", path + "/PSPId", "PSPIdOther is required when PSPIdType=\"Other\""));
        } else {
            out.add(err("PSP_ID_TYPE", path + "/PSPId", "PSPIdType must be BIC or Other, was " + type));
        }
        if (text(e, CESOP, "Name") == null) out.add(err("MISSING", path, "Name is missing"));
    }

    private void payee(Element p, String path, String msgIndic, Integer quarter, Integer year,
                       Set<String> docRefIds, Set<String> txIds, List<Issue> out) {
        if (text(p, CESOP, "Name") == null) out.add(err("MISSING", path, "Name is missing"));
        String country = text(p, CESOP, "Country");
        if (country == null) out.add(err("MISSING", path, "Country is missing"));
        else if (!CC_RE.matcher(country).matches()) out.add(err("FORMAT", path + "/Country", "Must be ISO alpha-2 upper case: " + country));

        Element addr = kid(p, CESOP, "Address");
        if (addr != null) {
            String ap = path + "/Address";
            String cc = text(addr, CM, "CountryCode");
            if (cc == null || !CC_RE.matcher(cc).matches()) out.add(err("FORMAT", ap + "/CountryCode", "CountryCode must be ISO alpha-2 upper case"));
            Element free = kid(addr, CM, "AddressFree");
            Element fix = kid(addr, CM, "AddressFix");
            if ((free == null) == (fix == null)) {
                out.add(err("ADDRESS", ap, "Exactly one of AddressFree / AddressFix must be provided"));
            } else if (fix != null && text(fix, CM, "City") == null) {
                out.add(err("MISSING", ap + "/AddressFix", "City is required in AddressFix"));
            }
        }

        List<Element> accounts = kids(p, CESOP, "AccountIdentifier");
        if (accounts.isEmpty() && kid(p, CESOP, "Representative") == null) {
            out.add(err("RP-BR-0080", path, "Either an AccountIdentifier or a Representative must be provided (error code 40080)"));
        }
        for (int i = 0; i < accounts.size(); i++) {
            Element a = accounts.get(i);
            String ap = path + "/AccountIdentifier[" + (i + 1) + "]";
            if (text(a) == null) out.add(err("MISSING", ap, "account identifier is empty"));
            String type = attr(a, "type");
            if (type == null) out.add(err("MISSING", ap, "attribute 'type' is missing"));
            else if (!ACCOUNT_TYPES.contains(type)) out.add(warn("ACCOUNT_TYPE", ap, "Unexpected account type " + type));
            String cc = attr(a, "CountryCode");
            if (cc != null && !CC_RE.matcher(cc).matches()) out.add(err("FORMAT", ap, "CountryCode must be ISO alpha-2 upper case"));
        }
        for (Element v : kids(kid(p, CESOP, "TAXIdentification"), CESOP, "VATId")) {
            if (text(v) == null) out.add(err("MISSING", path + "/TAXIdentification/VATId", "VATId is empty"));
            String by = attr(v, "issuedBy");
            if (by == null || !CC_RE.matcher(by).matches()) out.add(err("FORMAT", path + "/TAXIdentification/VATId", "issuedBy must be ISO alpha-2 upper case"));
        }

        // DocSpec
        Element doc = kid(p, CESOP, "DocSpec");
        String docIndic = null;
        if (doc == null) {
            out.add(err("MISSING", path, "DocSpec is missing"));
        } else {
            String dp = path + "/DocSpec";
            docIndic = text(doc, CM, "DocTypeIndic");
            String docRef = text(doc, CM, "DocRefId");
            String corrDoc = text(doc, CM, "CorrDocRefId");
            if (docIndic == null || !DOC_INDICS.contains(docIndic)) {
                out.add(err("DOC_TYPE_INDIC", dp, "DocTypeIndic must be one of " + DOC_INDICS));
            }
            if (docRef == null) out.add(err("MISSING", dp, "DocRefId is missing"));
            else {
                if (!UUID_RE.matcher(docRef).matches()) out.add(err("CM-BR-0030", dp + "/DocRefId", "DocRefId must be a UUID version 4 (error code 20030): " + docRef));
                if (!docRefIds.add(docRef)) out.add(err("DUPLICATE", dp + "/DocRefId", "DocRefId used more than once in this message: " + docRef));
            }
            if ("CESOP1".equals(docIndic) && corrDoc != null) out.add(err("CORR_DOC_REF", dp, "CorrDocRefId must not be present for CESOP1 (new)"));
            if (("CESOP2".equals(docIndic) || "CESOP3".equals(docIndic)) && corrDoc == null) {
                out.add(err("CORR_DOC_REF", dp, "CorrDocRefId is required for " + docIndic));
            }
            if ("CESOP100".equals(msgIndic) && docIndic != null && !"CESOP1".equals(docIndic)) {
                out.add(err("DOC_VS_MESSAGE", dp, "A new message (CESOP100) can only contain DocTypeIndic CESOP1"));
            }
        }

        // Transactions
        List<Element> txs = kids(p, CESOP, "ReportedTransaction");
        if ("CESOP3".equals(docIndic)) {
            if (!txs.isEmpty()) out.add(err("RP-BR-0090", path, "A deleted payee (CESOP3) must not carry ReportedTransaction elements (error code 40090)"));
        } else if (txs.isEmpty() && docIndic != null) {
            out.add(err("MISSING", path, "at least one ReportedTransaction is required"));
        }
        for (int i = 0; i < txs.size(); i++) {
            transaction(txs.get(i), path + "/ReportedTransaction[" + (i + 1) + "]", quarter, year, txIds, out);
        }
    }

    private void transaction(Element t, String path, Integer quarter, Integer year, Set<String> txIds, List<Issue> out) {
        String refundAttr = attr(t, "IsRefund");
        Boolean refund = parseBool(refundAttr);
        if (refund == null) out.add(err("FORMAT", path, "IsRefund must be true or false"));

        String id = text(t, CESOP, "TransactionIdentifier");
        if (id == null) out.add(err("MISSING", path, "TransactionIdentifier is missing"));
        else if (!txIds.add(id)) out.add(warn("DUPLICATE", path + "/TransactionIdentifier", "TransactionIdentifier appears more than once in this message: " + id));

        Element dt = kid(t, CESOP, "DateTime");
        String dts = text(dt);
        if (dts == null) {
            out.add(err("MISSING", path, "DateTime is missing"));
        } else {
            Instant when = parseInstant(dts);
            if (when == null) {
                out.add(err("FORMAT", path + "/DateTime", "Not an ISO date-time: " + dts));
            }
            if (attr(dt, "transactionDateType") == null) out.add(err("MISSING", path + "/DateTime", "attribute transactionDateType is missing"));
        }

        Element amount = kid(t, CESOP, "Amount");
        String amt = text(amount);
        if (amt == null) {
            out.add(err("MISSING", path, "Amount is missing"));
        } else {
            try {
                BigDecimal a = new BigDecimal(amt);
                if (refund != null && refund && a.signum() > 0) out.add(err("RT-BR-0010", path + "/Amount", "IsRefund=true but amount is positive; a refund must be negative (error code 45010)"));
                if (refund != null && !refund && a.signum() < 0) out.add(err("RT-BR-0010", path + "/Amount", "IsRefund=false but amount is negative; only a refund may be negative (error code 45010)"));
            } catch (NumberFormatException e) {
                out.add(err("FORMAT", path + "/Amount", "Not a decimal number: " + amt));
            }
            String ccy = attr(amount, "currency");
            if (ccy == null || !CCY_RE.matcher(ccy).matches()) out.add(err("FORMAT", path + "/Amount", "currency must be an ISO 4217 code (3 upper-case letters)"));
        }

        Element pm = kid(t, CESOP, "PaymentMethod");
        String type = text(pm, CM, "PaymentMethodType");
        String other = text(pm, CM, "PaymentMethodOther");
        if (type == null) {
            out.add(err("MISSING", path + "/PaymentMethod", "PaymentMethodType is missing"));
        } else {
            if (!PAYMENT_METHODS.contains(type)) out.add(warn("PAYMENT_METHOD", path + "/PaymentMethod", "Unexpected PaymentMethodType: " + type));
            if ("Other".equals(type) && other == null) out.add(err("PAYMENT_METHOD_OTHER", path + "/PaymentMethod", "PaymentMethodOther is required when PaymentMethodType=\"Other\""));
            if (!"Other".equals(type) && other != null) out.add(err("PAYMENT_METHOD_OTHER", path + "/PaymentMethod", "PaymentMethodOther is only allowed when PaymentMethodType=\"Other\""));
        }

        if (parseBool(text(t, CESOP, "InitiatedAtPhysicalPremisesOfMerchant")) == null) {
            out.add(err("FORMAT", path + "/InitiatedAtPhysicalPremisesOfMerchant", "Must be true or false"));
        }
        Element payer = kid(t, CESOP, "PayerMS");
        String pms = text(payer);
        if (pms == null || !CC_RE.matcher(pms).matches()) out.add(err("FORMAT", path + "/PayerMS", "PayerMS must be ISO alpha-2 upper case"));
        if (attr(payer, "PayerMSSource") == null) out.add(err("MISSING", path + "/PayerMS", "attribute PayerMSSource is missing"));
    }

    // ------------------------------------------------------------------ helpers

    private static Issue err(String code, String path, String msg) { return new Issue(Severity.ERROR, code, path, msg); }
    private static Issue warn(String code, String path, String msg) { return new Issue(Severity.WARNING, code, path, msg); }

    private static Integer intOrNull(String s) {
        try { return s == null ? null : Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean parseBool(String s) {
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return null;
    }

    private static Instant parseInstant(String s) {
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        return null;
    }

    private static boolean isLocalDateTime(String s) {
        return parseInstant(s) != null;
    }
}
