package org.pepsoft.worldpainter.ai;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Verifies RS256 ID tokens using JCA primitives and only issuer-published RSA signing keys. */
final class ChatGptIdentity {
    static Identity verify(String token, Map<?, ?> jwks, String issuer, String clientId, String nonce,
                           String subject, Instant now) throws IOException {
        try {
            if (token == null || token.length() > 32_768) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            Map<?, ?> header = jsonPart(parts[0]);
            if (!"RS256".equals(header.get("alg")) || header.containsKey("crit") || header.containsKey("jku")
                    || header.containsKey("jwk") || header.containsKey("x5u")) throw new IllegalArgumentException();
            String kid = text(header, "kid", 256);
            if (!(jwks.get("keys") instanceof List<?> keys) || keys.size() > 64) throw new IllegalArgumentException();
            Map<?, ?> selected = null;
            for (Object item : keys) if (item instanceof Map<?, ?> key && kid.equals(key.get("kid"))) {
                if (selected != null) throw new IllegalArgumentException();
                selected = key;
            }
            if (selected == null || !"RSA".equals(selected.get("kty"))
                    || (selected.get("use") != null && !"sig".equals(selected.get("use")))
                    || (selected.get("alg") != null && !"RS256".equals(selected.get("alg")))
                    || (selected.get("key_ops") != null && (!(selected.get("key_ops") instanceof List<?> operations)
                    || !operations.contains("verify")))) throw new IllegalArgumentException();
            BigInteger modulus = new BigInteger(1, decode(text(selected, "n", 2048)));
            BigInteger exponent = new BigInteger(1, decode(text(selected, "e", 16)));
            if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || exponent.signum() <= 0) throw new IllegalArgumentException();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decode(parts[2]))) throw new IllegalArgumentException();
            Map<?, ?> claims = jsonPart(parts[1]);
            Object audience = claims.get("aud");
            if (!(clientId.equals(audience) || (audience instanceof List<?> a && !a.isEmpty() && a.size() <= 16
                    && a.stream().allMatch(String.class::isInstance) && a.contains(clientId)
                    && (a.size() == 1 || clientId.equals(claims.get("azp")))))) throw new IllegalArgumentException();
            long time = now.getEpochSecond();
            if (!issuer.equals(claims.get("iss")) || !(claims.get("exp") instanceof Long exp) || exp <= time - 5
                    || !(claims.get("iat") instanceof Long iat) || iat > time + 5 || exp <= iat
                    || (claims.get("nbf") != null && (!(claims.get("nbf") instanceof Long nbf) || nbf > time + 5))
                    || (claims.get("azp") != null && !clientId.equals(claims.get("azp")))) throw new IllegalArgumentException();
            if (nonce != null && !same(nonce, text(claims, "nonce", 256))) throw new IllegalArgumentException();
            String actualSubject = text(claims, "sub", 512);
            if (subject != null && !same(subject, actualSubject)) throw new IllegalArgumentException();
            return new Identity(actualSubject, claims.get("email") instanceof String email ? clean(email) : "ChatGPT account");
        } catch (Exception e) {
            throw new IOException("ChatGPT identity verification failed; sign in again");
        }
    }

    static String text(Map<?, ?> map, String key, int maximum) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum
                || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return text;
    }
    static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static String clean(String value) { return value.substring(0, Math.min(value.length(), 256)).replaceAll("[\\p{Cntrl}]", ""); }
    private static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        return Base64.getUrlDecoder().decode(value);
    }
    private static Map<?, ?> jsonPart(String part) throws IOException {
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(decode(part))).toString();
        Object value = JsonProtocol.read(json);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException();
        return map;
    }
    record Identity(String subject, String label) { }
}
