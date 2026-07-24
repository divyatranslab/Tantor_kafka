package io.translab.tantor.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RoleAuthenticationUtil {

    public static final String CREATE_CLUSTER = "CREATE_CLUSTER";
    public static final String DELETE_CLUSTER = "DELETE_CLUSTER";
    public static final String ROLLING_RESTART = "ROLLING_RESTART";
    public static final String ADD_NODE = "ADD_NODE";
    public static final String CONFIGURATION_CHANGE = "CONFIGURATION_CHANGE";
    public static final String CONFIG_VERSION_CHANGE = "CONFIG_VERSION_CHANGE";
    public static final String BIND_AGENT = "BIND_AGENT";
    public static final String HOST_ONBOARDING = "HOST_ONBOARDING";
    public static final String HOST_REMOVE = "HOST_REMOVE";
    public static final String HOST_AVAILABILITY = "HOST_AVAILABILITY";
    public static final String HOST_REBOOT = "HOST_REBOOT";
    public static final String HOST_PREREQUISITES = "HOST_PREREQUISITES";
    public static final String TOPIC_MUTATION = "TOPIC_MUTATION";
    public static final String PRODUCE_MESSAGE = "PRODUCE_MESSAGE";
    public static final String SECURITY_CHANGE = "SECURITY_CHANGE";
    public static final String SCHEMA_REGISTRY_CHANGE = "SCHEMA_REGISTRY_CHANGE";
    public static final String KAFKA_CONNECT_CHANGE = "KAFKA_CONNECT_CHANGE";
    public static final String PARCEL_ACTION = "PARCEL_ACTION";
    public static final String ARTIFACT_UPLOAD = "ARTIFACT_UPLOAD";
    public static final String ARTIFACT_DELETE = "ARTIFACT_DELETE";
    public static final String BUNDLE_IMPORT = "BUNDLE_IMPORT";
    public static final String MONITORING_ENABLE = "MONITORING_ENABLE";
    public static final String JOB_CONTROL = "JOB_CONTROL";
    public static final String USER_MANAGEMENT = "USER_MANAGEMENT";

    private final ObjectMapper objectMapper;
    private final Map<String, Set<String>> allowedRolesByAction;
    private final String jwtSecret;

    public RoleAuthenticationUtil(ObjectMapper objectMapper, @Value("${tantor.security.jwt.secret}") String jwtSecret) {
        this.objectMapper = objectMapper;
        this.jwtSecret = jwtSecret;
        this.allowedRolesByAction = loadAllowedRoles();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public boolean canAccess(String authorizationHeader, String action) {
        Set<String> allowedRoles = allowedRolesByAction.get(normalizeAction(action));
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            return false; // Fail-closed explicitly handled
        }

        String token = bearerToken(authorizationHeader);
        if (token == null || token.isBlank()) {
            return false;
        }

        Map<String, Object> claims = decodeClaims(token);
        if (claims.isEmpty()) {
            return false;
        }

        Set<String> roles = extractRoles(claims);
        return roles.stream().anyMatch(allowedRoles::contains);
    }

    public String extractUsername(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token == null || token.isBlank()) {
            return "system";
        }
        Map<String, Object> claims = decodeClaims(token);
        if (claims.isEmpty()) {
            return "system";
        }
        if (claims.containsKey("preferred_username")) {
            return String.valueOf(claims.get("preferred_username"));
        }
        if (claims.containsKey("email")) {
            return String.valueOf(claims.get("email"));
        }
        if (claims.containsKey("name")) {
            return String.valueOf(claims.get("name"));
        }
        if (claims.containsKey("sub")) {
            return String.valueOf(claims.get("sub"));
        }
        return "system";
    }

    private Map<String, Set<String>> loadAllowedRoles() {
        try (InputStream input = new ClassPathResource("config/config.json").getInputStream()) {
            if (input == null) {
                throw new IllegalStateException("Security config.json not found. Failing closed.");
            }
            Map<String, List<String>> configured = objectMapper.readValue(input, new TypeReference<>() {});
            Map<String, Set<String>> normalized = new HashMap<>();
            configured.forEach((action, roles) -> {
                Set<String> roleSet = new HashSet<>();
                if (roles != null) {
                    roles.forEach(role -> roleSet.add(normalizeRole(role)));
                }
                normalized.put(normalizeAction(action), roleSet);
            });
            return Map.copyOf(normalized);
        } catch (IOException | IllegalArgumentException e) {
            // Fail closed by throwing an exception, blocking startup if config is invalid
            throw new IllegalStateException("Unable to load role endpoint config, failing closed.", e);
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private Map<String, Object> decodeClaims(String token) {
        try {
            // Cryptographically verify the token, fixing the decode-without-verify flaw
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new HashMap<>(claims);
        } catch (Exception e) {
            // Token is invalid or signature is forged
            return Map.of();
        }
    }

    private Set<String> extractRoles(Map<String, Object> claims) {
        Set<String> roles = new HashSet<>();
        collectRoleValue(claims.get("role"), roles);
        collectRoleValue(claims.get("roles"), roles);
        collectRoleValue(claims.get("authorities"), roles);

        Object realmAccess = claims.get("realm_access");
        if (realmAccess instanceof Map<?, ?> realmMap) {
            collectRoleValue(realmMap.get("roles"), roles);
        }

        Object resourceAccess = claims.get("resource_access");
        if (resourceAccess instanceof Map<?, ?> resourceMap) {
            for (Object clientAccess : resourceMap.values()) {
                if (clientAccess instanceof Map<?, ?> clientMap) {
                    collectRoleValue(clientMap.get("roles"), roles);
                }
            }
        }

        return roles;
    }

    private void collectRoleValue(Object value, Set<String> roles) {
        if (value instanceof String role) {
            roles.add(normalizeRole(role));
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    collectRoleValue(String.valueOf(item), roles);
                }
            }
        }
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("role_")) {
            normalized = normalized.substring(5);
        }
        return normalized;
    }

    private String normalizeAction(String action) {
        return action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
    }
}
