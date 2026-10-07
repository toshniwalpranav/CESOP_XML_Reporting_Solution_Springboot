package com.example.cesop.service;

import com.example.cesop.model.CesopModel.ExportRequest;
import com.example.cesop.model.ValidationResult;
import com.example.cesop.xml.CesopXmlWriter;
import com.example.cesop.xml.CesopXmlWriter.Generated;
import org.springframework.stereotype.Service;

@Service
public class CesopExportService {

    private final CesopXmlWriter writer;
    private final CesopValidationService validator;

    public CesopExportService(CesopXmlWriter writer, CesopValidationService validator) {
        this.writer = writer;
        this.validator = validator;
    }

    /** Builds the XML and (unless skipValidation) rejects it when it has validation errors. */
    public Generated export(ExportRequest request, boolean skipValidation) {
        Generated g = writer.write(request);
        if (!skipValidation) {
            ValidationResult r = validator.validate(g.xml());
            if (!r.valid()) throw new ValidationFailedException(r);
        }
        return g;
    }
}
