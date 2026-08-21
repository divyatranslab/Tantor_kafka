package io.translab.tantor.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Path exportDir;
    
    private String lastHash = "0000000000000000000000000000000000000000000000000000000000000000";

    public AuditExportService(AuditLogRepository repository, 
                              ObjectMapper objectMapper,
                              @Value("${tantor.audit.export.dir:/var/lib/tantor/audit}") String exportDirPath) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.exportDir = Paths.get(exportDirPath);
        
        try {
            if (!Files.exists(exportDir)) {
                Files.createDirectories(exportDir);
            }
        } catch (IOException e) {
            log.warn("Could not create audit export directory. Export will fail.", e);
        }
    }

    @Scheduled(fixedDelay = 60000) // Run every 60 seconds
    public void exportAuditLogs() {
        Path stateFile = exportDir.resolve("last_exported_timestamp.txt");
        Path logFile = exportDir.resolve("audit_export.jsonl");

        Instant lastTimestamp = Instant.EPOCH;
        if (Files.exists(stateFile)) {
            try {
                String tsStr = Files.readString(stateFile).trim();
                if (!tsStr.isBlank()) {
                    lastTimestamp = Instant.parse(tsStr);
                }
            } catch (Exception e) {
                log.warn("Failed to read state file, starting from EPOCH", e);
            }
        }

        List<AuditLog> newLogs = repository.findByCreatedTimeGreaterThanOrderByCreatedTimeAsc(lastTimestamp);
        
        if (newLogs.isEmpty()) {
            return;
        }

        log.info("Exporting {} new audit logs", newLogs.size());
        
        Instant maxTimestamp = lastTimestamp;
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            for (AuditLog audit : newLogs) {
                // Construct a redacted, safe export object
                Map<String, Object> exportRecord = Map.of(
                    "timestamp", audit.getCreatedTime().toString(),
                    "eventId", audit.getId().toString(),
                    "actor", audit.getCreatedBy() != null ? audit.getCreatedBy() : "UNKNOWN",
                    "operation", audit.getAction() != null ? audit.getAction() : "UNKNOWN",
                    "resource", audit.getResource() != null ? audit.getResource() : "UNKNOWN",
                    "result", audit.getStatus() != null ? audit.getStatus() : "UNKNOWN",
                    "correlationId", audit.getClusterId() != null ? audit.getClusterId().toString() : ""
                );
                
                String jsonLine = objectMapper.writeValueAsString(exportRecord);
                
                // Compute chained hash for integrity/tamper-evidence
                String dataToHash = lastHash + jsonLine;
                byte[] hashBytes = digest.digest(dataToHash.getBytes(StandardCharsets.UTF_8));
                lastHash = HexFormat.of().formatHex(hashBytes);
                
                // Append hash to record
                String finalLine = jsonLine.substring(0, jsonLine.length() - 1) + ",\"_integrityHash\":\"" + lastHash + "\"}\n";
                
                Files.writeString(logFile, finalLine, StandardCharsets.UTF_8, 
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        
                if (audit.getCreatedTime().isAfter(maxTimestamp)) {
                    maxTimestamp = audit.getCreatedTime();
                }
            }
            
            // Save state
            Files.writeString(stateFile, maxTimestamp.toString(), StandardCharsets.UTF_8, 
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Failed to export audit logs", e);
        }
    }
}
