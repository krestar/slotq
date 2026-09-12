package com.slotq.events.application;

public record DeliveryClaim(DeliveryKey key, long fencingToken) { }
