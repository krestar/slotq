package com.slotq.waitlist.application;

import com.slotq.waitlist.domain.WaitlistDemand;

public interface WaitlistDemandStore {

    WaitlistDemand lockOrCreate(WaitlistDemand.Identity identity);

    void lock(WaitlistDemand demand);
}
