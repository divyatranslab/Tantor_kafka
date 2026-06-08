package io.translab.tantor.server.web;

import io.translab.tantor.server.security.JwtUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;

    @PostMapping("/login")
    public ResponseEntity<JwtResponse> authenticateUser(@RequestBody LoginRequest loginRequest) {
        // Dummy authentication for now. In reality, use AuthenticationManager
        if ("admin".equals(loginRequest.getUsername()) && "admin".equals(loginRequest.getPassword())) {
            String jwt = jwtUtils.generateTokenFromUsername(loginRequest.getUsername());
            return ResponseEntity.ok(new JwtResponse(jwt));
        }
        return ResponseEntity.status(401).build();
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
