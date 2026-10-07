package com.example.cesop.service;

import com.example.cesop.model.ValidationResult;

public class ValidationFailedException extends RuntimeException {
    private final transient ValidationResult result;

    public ValidationFailedException(ValidationResult result) {
        super("Generated XML failed validation (" + result.errorCount() + " error(s))");
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }
}
