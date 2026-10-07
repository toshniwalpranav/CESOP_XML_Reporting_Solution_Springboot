# CESOP Spring Boot API (DIP v2 + CESOP v4.03)

Java 17+, Spring Boot 3.3, Maven. Run: `mvn spring-boot:run`  - Test: `mvn test`

## Endpoints
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/cesop/validate` | multipart `file` **or** raw `application/xml` body -> JSON `ValidationResult` |
| POST | `/api/cesop/export` | JSON (`ExportRequest`) -> DIP/CESOP XML (new CESOP100, nil CESOP102, or any correction) |
| POST | `/api/cesop/resubmission/export` | multipart `original` (XML) + `request` (JSON) -> CESOP101 correction/deletion XML |

`?skipValidation=true` on the export endpoints skips the post-generation check. Without it, an invalid result returns HTTP 422 with the validation report.
Responses carry `X-Cesop-Message-Ref-Id` and `X-Dip-Transferticket-Id` headers (store the MessageRefId; you need it to correct later).

## curl
```
curl -F file=@Valid_initial_message.xml localhost:8080/api/cesop/validate

curl -X POST localhost:8080/api/cesop/export -H "Content-Type: application/json" -d @examples/export-initial.json -o out.xml

curl localhost:8080/api/cesop/resubmission/export \
  -F "original=@Valid_initial_message.xml" \
  -F "request={\"action\":\"DELETE_PAYEE\",\"targetDocRefId\":\"660f1ecb-1764-40b8-8153-72793e2af465\"};type=application/json" -o delete.xml
```

## Resubmission actions (mirror the BZSt samples)
- `CORRECT_PSP` + `reportingPsp` -> CESOP101, no payees (sample 4)
- `DELETE_PAYEE` + `targetDocRefId` -> payee copied without transactions, DocTypeIndic CESOP3 (sample 5)
- `CORRECT_PAYEE` + `targetDocRefId` + either full `payee` or `transactionPatches` e.g. `{"transactionIdentifier":"...","refund":true}` -> DocTypeIndic CESOP2 (sample 6; amount is auto-negated when refund=true and no amount is given)

In all cases `CorrMessageRefId` = original `MessageRefId`, `CorrDocRefId` = target payee `DocRefId`, new UUIDs are generated.

## Official XSDs
The BZSt XSDs were not part of the upload. Put them somewhere and set in `application.yml`:
```
cesop.xsd-files: [file:/opt/cesop/xsd/dip.xsd, file:/opt/cesop/xsd/cesop_PaymentData.xsd]
```
Uploaded files are then validated against them in addition to the built-in rules (XSD findings have code `XSD`).

## Limits
Built-in rules cover what the samples demonstrate, not the full EU business-rule catalogue. DOM-based, so very large files need heap. Only VATId is modelled under TAXIdentification.


## New: data collection, threshold logic, submission tracking

| Endpoint | Purpose |
|---|---|
| `POST /api/cesop/collect/preview` | multipart `payments` (CSV) + `config` (JSON) -> which payees are reportable and why |
| `POST /api/cesop/collect/export` | same input -> CESOP XML (CESOP100, or CESOP102 nil report if nobody is over the threshold). `?ignoreRowErrors=true` skips bad rows (default: stop with HTTP 422) |
| `POST /api/cesop/submissions` | multipart `file`: validate, store, hand to the channel |
| `GET /api/cesop/submissions` / `/{id}` / `/{id}/xml` | history, one record, stored XML |
| `POST /api/cesop/submissions/{id}/response` | record the tax authority answer `{"status":"ACCEPTED|REJECTED","message":"..."}` |
| `POST /api/cesop/submissions/{id}/correction` | CESOP101 built from the stored original (same JSON as resubmission) |

CSV columns (header row required, comma/semicolon/tab, names case-insensitive):
required `transaction_id, date_time, amount, currency, payer_country, payee_name, payee_account, payment_method`;
optional `is_refund, payee_account_type, payee_account_country, payee_country, payee_vat, payee_vat_country, payee_email, payee_web, payee_address, payee_address_country, payment_method_other, initiated_physical, payer_source, psp_role, date_type`.
See `examples/payments-sample.csv` and `examples/collect-config.json`.

Rules applied: a payment is cross-border when payer_country differs from the payee account country; the payer must be an EU member state; only the chosen quarter counts; a payee is reportable when counted payments are MORE than `threshold` (default 25); refunds are reported but not counted (`countRefundsForThreshold` changes this). Verify these against current EU Commission guidance.

Submission: only the file outbox (`data/outbox`) is built in. Stored messages and status history live in `data/submissions`. Real tax authority transports (e.g. BZSt DIP) need credentials and specs; add one `SubmissionChannel` class per country.
