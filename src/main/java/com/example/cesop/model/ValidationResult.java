package com.example.cesop.model;

import java.util.List;

public record ValidationResult(boolean valid, int errorCount, int warningCount, List<Issue> issues) {
    public static ValidationResult of(List<Issue> issues) {
        int e = (int) issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).count();
        return new ValidationResult(e == 0, e, issues.size() - e, issues);
    }
}
