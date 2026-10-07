package com.example.cesop.web;

import com.example.cesop.model.CesopModel.ResubmissionRequest;
import com.example.cesop.submission.SubmissionRecord;
import com.example.cesop.submission.SubmissionService;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Step 5: message history, hand-over to the submission channel, tax authority answers, corrections from history. */
@RestController
@RequestMapping("/api/cesop/submissions")
public class SubmissionController {

    public record ResponseRequest(String status, String message) {}

    private final SubmissionService service;

    public SubmissionController(SubmissionService service) {
        this.service = service;
    }

    /** Validates, stores and hands the XML to the configured channel. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SubmissionRecord submit(@RequestParam("file") MultipartFile file) throws IOException {
        return service.submit(file.getBytes());
    }

    @GetMapping
    public List<SubmissionRecord> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SubmissionRecord get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping(value = "/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> xml(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(id + ".xml").build().toString())
                .body(service.xml(id));
    }

    /** Record the tax authority answer: {"status":"ACCEPTED"|"REJECTED","message":"..."} */
    @PostMapping(value = "/{id}/response", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SubmissionRecord response(@PathVariable String id, @RequestBody ResponseRequest body) {
        return service.recordResponse(id, body.status(), body.message());
    }

    /** Builds a CESOP101 correction/deletion from the stored original (no need to upload it again). */
    @PostMapping(value = "/{id}/correction", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> correction(@PathVariable String id, @RequestBody ResubmissionRequest request) {
        Generated g = service.correction(id, request);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cesop-correction.xml").build().toString())
                .header("X-Cesop-Message-Ref-Id", g.messageRefId())
                .body(g.xml());
    }
}
