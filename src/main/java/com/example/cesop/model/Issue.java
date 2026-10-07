package com.example.cesop.model;

public record Issue(Severity severity, String code, String path, String message) {
    public enum Severity { ERROR, WARNING }
}
