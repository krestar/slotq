package com.slotq.observability.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Machine-only scrape credential; deliberately independent of Product and human operator auth. */
@Component
public final class ScrapeCredential {
    private final byte[] expected;

    public ScrapeCredential(@Value("${slotq.observability.scrape-token:}") String token) {
        expected = token.length() >= 32 ? ("Bearer " + token).getBytes(StandardCharsets.UTF_8) : null;
    }

    public boolean matches(HttpServletRequest request) {
        String supplied = request.getHeader("Authorization");
        return expected != null && supplied != null && supplied.length() <= 4096
            && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
