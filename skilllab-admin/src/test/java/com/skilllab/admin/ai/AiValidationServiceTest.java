package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class AiValidationServiceTest {
    private final AiSimulatorVersionRepository versions = mock(AiSimulatorVersionRepository.class);
    private final AiSimulatorDraftRepository drafts = mock(AiSimulatorDraftRepository.class);
    private final AiPackageRepository packages = mock(AiPackageRepository.class);
    private final AiValidationRepository reports = mock(AiValidationRepository.class);
    private final AiPreviewService previews = mock(AiPreviewService.class);
    private final AiWorkerClient worker = mock(AiWorkerClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiValidationService service = new AiValidationService(versions, drafts, packages, reports, previews, worker, mapper);

    private AiSimulatorVersionEntity candidate() throws Exception {
        String json = "{\"schemaVersion\":\"skilllab.simulator-behavior.v2\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte item : digest) hash.append(String.format("%02x", item & 0xff));
        AiSimulatorVersionEntity version = new AiSimulatorVersionEntity("version", "draft", "project", "maker", "CANDIDATE", hash('a'), hash.toString());
        when(versions.findById("version")).thenReturn(Optional.of(version));
        when(drafts.findById("draft")).thenReturn(Optional.of(new AiSimulatorDraftEntity("draft", "project", "pkg", "api", 1, "DRAFT", json)));
        when(packages.findById("pkg")).thenReturn(Optional.of(new AiPackageEntity("pkg", "project", "device.zip", hash('a'), 1, "/tmp/device.zip")));
        when(worker.getJson("/internal/validator-runtime")).thenReturn(Collections.singletonMap("runtimeHash", hash('b')));
        return version;
    }

    @Test void blocksFailedReportAndMissingRiskAcknowledgement() throws Exception {
        candidate();
        AiValidationEntity blocked = new AiValidationEntity("blocked", "version", "project", "maker", hash('a'),
                "unused", hash('b'), "BLOCKED", "{\"status\":\"BLOCKED\",\"blockers\":[{}],\"warnings\":[]}");
        when(reports.findById("blocked")).thenReturn(Optional.of(blocked));
        assertThrows(IllegalStateException.class, () -> service.enable("project", "version", "blocked", "maker", true));

        String hash = versions.findById("version").get().getBehaviorSha256();
        AiValidationEntity warned = new AiValidationEntity("warned", "version", "project", "maker", hash('a'),
                hash, hash('b'), "PASSED", "{\"status\":\"PASSED\",\"runtimeHash\":\"" + hash('b') + "\",\"blockers\":[],\"warnings\":[{}]}");
        when(reports.findById("warned")).thenReturn(Optional.of(warned));
        assertThrows(IllegalStateException.class, () -> service.enable("project", "version", "warned", "maker", false));
        assertEquals("ENABLED", service.enable("project", "version", "warned", "maker", true).get("status"));
        assertThrows(IllegalStateException.class, () -> service.enable("project", "version", "warned", "maker", true));
    }

    @Test void rejectsRuntimeChangeAndOtherCreator() throws Exception {
        AiSimulatorVersionEntity version = candidate();
        AiValidationEntity report = new AiValidationEntity("report", "version", "project", "maker", hash('a'),
                version.getBehaviorSha256(), hash('c'), "PASSED", "{\"status\":\"PASSED\",\"blockers\":[],\"warnings\":[]}");
        when(reports.findById("report")).thenReturn(Optional.of(report));
        assertThrows(SecurityException.class, () -> service.enable("project", "version", "report", "other", true));
        assertThrows(IllegalStateException.class, () -> service.enable("project", "version", "report", "maker", true));
    }

    private String hash(char value) {
        char[] chars = new char[64]; java.util.Arrays.fill(chars, value); return new String(chars);
    }
}
