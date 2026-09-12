package com.slotq.events.application;

/** A guarded transition did not apply; never acknowledge or overwrite another owner. */
public final class EventOwnershipLostException extends RuntimeException { }
