package com.github.huchangjun118.schemamigrationplannerjava.lease;

/**
 * Raised when an executor lost its lease (expired and taken over) before committing.
 */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException(String message) {
        super(message);
    }
}
