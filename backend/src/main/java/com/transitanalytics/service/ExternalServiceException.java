package com.transitanalytics.service;

/** An external data source failed or did not answer in time. */
public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String source, Throwable cause) {
        super(source + " request failed: " + cause.getMessage(), cause);
    }
}
