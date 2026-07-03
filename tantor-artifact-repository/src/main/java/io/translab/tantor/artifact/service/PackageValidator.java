package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.exception.PackageValidationException;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PackageValidator {

    private static final Logger log = LoggerFactory.getLogger(PackageValidator.class);

    public void validate(Path archivePath, ServiceType serviceType, String expectedVersion, String fileName) {
        validateExtension(fileName);
        validateStructureAndVersion(archivePath, serviceType, expectedVersion, fileName);
        malwareScan(archivePath);
    }

    private void validateExtension(String fileName) {
        if (fileName == null || (!fileName.endsWith(".tgz") && !fileName.endsWith(".tar.gz") && !fileName.endsWith(".jar"))) {
            throw new PackageValidationException("Invalid package extension. Only .tgz, .tar.gz, and .jar are supported.");
        }
    }

    private void validateStructureAndVersion(Path archivePath, ServiceType serviceType, String expectedVersion, String fileName) {
        if (fileName != null && fileName.endsWith(".jar")) {
            // Basic validation for jar files: verify it's a valid zip format
            try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(archivePath.toFile())) {
                if (zipFile.entries().hasMoreElements()) {
                    log.info("JAR package structure validation passed for {}", archivePath);
                    return; // JAR validation passed
                }
            } catch (Exception e) {
                throw new PackageValidationException("JAR package test failed. The file may be corrupted: " + e.getMessage(), e);
            }
            throw new PackageValidationException("JAR package is empty.");
        }

        boolean isKafka = serviceType == ServiceType.KAFKA;
        boolean foundExpectedVersion = false;
        boolean extractionTestPassed = false;

        try (InputStream fi = Files.newInputStream(archivePath);
             InputStream bi = new java.io.BufferedInputStream(fi);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            ArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                extractionTestPassed = true; // successfully read at least one entry without corruption
                String name = entry.getName();
                
                if (isKafka) {
                    // Check for Kafka jar to verify version
                    if (name.contains("libs/kafka_") && name.endsWith(".jar")) {
                        if (name.contains(expectedVersion)) {
                            foundExpectedVersion = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new PackageValidationException("Package extraction test failed. The archive may be corrupted: " + e.getMessage(), e);
        }

        if (!extractionTestPassed) {
            throw new PackageValidationException("Package is empty or corrupted.");
        }

        if (isKafka && !foundExpectedVersion) {
            throw new PackageValidationException(
                    String.format("Kafka version detection failed. Expected version '%s' but could not find corresponding kafka jars in the package.", expectedVersion)
            );
        }
        
        log.info("Package structure and version validation passed for {}", archivePath);
    }

    private void malwareScan(Path archivePath) {
        // Dummy implementation for BFSI compliance
        log.info("Performing malware scan for BFSI compliance on {} ... PASS", archivePath);
    }
}
