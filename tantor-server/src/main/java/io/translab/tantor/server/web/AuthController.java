package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.domain.User;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.dto.LdapDTOs;
import io.translab.tantor.server.repository.LdapConfigRepository;
import io.translab.tantor.server.repository.UserRepository;
import io.translab.tantor.server.service.LdapService;
import io.translab.tantor.server.security.JwtUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import io.translab.tantor.server.dto.UserDto;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;
    private final LdapConfigRepository ldapConfigRepository;
    private final LdapService ldapService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${tantor.security.local-break-glass.enabled:false}")
    private boolean breakGlassEnabled;

    @Value("${tantor.security.local-break-glass.username:}")
    private String breakGlassUsername;

    // Basic in-memory fallback rate limiter for direct-access protection.
    // Nginx provides the primary distributed rate limiting in production.
    private static class BoundedRateLimiter {
        private final int maxEntries;
        private final int maxAttempts;
        private final long timeWindowMs;
        
        private static class Attempt {
            int count = 0;
            long firstAttempt = System.currentTimeMillis();
        }

        private final Map<String, Attempt> store;

        public BoundedRateLimiter(int maxEntries, int maxAttempts, long timeWindowMs) {
            this.maxEntries = maxEntries;
            this.maxAttempts = maxAttempts;
            this.timeWindowMs = timeWindowMs;
            this.store = new java.util.LinkedHashMap<>(maxEntries, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Attempt> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        public synchronized boolean tryAcquire(String key) {
            long now = System.currentTimeMillis();
            Attempt attempt = store.computeIfAbsent(key, k -> new Attempt());
            if (now - attempt.firstAttempt > timeWindowMs) {
                attempt.count = 1;
                attempt.firstAttempt = now;
                return true;
            }
            return ++attempt.count <= maxAttempts;
        }
    }

    private final BoundedRateLimiter ipLimiter = new BoundedRateLimiter(1000, 10, 60000);
    private final BoundedRateLimiter userLimiter = new BoundedRateLimiter(1000, 5, 60000);

    @PostMapping("/login")
    public ResponseEntity<JwtResponse> authenticateUser(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String username = loginRequest.getUsername();
        
        boolean ipAllowed = ipLimiter.tryAcquire(clientIp != null ? clientIp : "unknown");
        boolean userAllowed = username != null && !username.isBlank() ? userLimiter.tryAcquire(username) : true;

        if (!ipAllowed || !userAllowed) {
            auditService.recordAs("system", "RATE_LIMIT", null, "AUTHENTICATION", "LOGIN_REJECTED", 
                                  "IP", clientIp, null, "FAILED", null, null, null, Map.of("reason", "Too many attempts"));
            return ResponseEntity.status(429).build();
        }

        String password = loginRequest.getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        username = username.trim();

        LdapConfig ldapConfig = ldapConfigRepository.findAll().stream().findFirst().orElse(null);
        boolean ldapEnabled = ldapConfig != null && ldapConfig.isEnabled();

        if (ldapEnabled) {
            String bindPassword = ldapService.decryptPassword(ldapConfig.getEncryptedBindPassword());
            LdapDTOs.LdapTestResponse ldapResponse = ldapService.authenticate(username, password, ldapConfig, bindPassword);
            
            if (ldapResponse.isSuccess()) {
                User user = userRepository.findByUsername(username).orElse(null);
                if (user != null && !user.isActive()) {
                    return ResponseEntity.status(403).build();
                }
                if (user == null) user = new User();
                user.setUsername(username);
                user.setPasswordHash("");
                user.setAuthSource("ldap");
                user.setLdapDn(ldapResponse.getUserDn());
                user.setRole(roleForGroups(ldapResponse.getGroups(), ldapConfig));
                user.setActive(true);
                userRepository.save(user);
                
                String jwt = jwtUtils.generateToken(username, user.getRole(), "ldap");
                return ResponseEntity.ok(new JwtResponse(jwt));
            }
        }

        // LDAP mode does not silently fall back to local credentials. The only
        // exception is the explicitly configured, audited break-glass account.
        boolean breakGlassLogin = ldapEnabled && isBreakGlassUser(username);
        if (!ldapEnabled || breakGlassLogin) {
            User user = userRepository.findByUsername(username).orElse(null);
            if (user != null && (user.getAuthSource() == null || "local".equalsIgnoreCase(user.getAuthSource()))
                    && user.isActive() && passwordEncoder.matches(password, user.getPasswordHash())) {
                if (breakGlassLogin && !"admin".equalsIgnoreCase(user.getRole())) {
                    return ResponseEntity.status(403).build();
                }
                if (breakGlassLogin) {
                    auditService.recordAs(username, "LOCAL_BREAK_GLASS", null,
                            "AUTHENTICATION", "BREAK_GLASS_LOGIN", "USER", username,
                            null, "SUCCESS", null, null, null,
                            Map.of("reason", "LDAP authentication was unavailable or rejected"));
                }
                String jwt = jwtUtils.generateToken(username, user.getRole(), "local");
                return ResponseEntity.ok(new JwtResponse(jwt));
            }
        }
        
        return ResponseEntity.status(401).build();
    }

    private boolean isBreakGlassUser(String username) {
        return breakGlassEnabled && breakGlassUsername != null && !breakGlassUsername.isBlank()
                && breakGlassUsername.equalsIgnoreCase(username);
    }

    private String roleForGroups(List<String> groups, LdapConfig config) {
        List<String> safeGroups = groups == null ? List.of() : groups;
        if (groupPresent(safeGroups, config.getAdminGroupDn())) return "admin";
        if (groupPresent(safeGroups, config.getMonitorGroupDn())) return "monitor";
        return "admin".equalsIgnoreCase(config.getDefaultRole()) ? "admin" : "monitor";
    }

    private boolean groupPresent(List<String> groups, String configuredGroup) {
        if (configuredGroup == null || configuredGroup.isBlank()) return false;
        String expected = normalizeDn(configuredGroup);
        return groups.stream().map(this::normalizeDn).anyMatch(expected::equals);
    }

    private String normalizeDn(String dn) {
        return dn == null ? "" : dn.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto.UserResponse> getMe() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }
        
        String username = auth.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).build();
        }
        
        UserDto.UserResponse response = new UserDto.UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.set_active(user.isActive());
        response.setCreated_at(user.getCreatedAt());
        response.setLast_login(user.getUpdatedAt()); // Using updatedAt for now
        response.setAuth_source(user.getAuthSource());
        response.setLdap_dn(user.getLdapDn());
        
        return ResponseEntity.ok(response);
    }

    @Data
    static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    static class JwtResponse {
        private final String token;
    }
}
