package com.slotq.auth.access;

/** Read bindings available to #132. Write issuance stays closed until its admission/confirmation gate. */
public enum ProductOperation {
    RESERVATION_GET("reservation.get", AccessProfile.CUSTOMER, AccessAction.RESERVATION_READ),
    MANAGEMENT_LIST("management.reservations.list", AccessProfile.MANAGEMENT, AccessAction.MANAGEMENT_READ);
    public final String tool;
    public final AccessProfile profile;
    public final AccessAction action;
    ProductOperation(String tool, AccessProfile profile, AccessAction action) { this.tool=tool; this.profile = profile; this.action = action; }
}
