package com.transitanalytics.service;

/** An external data source failed or did not answer in time. */
public class ExternalServiceException extends RuntimeException {

    private final String source;

    public ExternalServiceException(String source, Throwable cause) {
        super(source + " request failed: " + cause.getMessage(), cause);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
