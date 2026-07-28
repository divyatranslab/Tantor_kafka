package io.translab.tantor.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String clientId;

    public KeycloakRoleConverter() {
        this("apb-kafka");
    }

    public KeycloakRoleConverter(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        addRoles(roles, jwt.getClaims().get("roles"));

        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> realmClaims) {
            addRoles(roles, realmClaims.get("roles"));
        }

        Object resourceAccess = jwt.getClaims().get("resource_access");
        if (resourceAccess instanceof Map<?, ?> resources) {
            Object clientAccess = resources.get(clientId);
            if (clientAccess instanceof Map<?, ?> clientClaims) {
                addRoles(roles, clientClaims.get("roles"));
            }
        }

        if (roles.isEmpty()) {
            return new ArrayList<>();
        }

        return roles.stream()
                .map(KeycloakRoleConverter::normalizeRole)
                .filter(roleName -> roleName != null)
                .map(roleName -> "ROLE_" + roleName)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    private static void addRoles(Set<String> target, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(target::add);
        } else if (claim instanceof String value) {
            target.add(value);
        }
    }

    private static String normalizeRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase().replace(' ', '_');
        if (role.startsWith("ROLE_")) {
            role = role.substring("ROLE_".length());
        }
        return switch (role) {
            case "ADMINISTRATOR" -> "ADMIN";
            case "VIEWER", "READONLY", "READ_ONLY" -> "MONITOR";
            case "ADMIN", "MONITOR", "AGENT" -> role;
            default -> null;
        };
    }
}
