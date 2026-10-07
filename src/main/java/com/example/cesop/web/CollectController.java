package com.example.cesop.web;

import com.example.cesop.collect.CollectModel.CollectConfig;
import com.example.cesop.collect.CollectModel.CollectReport;
import com.example.cesop.collect.CollectService;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Steps 1 and 2: CSV of payments in, threshold decision and CESOP XML out. */
@RestController
@RequestMapping("/api/cesop/collect")
public class CollectController {

    private final CollectService collect;

    public CollectController(CollectService collect) {
        this.collect = collect;
    }

    /** Shows which payees are reportable and why, without creating a file. */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CollectReport preview(@RequestPart("payments") MultipartFile payments,
                                 @RequestPart("config") CollectConfig config) throws IOException {
        return collect.preview(payments.getBytes(), config);
    }

    /** Builds the CESOP XML (CESOP100, or a CESOP102 nil report when nobody is over the threshold). */
    @PostMapping(value = "/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> export(@RequestPart("payments") MultipartFile payments,
                                         @RequestPart("config") CollectConfig config,
                                         @RequestParam(defaultValue = "false") boolean ignoreRowErrors)
            throws IOException {
        Generated g = collect.export(payments.getBytes(), config, ignoreRowErrors);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cesop-report.xml").build().toString())
                .header("X-Cesop-Message-Ref-Id", g.messageRefId())
                .header("X-Dip-Transferticket-Id", g.transferticketId())
                .body(g.xml());
    }
}
