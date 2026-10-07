package com.example.cesop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * JSON-friendly model of a DIP v2 envelope carrying one CESOP v4.03 payment data message.
 * Null/blank fields are auto-filled by the exporter where sensible (UUIDs, timestamps, type codes).
 */
public final class CesopModel {
    private CesopModel() {}

    /** dip:header + dip:consignmentItem/bopAccountId */
    public record DipHeader(String environment, String identityProvider, String identifier,
                            String creationTime, String transferticketId, String bopAccountId) {}

    /** ReportingPSP / SendingPSP. idType = BIC | Other (idOther required for Other). */
    public record Psp(String idType, String idOther, String id, String name, String nameType) {}

    public record AddressFix(String street, String buildingIdentifier, String suiteIdentifier,
                             String floorIdentifier, String districtName, String pob,
                             String postCode, String city, String countrySubentity) {}

    /** Provide either {@code free} (AddressFree) or {@code fix} (AddressFix). */
    public record Address(String countryCode, String legalAddressType, String free, AddressFix fix) {}

    public record Vat(String issuedBy, String value) {}

    public record Account(String countryCode, String type, String value) {}

    public record Transaction(Boolean refund, String transactionIdentifier, String dateTime, String dateType,
                              BigDecimal amount, String currency,
                              String paymentMethodType, String paymentMethodOther,
                              Boolean initiatedAtPhysicalPremises,
                              String payerMS, String payerMSSource, String pspRole) {}

    /** docTypeIndic: CESOP1 new, CESOP2 corrected, CESOP3 deleted. */
    public record Payee(String name, String nameType, String country, Address address,
                        String email, String webPage, List<Vat> vatIds, List<Account> accounts,
                        List<Transaction> transactions,
                        String docTypeIndic, String docRefId, String corrDocRefId) {

        public Payee withDoc(String docTypeIndic, String docRefId, String corrDocRefId) {
            return new Payee(name, nameType, country, address, email, webPage, vatIds, accounts,
                    transactions, docTypeIndic, docRefId, corrDocRefId);
        }

        public Payee withTransactions(List<Transaction> tx) {
            return new Payee(name, nameType, country, address, email, webPage, vatIds, accounts,
                    tx, docTypeIndic, docRefId, corrDocRefId);
        }
    }

    /** messageTypeIndic: CESOP100 new, CESOP101 correction, CESOP102 nil report. */
    public record Message(String transmittingCountry, String messageTypeIndic, String messageRefId,
                          String corrMessageRefId, Psp sendingPsp, Integer quarter, Integer year,
                          String timestamp, Psp reportingPsp, List<Payee> payees) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportRequest(DipHeader header, Message message) {}

    // ---------------- resubmission ----------------

    public enum ResubmissionAction {
        /** Spontaneous correction of the ReportingPSP data (CESOP101, no payees). */
        CORRECT_PSP,
        /** Correct one ReportedPayee (DocTypeIndic CESOP2 + CorrDocRefId). */
        CORRECT_PAYEE,
        /** Delete one ReportedPayee (DocTypeIndic CESOP3 + CorrDocRefId, no transactions). */
        DELETE_PAYEE
    }

    /** Patch for an existing transaction (e.g. IsRefund false -> true). */
    public record TransactionPatch(String transactionIdentifier, Boolean refund, BigDecimal amount) {}

    /**
     * @param consignmentItemPosition which dip:consignmentItem of the original to use (default 1)
     * @param targetDocRefId          DocRefId of the payee to correct/delete
     * @param reportingPsp            replacement ReportingPSP data (CORRECT_PSP)
     * @param payee                   full replacement payee (CORRECT_PAYEE, optional)
     * @param transactionPatches      alternative to {@code payee}: copy original payee and patch transactions
     * @param header                  optional header overrides
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResubmissionRequest(ResubmissionAction action, Integer consignmentItemPosition,
                                      String targetDocRefId, Psp reportingPsp, Payee payee,
                                      List<TransactionPatch> transactionPatches, DipHeader header) {}
}
