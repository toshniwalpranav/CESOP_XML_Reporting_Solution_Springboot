# Changes in v3 (official XSD + EU business rules)

1. **Official XSD files** (BZSt "Amtlicher Datensatz CESOP", DIP v2 + CESOP XSD v4.03) are now inside the project:
   `src/main/resources/xsd/Amtlicher_Datensatz_CESOP/` and switched on in `application.yml`.
   Both `dip.xsd` AND `cesop_PaymentData.xsd` are loaded. `dip.xsd` alone does NOT check the CESOP content.
2. **EU business rules** from the CESOP XSD User Guide v6.00 (section 4), new class `service/CesopBusinessRules.java`.
   Issue codes are the official rule ids (e.g. RP-BR-0030); messages name the official error code (e.g. 40030).
   Added: MH-BR-0030, 0050, 0060, 0080 | CM-BR-0030, 0140, 0150 | RP-BR-0010, 0020, 0030, 0060, 0080, 0090, 0100, 0110 | RT-BR-0010, 0030, 0060, 0080, 0090
   Made stricter (were warnings): refund sign (RT-BR-0010), deleted payee with transactions (RP-BR-0090), UUID v4 format.
3. New tests: `OfficialXsdAndRulesTest` (12 tests) -> total 26 tests.

## NOT covered (needs the CESOP data store or country data)
MH-BR-0010 (MessageRefId unique over time), MH-BR-0040 (CorrMessageRefId known), MH-BR-0100 (period unchanged in correction),
MH-BR-0120 (transmitting country = national administration), CM-BR-0020/0040/0070/0120 (record lookups),
CM-BR-0130 (PSPRole 'other'), RP-BR-0070 (representative BIC), RT-BR-0040 (strict uniqueness; refunds may reuse an id),
RT-BR-0050 (TransactionIdentifier unique in the system).
The EU Validation Module (CESOP VM v1.7.1) remains the reference tool for the full rule set.

## Version note
XSD 4.03 is the version accepted by the CESOP central system today. XSD 4.04 / User Guide 7.00 is announced
(central release 1.8.2 is provisionally planned for production on 29/12/2026); each country switches on its own date.
