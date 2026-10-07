package com.example.cesop.service;

import com.example.cesop.model.CesopModel.*;
import com.example.cesop.xml.CesopXmlReader;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Derives a CESOP101 correction message from a previously submitted file:
 *   CorrMessageRefId = original MessageRefId, CorrDocRefId = DocRefId of the payee being changed.
 * Mirrors the BZSt samples (spontaneous PSP correction, payee deletion, payee/transaction correction).
 */
@Service
public class CesopResubmissionService {

    private final CesopXmlReader reader;
    private final CesopExportService exporter;

    public CesopResubmissionService(CesopXmlReader reader, CesopExportService exporter) {
        this.reader = reader;
        this.exporter = exporter;
    }

    public Generated build(byte[] originalXml, ResubmissionRequest req, boolean skipValidation) {
        if (req == null || req.action() == null) throw new IllegalArgumentException("'action' is required");
        int pos = req.consignmentItemPosition() == null ? 1 : req.consignmentItemPosition();
        ExportRequest original = reader.read(originalXml, pos);
        Message om = original.message();
        if (om.messageRefId() == null) throw new IllegalArgumentException("Original file has no MessageRefId");

        List<Payee> payees = new ArrayList<>();
        Psp reportingPsp = om.reportingPsp();

        switch (req.action()) {
            case CORRECT_PSP -> {
                if (req.reportingPsp() == null) throw new IllegalArgumentException("'reportingPsp' is required for CORRECT_PSP");
                reportingPsp = req.reportingPsp();
            }
            case DELETE_PAYEE -> {
                Payee target = findTarget(om, req);
                payees.add(target.withTransactions(List.of()).withDoc("CESOP3", null, target.docRefId()));
            }
            case CORRECT_PAYEE -> {
                Payee target = findTarget(om, req);
                Payee base = req.payee() != null ? req.payee() : patch(target, req.transactionPatches());
                payees.add(base.withDoc("CESOP2", base.docRefId(), target.docRefId()));
            }
        }

        DipHeader oh = original.header();
        DipHeader h = req.header() != null ? req.header()
                : oh == null ? null
                : new DipHeader(oh.environment(), oh.identityProvider(), oh.identifier(), null, null, oh.bopAccountId());

        Message cm = new Message(om.transmittingCountry(), "CESOP101", null, om.messageRefId(),
                om.sendingPsp(), om.quarter(), om.year(), null, reportingPsp, payees);
        return exporter.export(new ExportRequest(h, cm), skipValidation);
    }

    private Payee findTarget(Message om, ResubmissionRequest req) {
        if (req.targetDocRefId() == null || req.targetDocRefId().isBlank()) {
            throw new IllegalArgumentException("'targetDocRefId' is required for " + req.action());
        }
        return om.payees().stream()
                .filter(p -> req.targetDocRefId().equals(p.docRefId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ReportedPayee with DocRefId " + req.targetDocRefId() + " in the original message"));
    }

    /** Copies the payee and applies refund/amount patches to matching transactions. */
    private Payee patch(Payee target, List<TransactionPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("CORRECT_PAYEE needs either 'payee' or 'transactionPatches'");
        }
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : target.transactions()) {
            Transaction cur = t;
            for (TransactionPatch p : patches) {
                if (t.transactionIdentifier() != null && t.transactionIdentifier().equals(p.transactionIdentifier())) {
                    Boolean refund = p.refund() != null ? p.refund() : cur.refund();
                    BigDecimal amount = p.amount() != null ? p.amount() : cur.amount();
                    if (p.amount() == null && p.refund() != null && amount != null) {
                        amount = p.refund() ? amount.abs().negate() : amount.abs();
                    }
                    cur = new Transaction(refund, cur.transactionIdentifier(), cur.dateTime(), cur.dateType(),
                            amount, cur.currency(), cur.paymentMethodType(), cur.paymentMethodOther(),
                            cur.initiatedAtPhysicalPremises(), cur.payerMS(), cur.payerMSSource(), cur.pspRole());
                }
            }
            out.add(cur);
        }
        for (TransactionPatch p : patches) {
            if (out.stream().noneMatch(t -> p.transactionIdentifier() != null
                    && p.transactionIdentifier().equals(t.transactionIdentifier()))) {
                throw new IllegalArgumentException("Transaction " + p.transactionIdentifier() + " not found in payee " + target.docRefId());
            }
        }
        return target.withTransactions(out).withDoc(null, null, null);
    }
}
