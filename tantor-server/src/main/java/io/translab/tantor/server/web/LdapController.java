package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ldap")
public class LdapController {

    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", false);
        config.put("server_url", "");
        config.put("use_ssl", false);
        config.put("tls_validate_cert", true);
        config.put("tls_ca_cert_present", false);
        config.put("bind_dn", "");
        config.put("user_search_base", "");
        config.put("user_search_filter", "(sAMAccountName={username})");
        config.put("group_search_base", "");
        config.put("admin_group_dn", "");
        config.put("monitor_group_dn", "");
        config.put("default_role", "monitor");
        config.put("connection_timeout", 10);
        return ResponseEntity.ok(config);
    }

    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Connection successful (mock)"
        ));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncUsers() {
        return ResponseEntity.ok(Map.of("users", Collections.emptyList()));
    }
}
