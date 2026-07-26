package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.dto.LdapDTOs;
import io.translab.tantor.server.repository.LdapConfigRepository;
import io.translab.tantor.server.service.LdapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@org.springframework.validation.annotation.Validated
@RestController
@RequestMapping("/api/v1/ldap")
public class LdapController {

    private final LdapConfigRepository ldapConfigRepository;
    private final LdapService ldapService;

    public LdapController(LdapConfigRepository ldapConfigRepository, LdapService ldapService) {
        this.ldapConfigRepository = ldapConfigRepository;
        this.ldapService = ldapService;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/config")
    public ResponseEntity<LdapDTOs.LdapConfigResponse> getConfig() {
        return ldapConfigRepository.findAll().stream().findFirst()
                .map(config -> ResponseEntity.ok(toResponse(config)))
                .orElse(ResponseEntity.ok(null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/config")
    public ResponseEntity<LdapDTOs.LdapConfigResponse> updateConfig(@RequestBody LdapDTOs.LdapConfigCreateRequest request) {
        LdapConfig config = ldapConfigRepository.findAll().stream().findFirst().orElse(new LdapConfig());

        config.setEnabled(request.isEnabled());
        config.setServerUrl(request.getServerUrl());
        config.setUseSsl(request.isUseSsl());
        config.setTlsValidateCert(request.isTlsValidateCert());
        config.setTlsCaCert(request.getTlsCaCert());
        config.setBindDn(request.getBindDn());

        if (request.getBindPassword() != null && !request.getBindPassword().isEmpty()) {
            config.setEncryptedBindPassword(ldapService.encryptPassword(request.getBindPassword()));
        }

        config.setUserSearchBase(request.getUserSearchBase());
        config.setUserSearchFilter(request.getUserSearchFilter());
        config.setGroupSearchBase(request.getGroupSearchBase());
        config.setAdminGroupDn(request.getAdminGroupDn());
        config.setMonitorGroupDn(request.getMonitorGroupDn());
        config.setDefaultRole(request.getDefaultRole() != null ? request.getDefaultRole() : "monitor");
        config.setConnectionTimeout(request.getConnectionTimeout());

        config = ldapConfigRepository.save(config);
        return ResponseEntity.ok(toResponse(config));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/test")
    public ResponseEntity<LdapDTOs.LdapTestResponse> testConnection(@RequestBody LdapDTOs.LdapTestRequest request) {
        LdapConfig config = ldapConfigRepository.findAll().stream().findFirst().orElse(null);
        if (config == null) {
            return ResponseEntity.badRequest().body(new LdapDTOs.LdapTestResponse(false, "LDAP not configured", null, null));
        }

        String bindPassword = request.getBindPassword();
        if (bindPassword == null || bindPassword.isEmpty()) {
            bindPassword = ldapService.decryptPassword(config.getEncryptedBindPassword());
        }

        if (request.getUsername() != null && !request.getUsername().isEmpty()) {
            // Test auth
            return ResponseEntity.ok(ldapService.authenticate(request.getUsername(), request.getPassword(), config, bindPassword));
        } else {
            // Test connection only
            return ResponseEntity.ok(ldapService.testConnection(config, bindPassword));
        }
    }

    private LdapDTOs.LdapConfigResponse toResponse(LdapConfig config) {
        LdapDTOs.LdapConfigResponse response = new LdapDTOs.LdapConfigResponse();
        if (config.getId() != null) {
            response.setId(config.getId().toString());
        }
        response.setEnabled(config.isEnabled());
        response.setServerUrl(config.getServerUrl());
        response.setUseSsl(config.isUseSsl());
        response.setTlsValidateCert(config.isTlsValidateCert());
        response.setTlsCaCertPresent(config.getTlsCaCert() != null && !config.getTlsCaCert().isEmpty());
        response.setBindDn(config.getBindDn());
        response.setUserSearchBase(config.getUserSearchBase());
        response.setUserSearchFilter(config.getUserSearchFilter());
        response.setGroupSearchBase(config.getGroupSearchBase());
        response.setAdminGroupDn(config.getAdminGroupDn());
        response.setMonitorGroupDn(config.getMonitorGroupDn());
        response.setDefaultRole(config.getDefaultRole());
        response.setConnectionTimeout(config.getConnectionTimeout());
        return response;
    }
}
