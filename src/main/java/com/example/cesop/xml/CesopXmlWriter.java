package com.example.cesop.xml;

import static com.example.cesop.xml.Dom.*;

import com.example.cesop.config.CesopProperties;
import com.example.cesop.model.CesopModel.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Builds the DIP v2 / CESOP v4.03 document. Element order follows the BZSt sample files. */
@Component
public class CesopXmlWriter {

    public record Generated(byte[] xml, String messageRefId, String transferticketId) {}

    private final CesopProperties props;

    public CesopXmlWriter(CesopProperties props) {
        this.props = props;
    }

    public Generated write(ExportRequest req) {
        if (req == null || req.message() == null) throw new IllegalArgumentException("'message' is required");
        DipHeader h = req.header() == null ? new DipHeader(null, null, null, null, null, null) : req.header();
        Message m = req.message();

        String ticket = or(h.transferticketId(), uuid());
        String messageRefId = or(m.messageRefId(), uuid());

        Document d = Dom.newDocument();
        Element dip = d.createElementNS(DIP, "dip:dip");
        d.appendChild(dip);
        dip.setAttributeNS(XMLNS, "xmlns:dip", DIP);
        dip.setAttributeNS(XMLNS, "xmlns:xsi", XSI);
        dip.setAttribute("version", "2.0");
        dip.setAttributeNS(XSI, "xsi:schemaLocation", DIP + " " + props.xsdBasePath() + "/dip.xsd");

        Element header = el(d, dip, DIP, "dip", "header", null);
        header.setAttribute("environment", or(h.environment(), props.defaultEnvironment()));
        Element consignment = el(d, header, DIP, "dip", "consignment", null);
        Element cust = el(d, consignment, DIP, "dip", "customerIdentifier", null);
        el(d, cust, DIP, "dip", "identityProvider", h.identityProvider());
        el(d, cust, DIP, "dip", "identifier", h.identifier());
        el(d, consignment, DIP, "dip", "creationTime",
                or(h.creationTime(), LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        el(d, consignment, DIP, "dip", "transferticketId", ticket);
        el(d, header, DIP, "dip", "application", null).setAttribute("code", "CESOP");

        Element body = el(d, dip, DIP, "dip", "body", null);
        Element item = el(d, body, DIP, "dip", "consignmentItem", null);
        item.setAttribute("consignmentItemPosition", "1");
        el(d, item, DIP, "dip", "bopAccountId", or(h.bopAccountId(), "a"));
        Element data = el(d, item, DIP, "dip", "data", null);

        Element c = el(d, data, CESOP, "cesop", "CESOP", null);
        c.setAttributeNS(XMLNS, "xmlns:cm", CM);
        c.setAttributeNS(XMLNS, "xmlns:cesop", CESOP);
        c.setAttributeNS(XMLNS, "xmlns:xsi", XSI);
        c.setAttribute("version", "4.03");
        c.setAttributeNS(XSI, "xsi:schemaLocation",
                CESOP + " " + props.xsdBasePath() + "/cesop_PaymentData.xsd");

        Element ms = el(d, c, CESOP, "cesop", "MessageSpec", null);
        el(d, ms, CESOP, "cesop", "TransmittingCountry", or(m.transmittingCountry(), props.defaultTransmittingCountry()));
        el(d, ms, CESOP, "cesop", "MessageType", "PMT");
        el(d, ms, CESOP, "cesop", "MessageTypeIndic", or(m.messageTypeIndic(), "CESOP100"));
        el(d, ms, CESOP, "cesop", "MessageRefId", messageRefId);
        if (m.corrMessageRefId() != null) el(d, ms, CESOP, "cesop", "CorrMessageRefId", m.corrMessageRefId());
        if (m.sendingPsp() != null) psp(d, ms, "SendingPSP", m.sendingPsp());
        Element period = el(d, ms, CESOP, "cesop", "ReportingPeriod", null);
        el(d, period, CESOP, "cesop", "Quarter", String.valueOf(m.quarter()));
        el(d, period, CESOP, "cesop", "Year", String.valueOf(m.year()));
        el(d, ms, CESOP, "cesop", "Timestamp",
                or(m.timestamp(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));

        Element pdb = el(d, c, CESOP, "cesop", "PaymentDataBody", null);
        if (m.reportingPsp() != null) psp(d, pdb, "ReportingPSP", m.reportingPsp());
        for (Payee p : nz(m.payees())) payee(d, pdb, p);

        return new Generated(Dom.serialize(d), messageRefId, ticket);
    }

    private void psp(Document d, Element parent, String tag, Psp p) {
        Element e = el(d, parent, CESOP, "cesop", tag, null);
        String type = or(p.idType(), "BIC");
        Element id = el(d, e, CESOP, "cesop", "PSPId", p.id());
        id.setAttribute("PSPIdType", type);
        if (p.idOther() != null) id.setAttribute("PSPIdOther", p.idOther());
        Element name = el(d, e, CESOP, "cesop", "Name", p.name());
        name.setAttribute("nameType", or(p.nameType(), "LEGAL"));
    }

    private void payee(Document d, Element parent, Payee p) {
        Element e = el(d, parent, CESOP, "cesop", "ReportedPayee", null);
        el(d, e, CESOP, "cesop", "Name", p.name()).setAttribute("nameType", or(p.nameType(), "BUSINESS"));
        el(d, e, CESOP, "cesop", "Country", p.country());
        if (p.address() != null) address(d, e, p.address());
        if (p.email() != null) el(d, e, CESOP, "cesop", "EmailAddress", p.email());
        if (p.webPage() != null) el(d, e, CESOP, "cesop", "WebPage", p.webPage());
        if (!nz(p.vatIds()).isEmpty()) {
            Element tax = el(d, e, CESOP, "cesop", "TAXIdentification", null);
            for (Vat v : p.vatIds()) {
                Element vat = el(d, tax, CESOP, "cesop", "VATId", v.value());
                set(vat, "issuedBy", v.issuedBy());
            }
        }
        for (Account a : nz(p.accounts())) {
            Element acc = el(d, e, CESOP, "cesop", "AccountIdentifier", a.value());
            if (a.countryCode() != null) acc.setAttribute("CountryCode", a.countryCode());
            acc.setAttribute("type", or(a.type(), "IBAN"));
        }
        for (Transaction t : nz(p.transactions())) transaction(d, e, t);

        Element doc = el(d, e, CESOP, "cesop", "DocSpec", null);
        el(d, doc, CM, "cm", "DocTypeIndic", or(p.docTypeIndic(), "CESOP1"));
        el(d, doc, CM, "cm", "DocRefId", or(p.docRefId(), uuid()));
        if (p.corrDocRefId() != null) el(d, doc, CM, "cm", "CorrDocRefId", p.corrDocRefId());
    }

    private void address(Document d, Element parent, Address a) {
        Element e = el(d, parent, CESOP, "cesop", "Address", null);
        e.setAttribute("legalAddressType", or(a.legalAddressType(), "CESOP303"));
        el(d, e, CM, "cm", "CountryCode", a.countryCode());
        if (a.fix() != null) {
            AddressFix f = a.fix();
            Element fx = el(d, e, CM, "cm", "AddressFix", null);
            opt(d, fx, "Street", f.street());
            opt(d, fx, "BuildingIdentifier", f.buildingIdentifier());
            opt(d, fx, "SuiteIdentifier", f.suiteIdentifier());
            opt(d, fx, "FloorIdentifier", f.floorIdentifier());
            opt(d, fx, "DistrictName", f.districtName());
            opt(d, fx, "POB", f.pob());
            opt(d, fx, "PostCode", f.postCode());
            opt(d, fx, "City", f.city());
            opt(d, fx, "CountrySubentity", f.countrySubentity());
        } else if (a.free() != null) {
            el(d, e, CM, "cm", "AddressFree", a.free());
        }
    }

    private void transaction(Document d, Element parent, Transaction t) {
        Element e = el(d, parent, CESOP, "cesop", "ReportedTransaction", null);
        e.setAttribute("IsRefund", String.valueOf(Boolean.TRUE.equals(t.refund())));
        el(d, e, CESOP, "cesop", "TransactionIdentifier", t.transactionIdentifier());
        el(d, e, CESOP, "cesop", "DateTime", t.dateTime())
                .setAttribute("transactionDateType", or(t.dateType(), "CESOP701"));
        Element amount = el(d, e, CESOP, "cesop", "Amount", t.amount() == null ? null : t.amount().toPlainString());
        set(amount, "currency", t.currency());
        Element pm = el(d, e, CESOP, "cesop", "PaymentMethod", null);
        el(d, pm, CM, "cm", "PaymentMethodType", t.paymentMethodType());
        if (t.paymentMethodOther() != null) el(d, pm, CM, "cm", "PaymentMethodOther", t.paymentMethodOther());
        el(d, e, CESOP, "cesop", "InitiatedAtPhysicalPremisesOfMerchant",
                String.valueOf(Boolean.TRUE.equals(t.initiatedAtPhysicalPremises())));
        el(d, e, CESOP, "cesop", "PayerMS", t.payerMS()).setAttribute("PayerMSSource", or(t.payerMSSource(), "IBAN"));
        if (t.pspRole() != null) {
            Element role = el(d, e, CESOP, "cesop", "PSPRole", null);
            el(d, role, CM, "cm", "PSPRoleType", t.pspRole());
        }
    }

    private static void opt(Document d, Element parent, String name, String value) {
        if (value != null && !value.isBlank()) el(d, parent, CM, "cm", name, value);
    }

    private static void set(Element e, String name, String value) {
        if (value != null && !value.isBlank()) e.setAttribute(name, value);
    }

    private static Element el(Document d, Element parent, String ns, String prefix, String name, String text) {
        Element e = d.createElementNS(ns, prefix + ":" + name);
        if (text != null) e.setTextContent(text);
        parent.appendChild(e);
        return e;
    }

    private static String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
