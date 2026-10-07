package com.kondapallicb.urlshortener.orchestration;

public enum ExecutionState {
    PENDING,
    RUNNING,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
    SAFE_STOPPED
}
