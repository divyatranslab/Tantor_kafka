package io.translab.tantor.artifact.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.artifact.audit.ArtifactAuditService;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.service.ArtifactService;
import io.translab.tantor.artifact.service.ManifestService;
import io.translab.tantor.artifact.util.RoleAuthenticationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ArtifactControllerApiTest {

    @Mock ArtifactService artifactService;
    @Mock ManifestService manifestService;
    @Mock ArtifactAuditService auditService;
    @Mock RoleAuthenticationUtil roleAuthenticationUtil;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ArtifactController controller = new ArtifactController(
                artifactService, manifestService, new ObjectMapper(), auditService, roleAuthenticationUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(roleAuthenticationUtil.canAccess(any(), eq(RoleAuthenticationUtil.ARTIFACT_UPLOAD))).thenReturn(true);
        when(roleAuthenticationUtil.username(any())).thenReturn("admin");
    }

    @Test
    void uploadQueueSaturationReturns429TooManyRequests() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "test-data".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation queue is full; try again shortly"));

        mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.title", is("Too Many Requests")))
                .andExpect(jsonPath("$.detail", is("validation queue is full; try again shortly")))
                .andExpect(jsonPath("$.type", is("https://docs.translab.io/tantor/errors/too-many-requests")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadQueueTimeoutReturns503ServiceUnavailable() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "test-data".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("malware-scan queue wait timeout exceeded; try again shortly"));

        mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.title", is("Service Unavailable")))
                .andExpect(jsonPath("$.detail", is("malware-scan queue wait timeout exceeded; try again shortly")))
                .andExpect(jsonPath("$.type", is("https://docs.translab.io/tantor/errors/service-unavailable")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadShutdownReturns503ServiceUnavailable() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "test-data".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation admission controller is shutting down"));

        mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.title", is("Service Unavailable")))
                .andExpect(jsonPath("$.type", is("https://docs.translab.io/tantor/errors/service-unavailable")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadWaitInterruptedReturns503ServiceUnavailable() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "test-data".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation wait interrupted"));

        mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.title", is("Service Unavailable")))
                .andExpect(jsonPath("$.type", is("https://docs.translab.io/tantor/errors/service-unavailable")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void actualOversizedUploadReturns413PayloadTooLarge() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kafka_2.13-3.7.0.tgz", "application/gzip", "test-data".getBytes());

        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("Upload exceeds the configured maximum of 104857600 bytes"));

        mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status", is(413)))
                .andExpect(jsonPath("$.title", is("Upload limit exceeded")))
                .andExpect(jsonPath("$.detail", is("Upload exceeds the configured maximum of 104857600 bytes")))
                .andExpect(jsonPath("$.type", is("https://docs.translab.io/tantor/errors/upload-limit-exceeded")));
    }

    @Test
    void rawPutQueueSaturationReturns429TooManyRequests() throws Exception {
        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("Malware scanner capacity is full"));

        mockMvc.perform(put("/api/v1/artifacts")
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .param("fileName", "kafka_2.13-3.7.0.tgz")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("raw-content".getBytes())
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.title", is("Too Many Requests")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rawPutQueueTimeoutReturns503ServiceUnavailable() throws Exception {
        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("validation queue wait timeout exceeded; try again shortly"));

        mockMvc.perform(put("/api/v1/artifacts")
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .param("fileName", "kafka_2.13-3.7.0.tgz")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("raw-content".getBytes())
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.title", is("Service Unavailable")));

        verify(auditService, never()).record(anyString(), eq("PACKAGE_UPLOAD_FAILED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rawPutOversizedUploadReturns413PayloadTooLarge() throws Exception {
        when(artifactService.upload(any(), any(InputStream.class)))
                .thenThrow(new UploadLimitExceededException("Upload exceeds the configured maximum of 104857600 bytes"));

        mockMvc.perform(put("/api/v1/artifacts")
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0")
                        .param("fileName", "kafka_2.13-3.7.0.tgz")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("raw-content".getBytes())
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status", is(413)))
                .andExpect(jsonPath("$.title", is("Upload limit exceeded")));
    }
}
