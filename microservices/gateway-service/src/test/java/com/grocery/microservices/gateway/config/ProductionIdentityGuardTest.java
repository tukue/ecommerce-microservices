package com.grocery.microservices.gateway.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionIdentityGuardTest {

    @Test
    void rejectsNonHttpsIssuer() {
        assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        "http://id.example.com/realms/grocery", "grocery-api"));
    }

    @Test
    void rejectsMixedCaseHttpsProtocol() {
        assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        "HtTpS://id.example.com/realms/grocery", "grocery-api"));
    }

    @Test
    void rejectsBlankAudience() {
        assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        "https://id.example.com/realms/grocery", ""));
    }

    @Test
    void acceptsSafeProductionIdentity() {
        assertDoesNotThrow(() -> ProductionIdentityGuard.requireSafeProductionIdentity(
                "https://id.example.com/realms/grocery", "grocery-api"));
    }
}
