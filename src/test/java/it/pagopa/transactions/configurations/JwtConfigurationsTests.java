package it.pagopa.transactions.configurations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class JwtConfigurationsTests {

    private JwtConfigurations jwtConfigurations = new JwtConfigurations();

    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final String WEAK_KEY = "ODMzNUZBNTZENDg3";

    private static final String INVALID_KEY = ".";

    @Test
    void shouldGenerateJwtSigningKey() {
        assertDoesNotThrow(() -> jwtConfigurations.jwtSigningKey(STRONG_KEY));
    }

    @Test
    void shouldThrowIllegalStateExceptionForWeakKey() {
        assertThrows(IllegalStateException.class, () -> jwtConfigurations.jwtSigningKey(WEAK_KEY));
    }

    @Test
    void shouldThrowIllegalStateExceptionForInvalidKey() {
        assertThrows(IllegalStateException.class, () -> jwtConfigurations.jwtSigningKey(INVALID_KEY));
    }
}
