package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiBaselineServiceTest {
    private AiDeviceProjectRepository projects;
    private AiPackageRepository packages;
    private AiApiDraftRepository drafts;
    private AiEvidenceRepository evidence;
    private AiBaselineRepository baselines;
    private AiBaselineService service;
    private final String observationId = "01234567-89ab-4cde-8fab-0123456789ab";

    @BeforeEach void setUp() {
        projects = mock(AiDeviceProjectRepository.class);
        packages = mock(AiPackageRepository.class);
        drafts = mock(AiApiDraftRepository.class);
        evidence = mock(AiEvidenceRepository.class);
        baselines = mock(AiBaselineRepository.class);
        when(projects.existsById("project")).thenReturn(true);
        when(packages.findById("package")).thenReturn(Optional.of(new AiPackageEntity("package", "project", "device.zip", String.join("", Collections.nCopies(64, "a")), 100, "path")));
        when(drafts.findById("draft")).thenReturn(Optional.of(new AiApiDraftEntity("draft", "project", "session", "{}")));
        when(evidence.findById(observationId)).thenReturn(Optional.of(new AiEvidenceEntity(observationId, "project", "session", 1,
                "{\"id\":\"" + observationId + "\",\"method\":\"GET\",\"path\":\"/api/config\",\"status\":200,\"bodyUsable\":true}")));
        when(baselines.save(any(AiBaselineEntity.class))).thenAnswer(call -> call.getArgument(0));
        service = new AiBaselineService(projects, packages, drafts, evidence, baselines, new ObjectMapper());
    }

    private Map<String, Object> scenario() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("observationId", observationId);
        step.put("action", "read config");
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("kind", "TEXT_CONTAINS"); checkpoint.put("value", "配置已保存");
        step.put("checkpoint", checkpoint);
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("path", "enabled"); comparison.put("policy", "EXACT"); comparison.put("expected", false);
        step.put("comparisons", Collections.singletonList(comparison));
        return Collections.singletonMap("steps", Collections.singletonList(step));
    }

    @Test void freezesOrderedScopedEvidence() {
        Map<String, Object> result = service.createBaseline("project", "package", "draft", "session", scenario(), "maker");
        assertEquals("project", result.get("projectId"));
        assertEquals(String.join("", Collections.nCopies(64, "a")), result.get("packageSha256"));
        assertTrue(String.valueOf(result.get("snapshotSha256")).matches("[a-f0-9]{64}"));
        assertEquals(observationId, ((Map<?, ?>) ((List<?>) ((Map<?, ?>) result.get("scenario")).get("steps")).get(0)).get("observationId"));
        verify(baselines).save(any(AiBaselineEntity.class));
    }

    @Test void rejectsForeignObservationAndPackage() {
        when(evidence.findById(observationId)).thenReturn(Optional.of(new AiEvidenceEntity(observationId, "other", "session", 1, "{}")));
        assertThrows(IllegalArgumentException.class, () -> service.createBaseline("project", "package", "draft", "session", scenario(), "maker"));
        verify(baselines, never()).save(any());
        when(packages.findById("package")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.createBaseline("project", "package", "draft", "session", scenario(), "maker"));
    }

    @Test void rejectsRedactedOrTruncatedCriticalBody() {
        when(evidence.findById(observationId)).thenReturn(Optional.of(new AiEvidenceEntity(observationId, "project", "session", 1,
                "{\"method\":\"PUT\",\"path\":\"/api/config\",\"truncated\":true,\"bodyUsable\":false}")));
        assertThrows(IllegalArgumentException.class, () -> service.createBaseline("project", "package", "draft", "session", scenario(), "maker"));
        verify(baselines, never()).save(any());
    }

    @Test void rejectsUnapprovedIgnoreAndUnsafeCheckpoint() {
        Map<String, Object> scenario = scenario();
        Map<String, Object> step = (Map<String, Object>) ((List<?>) scenario.get("steps")).get(0);
        ((Map<String, Object>) ((List<?>) step.get("comparisons")).get(0)).put("policy", "IGNORE");
        assertThrows(IllegalArgumentException.class, () -> service.createBaseline("project", "package", "draft", "session", scenario, "maker"));
        step.put("comparisons", Collections.emptyList());
        Map<String, Object> unsafe = new LinkedHashMap<>();
        unsafe.put("kind", "SCRIPT"); unsafe.put("value", "alert(1)");
        step.put("checkpoint", unsafe);
        assertThrows(IllegalArgumentException.class, () -> service.createBaseline("project", "package", "draft", "session", scenario, "maker"));
        verify(baselines, never()).save(any());
    }
}
