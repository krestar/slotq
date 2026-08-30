package com.slotq.booking.application;

public class ProductApiException extends RuntimeException {

    private final ProductError error;

    protected ProductApiException(ProductError error) {
        this.error = error;
    }

    public ProductError error() {
        return error;
    }
}
