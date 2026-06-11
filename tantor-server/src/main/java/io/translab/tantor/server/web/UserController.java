package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUsers() {
        return ResponseEntity.ok(List.of(
            Map.of(
                "id", UUID.randomUUID().toString(),
                "username", "admin",
                "role", "admin",
                "is_active", true,
                "auth_source", "local",
                "created_at", Instant.now().toString()
            )
        ));
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("message", "User created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("message", "User updated successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }
}
