package com.grocery.microservices.cart.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionIdentityGuardTest {

    @Test
    void rejectsEnabledDevelopmentIdentity() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        true, "https://id.example.com/realms/grocery", "grocery-api"));
        assertTrue(error.getMessage().contains("demo-enabled"));
    }

    @Test
    void rejectsNonHttpsIssuer() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        false, "http://id.example.com/realms/grocery", "grocery-api"));
        assertTrue(error.getMessage().contains("https"));
    }

    @Test
    void rejectsMixedCaseHttpsProtocol() {
        assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        false, "HtTpS://id.example.com/realms/grocery", "grocery-api"));
    }

    @Test
    void rejectsBlankAudience() {
        assertThrows(IllegalStateException.class,
                () -> ProductionIdentityGuard.requireSafeProductionIdentity(
                        false, "https://id.example.com/realms/grocery", " "));
    }

    @Test
    void acceptsSafeProductionIdentity() {
        assertDoesNotThrow(() -> ProductionIdentityGuard.requireSafeProductionIdentity(
                false, "https://id.example.com/realms/grocery", "grocery-api"));
    }
}
