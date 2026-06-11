package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ui/hosts")
@RequiredArgsConstructor
@Slf4j
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

    /**
     * Check if a TCP port is free (not listening) on a given host.
     * Attempts a socket connection with a 2-second timeout.
     * Returns { "free": true/false, "host": ..., "port": ..., "message": ... }
     */
    @GetMapping("/{id}/check-port/{port}")
    public ResponseEntity<?> checkPort(@PathVariable String id, @PathVariable int port) {
        Host host = hostRepository.findById(id).orElse(null);
        if (host == null) return ResponseEntity.notFound().build();

        if (port < 1 || port > 65535) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid port number"));
        }

        // Parse the first IP from the JSON array stored in ipAddresses
        String targetIp = host.getHostname(); // fallback to hostname
        if (host.getIpAddresses() != null && !host.getIpAddresses().isEmpty()) {
            String raw = host.getIpAddresses().replace("[", "").replace("]", "").replace("\"", "").trim();
            if (!raw.isEmpty()) {
                targetIp = raw.split(",")[0].trim();
            }
        }

        boolean portInUse = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetIp, port), 2000);
            portInUse = true; // Connection succeeded → something is listening
        } catch (Exception e) {
            portInUse = false; // Connection refused or timed out → port is free
        }

        boolean isFree = !portInUse;
        String message = isFree
            ? "Port " + port + " is free on " + host.getHostname()
            : "Port " + port + " is already in use on " + host.getHostname();

        log.info("Port check: {}:{} → {}", targetIp, port, isFree ? "FREE" : "IN_USE");

        return ResponseEntity.ok(Map.of(
            "free", isFree,
            "host", host.getHostname(),
            "ip", targetIp,
            "port", port,
            "message", message
        ));
    }
}
