package com.github.huchangjun118.schemamigrationplannerjava.audit;

/**
 * 越权访问被拒绝时抛出。携带可区分的原因码，便于调用方与审计定位。
 */
public class AccessDeniedException extends RuntimeException {

    private final String reasonCode;
    private final String operator;
    private final String action;

    public AccessDeniedException(String reasonCode, String operator, String action, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.operator = operator;
        this.action = action;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getOperator() {
        return operator;
    }

    public String getAction() {
        return action;
    }
}
