package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ui/hosts")
@RequiredArgsConstructor
public class HostController {

    private final HostRepository hostRepository;

    @GetMapping
    public ResponseEntity<List<Host>> getAllHosts() {
        return ResponseEntity.ok(hostRepository.findAll());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Host> approveHost(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            host.setStatus("ONLINE");
            hostRepository.save(host);
            return ResponseEntity.ok(host);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHost(@PathVariable String id) {
        if (hostRepository.existsById(id)) {
            hostRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
