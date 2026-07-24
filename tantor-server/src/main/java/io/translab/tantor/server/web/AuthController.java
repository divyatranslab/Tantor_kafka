package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.domain.User;
import io.translab.tantor.server.dto.LdapDTOs;
import io.translab.tantor.server.repository.LdapConfigRepository;
import io.translab.tantor.server.repository.UserRepository;
import io.translab.tantor.server.service.LdapService;
import io.translab.tantor.security.JwtUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import io.translab.tantor.server.dto.UserDto;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@org.springframework.validation.annotation.Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;
    private final LdapConfigRepository ldapConfigRepository;
    private final LdapService ldapService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<JwtResponse> authenticateUser(@RequestBody LoginRequest loginRequest) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();

        LdapConfig ldapConfig = ldapConfigRepository.findAll().stream().findFirst().orElse(null);
        boolean ldapSuccess = false;

        if (ldapConfig != null && ldapConfig.isEnabled()) {
            String bindPassword = ldapService.decryptPassword(ldapConfig.getEncryptedBindPassword());
            LdapDTOs.LdapTestResponse ldapResponse = ldapService.authenticate(username, password, ldapConfig, bindPassword);
            
            if (ldapResponse.isSuccess()) {
                ldapSuccess = true;
                
                // Sync user to local DB
                User user = userRepository.findByUsername(username).orElse(new User());
                user.setUsername(username);
                // Assign a dummy password hash as they login via LDAP
                user.setPasswordHash(""); 
                user.setAuthSource("ldap");
                user.setLdapDn(ldapResponse.getUserDn());
                // In a real scenario, map ldap groups to role here
                userRepository.save(user);
                
                String jwt = jwtUtils.generateTokenFromUsername(username);
                return ResponseEntity.ok(new JwtResponse(jwt));
            }
        }

        // Fallback to local auth
        if (!ldapSuccess) {
            User user = userRepository.findByUsername(username).orElse(null);
            if (user != null && user.isActive() && passwordEncoder.matches(password, user.getPasswordHash())) {
                String jwt = jwtUtils.generateTokenFromUsername(username);
                return ResponseEntity.ok(new JwtResponse(jwt));
            }
        }
        
        return ResponseEntity.status(401).build();
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
