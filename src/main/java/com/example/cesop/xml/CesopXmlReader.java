package com.example.cesop.xml;

import static com.example.cesop.xml.Dom.*;

import com.example.cesop.model.CesopModel.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Reads a DIP-wrapped (or bare) CESOP document back into the JSON model. */
@Component
public class CesopXmlReader {

    public ExportRequest read(byte[] xml, int consignmentItemPosition) {
        Document doc;
        try {
            doc = Dom.parse(xml);
        } catch (Exception e) {
            throw new IllegalArgumentException("Original file is not well-formed XML: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (CESOP.equals(root.getNamespaceURI()) && "CESOP".equals(root.getLocalName())) {
            return new ExportRequest(null, readMessage(root));
        }
        if (!(DIP.equals(root.getNamespaceURI()) && "dip".equals(root.getLocalName()))) {
            throw new IllegalArgumentException("Root element must be dip:dip or cesop:CESOP");
        }
        Element header = kid(root, DIP, "header");
        Element consignment = kid(header, DIP, "consignment");
        Element cust = kid(consignment, DIP, "customerIdentifier");
        Element body = kid(root, DIP, "body");
        Element item = null;
        for (Element e : kids(body, DIP, "consignmentItem")) {
            if (String.valueOf(consignmentItemPosition).equals(attr(e, "consignmentItemPosition"))) item = e;
        }
        if (item == null) {
            throw new IllegalArgumentException("No consignmentItem with position " + consignmentItemPosition);
        }
        Element cesop = kid(kid(item, DIP, "data"), CESOP, "CESOP");
        if (cesop == null) throw new IllegalArgumentException("consignmentItem has no cesop:CESOP data");
        DipHeader h = new DipHeader(attr(header, "environment"),
                text(cust, DIP, "identityProvider"), text(cust, DIP, "identifier"),
                text(consignment, DIP, "creationTime"), text(consignment, DIP, "transferticketId"),
                text(item, DIP, "bopAccountId"));
        return new ExportRequest(h, readMessage(cesop));
    }

    private Message readMessage(Element c) {
        Element ms = kid(c, CESOP, "MessageSpec");
        Element period = kid(ms, CESOP, "ReportingPeriod");
        Element bodyEl = kid(c, CESOP, "PaymentDataBody");
        List<Payee> payees = new ArrayList<>();
        for (Element p : kids(bodyEl, CESOP, "ReportedPayee")) payees.add(readPayee(p));
        return new Message(text(ms, CESOP, "TransmittingCountry"), text(ms, CESOP, "MessageTypeIndic"),
                text(ms, CESOP, "MessageRefId"), text(ms, CESOP, "CorrMessageRefId"),
                readPsp(kid(ms, CESOP, "SendingPSP")),
                toInt(text(period, CESOP, "Quarter")), toInt(text(period, CESOP, "Year")),
                text(ms, CESOP, "Timestamp"), readPsp(kid(bodyEl, CESOP, "ReportingPSP")), payees);
    }

    private Psp readPsp(Element e) {
        if (e == null) return null;
        Element id = kid(e, CESOP, "PSPId");
        Element name = kid(e, CESOP, "Name");
        return new Psp(attr(id, "PSPIdType"), attr(id, "PSPIdOther"), text(id), text(name), attr(name, "nameType"));
    }

    private Payee readPayee(Element p) {
        Element name = kid(p, CESOP, "Name");
        Element doc = kid(p, CESOP, "DocSpec");
        List<Vat> vats = new ArrayList<>();
        for (Element v : kids(kid(p, CESOP, "TAXIdentification"), CESOP, "VATId")) {
            vats.add(new Vat(attr(v, "issuedBy"), text(v)));
        }
        List<Account> accounts = new ArrayList<>();
        for (Element a : kids(p, CESOP, "AccountIdentifier")) {
            accounts.add(new Account(attr(a, "CountryCode"), attr(a, "type"), text(a)));
        }
        List<Transaction> tx = new ArrayList<>();
        for (Element t : kids(p, CESOP, "ReportedTransaction")) tx.add(readTx(t));
        return new Payee(text(name), attr(name, "nameType"), text(p, CESOP, "Country"),
                readAddress(kid(p, CESOP, "Address")), text(p, CESOP, "EmailAddress"), text(p, CESOP, "WebPage"),
                vats, accounts, tx,
                text(doc, CM, "DocTypeIndic"), text(doc, CM, "DocRefId"), text(doc, CM, "CorrDocRefId"));
    }

    private Address readAddress(Element a) {
        if (a == null) return null;
        Element fix = kid(a, CM, "AddressFix");
        AddressFix f = fix == null ? null : new AddressFix(
                text(fix, CM, "Street"), text(fix, CM, "BuildingIdentifier"), text(fix, CM, "SuiteIdentifier"),
                text(fix, CM, "FloorIdentifier"), text(fix, CM, "DistrictName"), text(fix, CM, "POB"),
                text(fix, CM, "PostCode"), text(fix, CM, "City"), text(fix, CM, "CountrySubentity"));
        return new Address(text(a, CM, "CountryCode"), attr(a, "legalAddressType"), text(a, CM, "AddressFree"), f);
    }

    private Transaction readTx(Element t) {
        Element amount = kid(t, CESOP, "Amount");
        Element pm = kid(t, CESOP, "PaymentMethod");
        Element dt = kid(t, CESOP, "DateTime");
        Element payer = kid(t, CESOP, "PayerMS");
        String amt = text(amount);
        return new Transaction(toBool(attr(t, "IsRefund")), text(t, CESOP, "TransactionIdentifier"),
                text(dt), attr(dt, "transactionDateType"),
                amt == null ? null : new BigDecimal(amt), attr(amount, "currency"),
                text(pm, CM, "PaymentMethodType"), text(pm, CM, "PaymentMethodOther"),
                toBool(text(t, CESOP, "InitiatedAtPhysicalPremisesOfMerchant")),
                text(payer), attr(payer, "PayerMSSource"),
                text(kid(t, CESOP, "PSPRole"), CM, "PSPRoleType"));
    }

    private static Integer toInt(String s) {
        try { return s == null ? null : Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean toBool(String s) {
        if (s == null) return null;
        return "true".equals(s) || "1".equals(s);
    }
}
