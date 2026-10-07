package com.example.cesop.web;

import com.example.cesop.model.CesopModel.ExportRequest;
import com.example.cesop.model.CesopModel.ResubmissionRequest;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.service.CesopExportService;
import com.example.cesop.service.CesopResubmissionService;
import com.example.cesop.service.CesopValidationService;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cesop")
public class CesopController {

    private final CesopValidationService validation;
    private final CesopExportService export;
    private final CesopResubmissionService resubmission;

    public CesopController(CesopValidationService validation, CesopExportService export,
                           CesopResubmissionService resubmission) {
        this.validation = validation;
        this.export = export;
        this.resubmission = resubmission;
    }

    /** Validate an uploaded XML file (multipart field "file"). */
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ValidationResult validateFile(@RequestParam("file") MultipartFile file) throws IOException {
        return validation.validate(file.getBytes());
    }

    /** Validate raw XML sent as the request body. */
    @PostMapping(value = "/validate", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ValidationResult validateBody(@RequestBody byte[] xml) {
        return validation.validate(xml);
    }

    /** JSON in, DIP v2 + CESOP v4.03 XML out (new report CESOP100, nil report CESOP102, or any correction). */
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request,
                                         @RequestParam(defaultValue = "false") boolean skipValidation) {
        return xml(export.export(request, skipValidation), "cesop-export.xml");
    }

    /**
     * Upload the original XML ("original") plus a JSON part ("request", content-type application/json)
     * and get back the CESOP101 correction/deletion message.
     */
    @PostMapping(value = "/resubmission/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> resubmission(@RequestPart("original") MultipartFile original,
                                               @RequestPart("request") ResubmissionRequest request,
                                               @RequestParam(defaultValue = "false") boolean skipValidation)
            throws IOException {
        return xml(resubmission.build(original.getBytes(), request, skipValidation), "cesop-resubmission.xml");
    }

    private ResponseEntity<byte[]> xml(Generated g, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Cesop-Message-Ref-Id", g.messageRefId())
                .header("X-Dip-Transferticket-Id", g.transferticketId())
                .body(g.xml());
    }
}
