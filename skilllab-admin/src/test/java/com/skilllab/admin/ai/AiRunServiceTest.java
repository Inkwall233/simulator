package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;

class AiRunServiceTest {
    @Test
    void candidateRequiresMatchingUploadedPackage() {
        AiSimulatorVersionRepository versions = mock(AiSimulatorVersionRepository.class);
        AiSimulatorDraftRepository drafts = mock(AiSimulatorDraftRepository.class);
        AiPackageRepository packages = mock(AiPackageRepository.class);
        AiSimulatorDraftEntity draft = new AiSimulatorDraftEntity("draft", "project", "pkg", "api", 1,
                "DRAFT", "{\"schemaVersion\":\"skilllab.simulator-behavior.v2\",\"packageVersion\":{\"id\":\"pkg\",\"sha256\":\"wrong\"}}");
        when(drafts.findByIdAndProjectId("draft", "project")).thenReturn(Optional.of(draft));
        when(versions.findByDraftId("draft")).thenReturn(Optional.empty());
        when(packages.findById("pkg")).thenReturn(Optional.of(new AiPackageEntity("pkg", "project", "package.zip",
                "correct", 1, "/tmp/package.zip")));
        AiRunService service = new AiRunService(versions, mock(AiRunRepository.class), mock(AiWorkerClient.class),
                drafts, packages, mock(AiPreviewService.class), new ObjectMapper(),
                "http://{runId}.localhost:3001", 30000, 600000, 28800000);
        assertThrows(IllegalArgumentException.class, () -> service.registerCandidate("project", "draft", "maker"));
        verify(versions, never()).save(any(AiSimulatorVersionEntity.class));
    }

    @Test
    void registrationFailureRemovesDerivedRunPackage() {
        AiSimulatorVersionRepository versions = mock(AiSimulatorVersionRepository.class);
        AiRunRepository runs = mock(AiRunRepository.class);
        AiSimulatorDraftRepository drafts = mock(AiSimulatorDraftRepository.class);
        AiPreviewService previews = mock(AiPreviewService.class);
        AiWorkerClient worker = mock(AiWorkerClient.class);
        when(versions.findById("v1")).thenReturn(Optional.of(
                new AiSimulatorVersionEntity("v1", "draft", "project", "maker", "ENABLED")));
        AiSimulatorDraftEntity draft = mock(AiSimulatorDraftEntity.class);
        when(drafts.findById("draft")).thenReturn(Optional.of(draft));
        when(draft.getPayloadJson()).thenReturn("{\"packageVersion\":{\"id\":\"pkg\"}}");
        when(previews.createRunPackage(anyString(), eq("pkg"))).thenReturn(java.nio.file.Paths.get("/tmp/run"));
        when(runs.save(any(AiRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(worker.post(eq("/internal/runs"), anyMap())).thenThrow(new IllegalStateException("Worker unavailable"));
        AiRunService service = new AiRunService(versions, runs, worker, drafts, mock(AiPackageRepository.class),
                previews, new ObjectMapper(), "http://{runId}.localhost:3001", 30000, 600000, 28800000);
        assertThrows(IllegalStateException.class, () -> service.startRun("v1", "maker"));
        verify(previews).deleteRunPackage(anyString());
    }

    @Test
    void draftCannotStartUntilEnabledAndRunBelongsToItsCreator() {
        AiSimulatorVersionRepository versions = mock(AiSimulatorVersionRepository.class);
        AiRunRepository runs = mock(AiRunRepository.class);
        AiSimulatorDraftRepository drafts = mock(AiSimulatorDraftRepository.class);
        AiPackageRepository packages = mock(AiPackageRepository.class);
        AiPreviewService previews = mock(AiPreviewService.class);
        AiWorkerClient worker = mock(AiWorkerClient.class);
        AiSimulatorVersionEntity candidate = new AiSimulatorVersionEntity("v1", "draft", "project", "maker", "CANDIDATE");
        when(versions.findById("v1")).thenReturn(Optional.of(candidate));
        AiRunService service = new AiRunService(versions, runs, worker, drafts, packages, previews, new ObjectMapper(), "http://{runId}.localhost:3001", 30000, 600000, 28800000);
        assertThrows(IllegalStateException.class, () -> service.startRun("v1", "maker"));
        verify(runs, never()).save(any(AiRunEntity.class));
        candidate.enable();
        AiSimulatorDraftEntity draft = Mockito.mock(AiSimulatorDraftEntity.class);
        when(drafts.findById("draft")).thenReturn(Optional.of(draft));
        when(draft.getPayloadJson()).thenReturn("{\"packageVersion\":{\"id\":\"pkg\"}}");
        when(previews.createRunPackage(anyString(), eq("pkg"))).thenReturn(java.nio.file.Paths.get("/tmp/run"));
        java.util.Map<String, AiRunEntity> savedRuns = new java.util.HashMap<String, AiRunEntity>();
        when(runs.save(any(AiRunEntity.class))).thenAnswer(invocation -> {
            AiRunEntity saved = invocation.getArgument(0); savedRuns.put(saved.getId(), saved); return saved;
        });
        when(runs.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedRuns.get(invocation.getArgument(0))));
        when(worker.post(anyString(), anyMap())).thenReturn(java.util.Collections.emptyMap());
        java.util.Map<String, Object> run = service.startRun("v1", "maker");
        String runId = (String) run.get("runId");
        assertNotNull(runId);
        assertThrows(SecurityException.class, () -> service.heartbeat(runId, "other", Instant.now()));
        assertThrows(SecurityException.class, () -> service.endRun(runId, "other"));
        assertDoesNotThrow(() -> service.endRun(runId, "maker"));
    }

    @Test
    void expiredRunCannotBeRenewedAndDisabledVersionCannotStartNewRun() {
        AiSimulatorVersionRepository versions = mock(AiSimulatorVersionRepository.class);
        AiRunRepository runs = mock(AiRunRepository.class);
        AiSimulatorDraftRepository drafts = mock(AiSimulatorDraftRepository.class);
        AiPackageRepository packages = mock(AiPackageRepository.class);
        AiPreviewService previews = mock(AiPreviewService.class);
        AiWorkerClient worker = mock(AiWorkerClient.class);
        AiSimulatorVersionEntity enabled = new AiSimulatorVersionEntity("v1", "draft", "project", "maker", "ENABLED");
        AiRunEntity expired = new AiRunEntity("run", "v1", "project", "maker", Instant.now().minusSeconds(1000), Instant.now().minusSeconds(10), Instant.now().plusSeconds(100));
        when(versions.findById("v1")).thenReturn(Optional.of(enabled));
        when(runs.findById("run")).thenReturn(Optional.of(expired));
        AiRunService service = new AiRunService(versions, runs, worker, drafts, packages, previews, new ObjectMapper(), "http://{runId}.localhost:3001", 30000, 600000, 28800000);
        assertThrows(SecurityException.class, () -> service.heartbeat("run", "maker", Instant.now()));
        enabled.disable();
        assertThrows(IllegalStateException.class, () -> service.startRun("v1", "maker"));
    }
}
