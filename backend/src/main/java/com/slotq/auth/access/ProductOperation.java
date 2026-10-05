package com.slotq.auth.access;

/** Exact operations of the narrowed authenticated Product consumer. */
public enum ProductOperation {
    RESERVATION_GET("reservation.get", AccessProfile.CUSTOMER, AccessAction.RESERVATION_READ),
    RESERVATION_HOLD("reservation.hold", AccessProfile.CUSTOMER, AccessAction.RESERVATION_WRITE),
    MANAGEMENT_LIST("management.reservations.list", AccessProfile.MANAGEMENT, AccessAction.MANAGEMENT_READ);
    public final String tool;
    public final AccessProfile profile;
    public final AccessAction action;
    ProductOperation(String tool, AccessProfile profile, AccessAction action) { this.tool=tool; this.profile = profile; this.action = action; }
}
