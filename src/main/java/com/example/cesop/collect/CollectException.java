package com.example.cesop.collect;

public class CollectException extends RuntimeException {
    private final transient CollectModel.CollectReport report;

    public CollectException(CollectModel.CollectReport report) {
        super("CSV contains " + report.rowsWithErrors() + " row(s) with errors");
        this.report = report;
    }

    public CollectModel.CollectReport getReport() {
        return report;
    }
}
