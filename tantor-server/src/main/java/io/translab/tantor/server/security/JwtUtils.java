package io.translab.tantor.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class JwtUtils {

    private final ObjectMapper objectMapper;

    public JwtUtils(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Value("${tantor.security.jwt.secret}")
    private String jwtSecret;

    @Value("${tantor.security.jwt.expiration-ms}")
    private int jwtExpirationMs;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String generateTokenFromUsername(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date((new Date()).getTime() + jwtExpirationMs))
                .signWith(key())
                .compact();
    }

    public String getUserNameFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(authToken);
            return true;
        } catch (Exception e) {
            // Log exceptions if needed (ExpiredJwtException, UnsupportedJwtException, MalformedJwtException, etc.)
        }
        return false;
    }

    /** Resolve the user identity carried by Tantor or Keycloak access tokens. */
    public String getIdentityFromJwtToken(String token) {
        if (validateJwtToken(token)) {
            return getUserNameFromJwtToken(token);
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            Map<String, Object> claims = objectMapper.readValue(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            Object expiration = claims.get("exp");
            if (expiration instanceof Number number && number.longValue() <= System.currentTimeMillis() / 1000) {
                return null;
            }
            for (String claim : new String[]{"preferred_username", "email", "name", "sub"}) {
                Object value = claims.get(claim);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        } catch (Exception ignored) {
            // Invalid bearer tokens do not populate the security context.
        }
        return null;
    }
}
