package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.HostParcel;
import io.translab.tantor.server.service.ParcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ui/parcels")
@RequiredArgsConstructor
public class ParcelController {
    private final ParcelService parcelService;
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping
    public List<HostParcel> listStates() {
        return parcelService.listStates();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{artifactId}/distribute")
    public ResponseEntity<Map<String, Object>> distribute(
            
            @PathVariable UUID artifactId,
            @RequestBody ParcelService.ParcelActionRequest request
    ) {
        return scheduled(parcelService.distribute(artifactId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{artifactId}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            
            @PathVariable UUID artifactId,
            @RequestBody ParcelService.ParcelActionRequest request
    ) {
        return scheduled(parcelService.activate(artifactId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{artifactId}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(
            
            @PathVariable UUID artifactId,
            @RequestBody ParcelService.ParcelActionRequest request
    ) {
        return scheduled(parcelService.deactivate(artifactId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{artifactId}/remove")
    public ResponseEntity<Map<String, Object>> remove(
            
            @PathVariable UUID artifactId,
            @RequestBody ParcelService.ParcelActionRequest request
    ) {
        return scheduled(parcelService.remove(artifactId, request));
    }

    private ResponseEntity<Map<String, Object>> scheduled(List<HostParcel> parcels) {
        return ResponseEntity.ok(Map.of(
                "scheduled", parcels.size(),
                "states", parcels
        ));
    }
}
