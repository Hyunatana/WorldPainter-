package org.pepsoft.worldpainter.ai;

import org.junit.BeforeClass;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ChatGptIdentityTest {
    @BeforeClass public static void keys() throws Exception { ChatGptOAuthTest.keys(); }

    @Test public void validatesTheSignatureBeforeTrustingTheSubject() throws Exception {
        String valid = token(Map.of("alg", "RS256", "kid", "test-key"), claims());
        assertEquals("account-one", verify(valid).subject());
        String[] parts = valid.split("\\.");
        Map<String, Object> altered = claims(); altered.put("sub", "attacker");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(JsonProtocol.write(altered).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        IOException failure = assertThrows(IOException.class, () -> verify(forged));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("attacker"));
    }

    @Test public void refusesAlgorithmConfusionAndHeaderControlledKeys() throws Exception {
        for (Map<String, Object> header : List.of(Map.<String, Object>of("alg", "none", "kid", "test-key"),
                Map.<String, Object>of("alg", "HS256", "kid", "test-key"), Map.<String, Object>of("alg", "RS256", "kid", "unknown"),
                Map.<String, Object>of("alg", "RS256", "kid", "test-key", "jku", "https://untrusted.invalid/keys"))) {
            assertThrows(IOException.class, () -> verify(token(header, claims())));
        }
    }

    @Test public void multipleAudiencesRequireTheCorrectAuthorizedParty() throws Exception {
        Map<String, Object> claims = claims(); claims.put("aud", List.of(CLIENT, "another-client"));
        assertThrows(IOException.class, () -> verify(token(Map.of("alg", "RS256", "kid", "test-key"), claims)));
        claims.put("azp", CLIENT);
        assertEquals("account-one", verify(token(Map.of("alg", "RS256", "kid", "test-key"), claims)).subject());
        claims.put("azp", "another-client");
        assertThrows(IOException.class, () -> verify(token(Map.of("alg", "RS256", "kid", "test-key"), claims)));
    }

    private static ChatGptIdentity.Identity verify(String token) throws IOException {
        return ChatGptIdentity.verify(token, ChatGptOAuthTest.jwks, ISSUER, CLIENT, "nonce-one", null, NOW);
    }
    private static Map<String, Object> claims() {
        return new LinkedHashMap<>(Map.of("iss", ISSUER, "aud", CLIENT, "sub", "account-one", "nonce", "nonce-one",
                "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(3600).getEpochSecond()));
    }
    private static String token(Map<String, Object> header, Map<String, Object> claims) throws Exception { return ChatGptOAuthTest.jwt(header, claims); }
    private static final String ISSUER = "https://auth.openai.com", CLIENT = "oaiapp_worldpainter_test";
    private static final Instant NOW = Instant.parse("2026-10-15T05:00:00Z");
}
