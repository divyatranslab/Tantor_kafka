package io.translab.tantor.artifact.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.artifact.audit.ArtifactAuditService;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.exception.PackageValidationException;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.service.ArtifactService;
import io.translab.tantor.artifact.service.ManifestService;
import io.translab.tantor.artifact.util.RoleAuthenticationUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactControllerTest {

    @Mock ArtifactService artifactService;
    @Mock ManifestService manifestService;
    @Mock ArtifactAuditService auditService;
    @Mock RoleAuthenticationUtil roleAuthenticationUtil;

    ArtifactController controller;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new ArtifactController(artifactService, manifestService, objectMapper, auditService, roleAuthenticationUtil);
        lenient().when(roleAuthenticationUtil.canAccess(any(), eq(RoleAuthenticationUtil.ARTIFACT_UPLOAD))).thenReturn(true);
        lenient().when(roleAuthenticationUtil.username(any())).thenReturn("admin");
    }

    @Test
    void capacityRejectionDoesNotCreateFailedAuditRecordInMultipartUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "payload".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("Validation capacity is full"));

        assertThatThrownBy(() -> controller.upload(
                ServiceType.KAFKA, "3.7.0", null, null, null, null, null, null, null, false, file, "Bearer token", request))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Validation capacity is full");

        // Crucial requirement: No failed-artifact audit record when temporarily at capacity
        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void capacityRejectionDoesNotCreateFailedAuditRecordInRawPutUpload() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("raw payload".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("Malware scanner capacity is full"));

        assertThatThrownBy(() -> controller.uploadRaw(
                ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz", null, null, null, null, null, null, null, false, "Bearer token", request))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Malware scanner capacity is full");

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void queueTimeoutDoesNotCreateFailedAuditRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "payload".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation queue wait timeout exceeded; try again shortly"));

        assertThatThrownBy(() -> controller.upload(
                ServiceType.KAFKA, "3.7.0", null, null, null, null, null, null, null, false, file, "Bearer token", request))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("queue wait timeout exceeded");

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shutdownRejectionDoesNotCreateFailedAuditRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "payload".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation admission controller is shutting down"));

        assertThatThrownBy(() -> controller.upload(
                ServiceType.KAFKA, "3.7.0", null, null, null, null, null, null, null, false, file, "Bearer token", request))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("shutting down");

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void actualValidationFailureCreatesFailedAuditRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "payload".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new PackageValidationException("Corrupted archive"));

        assertThatThrownBy(() -> controller.upload(
                ServiceType.KAFKA, "3.7.0", null, null, null, null, null, null, null, false, file, "Bearer token", request))
                .isInstanceOf(PackageValidationException.class)
                .hasMessageContaining("Corrupted archive");

        verify(auditService).record(eq("admin"), eq("PACKAGE_UPLOAD_FAILED"), eq("3.7.0"), eq("FAILED"), any(), any(), any(), any());
    }

    @Test
    void listClampsPageSizeToConfiguredMax() {
        org.springframework.data.domain.Page<io.translab.tantor.artifact.domain.Artifact> emptyPage =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of());

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        when(artifactService.list(any(), any(), captor.capture())).thenReturn(emptyPage);

        // Request size 500 when max configured is 100
        controller.list(null, null, 0, 500, "createdAt", org.springframework.data.domain.Sort.Direction.DESC);

        org.springframework.data.domain.Pageable pageable = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(pageable.getPageSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void listFallsBackToCreatedAtWhenSortNotWhitelisted() {
        org.springframework.data.domain.Page<io.translab.tantor.artifact.domain.Artifact> emptyPage =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of());

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        when(artifactService.list(any(), any(), captor.capture())).thenReturn(emptyPage);

        // SQL injection / arbitrary property attempt
        controller.list(null, null, 0, 20, "maliciousProperty;DROP TABLE;", org.springframework.data.domain.Sort.Direction.DESC);

        org.springframework.data.domain.Pageable pageable = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
    }
}
