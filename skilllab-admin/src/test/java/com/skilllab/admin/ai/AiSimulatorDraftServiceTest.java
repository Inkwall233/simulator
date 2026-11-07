package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiSimulatorDraftServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiSimulatorDraftRepository repository = mock(AiSimulatorDraftRepository.class);
    private final AiSimulatorDraftService service = new AiSimulatorDraftService(
            mock(AiDeviceProjectRepository.class), mock(AiPackageRepository.class),
            mock(AiApiDraftRepository.class), repository, mock(AiSessionRepository.class), mapper);

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload() throws Exception {
        return mapper.readValue(Files.readAllBytes(Paths.get("../schemas/examples/simulator-draft-v1.example.json")), Map.class);
    }

    private void stored(Map<String, Object> payload) throws Exception {
        when(repository.findByIdAndProjectId("draft", "demo-camera-project")).thenReturn(Optional.of(
                new AiSimulatorDraftEntity("draft", "demo-camera-project", "demo-package-v1", "demo-api-draft-v1", 1,
                        "DRAFT", mapper.writeValueAsString(payload))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsRequestMappingAndRejectsUnsafePathsOrUnknownRules() throws Exception {
        Map<String, Object> payload = payload();
        Map<String, Object> state = (Map<String, Object>) payload.get("stateModel");
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("onRuleId", "mock-device-read-v1");
        transition.put("operation", "SET_FROM_REQUEST");
        transition.put("path", "config.enabled");
        transition.put("requestPath", "enabled");
        state.put("transitions", Collections.singletonList(transition));
        stored(payload);
        assertNotNull(service.getDraft("demo-camera-project", "draft"));
        transition.put("path", "__proto__.enabled");
        stored(payload);
        assertThrows(IllegalArgumentException.class, () -> service.getDraft("demo-camera-project", "draft"));
        transition.put("path", "config.enabled");
        transition.put("onRuleId", "unknown");
        stored(payload);
        assertThrows(IllegalArgumentException.class, () -> service.getDraft("demo-camera-project", "draft"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsV1AsNewV2RevisionWithoutChangingSource() throws Exception {
        Map<String, Object> original = payload();
        stored(original);
        AiDeviceProjectRepository projects = mock(AiDeviceProjectRepository.class);
        AiPackageRepository packages = mock(AiPackageRepository.class);
        AiApiDraftRepository apiDrafts = mock(AiApiDraftRepository.class);
        AiSimulatorDraftService drafts = new AiSimulatorDraftService(projects, packages,
                apiDrafts, repository, mock(AiSessionRepository.class), mapper);
        when(projects.findByIdForUpdate("demo-camera-project")).thenReturn(Optional.of(
                new AiDeviceProjectEntity("demo-camera-project", "Demo", null, null)));
        when(packages.findById("demo-package-v1")).thenReturn(Optional.of(
                new AiPackageEntity("demo-package-v1", "demo-camera-project", "device.zip",
                        (String) ((Map<?, ?>) original.get("packageVersion")).get("sha256"), 12000, "test.zip")));
        when(apiDrafts.findById("demo-api-draft-v1")).thenReturn(Optional.of(
                new AiApiDraftEntity("demo-api-draft-v1", "demo-camera-project", "demo-session-v1", "{}")));
        when(repository.findFirstByProjectIdOrderByVersionNumberDesc("demo-camera-project"))
                .thenReturn(Optional.of(new AiSimulatorDraftEntity("draft", "demo-camera-project",
                        "demo-package-v1", "demo-api-draft-v1", 1, "DRAFT", mapper.writeValueAsString(original))));
        when(repository.save(any(AiSimulatorDraftEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> imported = drafts.importV1("demo-camera-project", "draft");
        assertEquals(2, imported.get("versionNumber"));
        Map<String, Object> behavior = (Map<String, Object>) imported.get("payload");
        assertEquals("skilllab.simulator-behavior.v2", behavior.get("schemaVersion"));
        assertEquals("skilllab.simulator-draft.v1", ((Map<?, ?>) drafts.getDraft("demo-camera-project", "draft").get("payload")).get("schemaVersion"));
        verify(repository).save(any(AiSimulatorDraftEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnsafeV2PayloadBeforePersisting() throws Exception {
        Map<String, Object> behavior = mapper.readValue(Files.readAllBytes(Paths.get(
                "../schemas/examples/simulator-behavior-v2.example.json")), Map.class);
        AiDeviceProjectRepository projects = mock(AiDeviceProjectRepository.class);
        AiPackageRepository packages = mock(AiPackageRepository.class);
        AiApiDraftRepository apiDrafts = mock(AiApiDraftRepository.class);
        AiSimulatorDraftService drafts = new AiSimulatorDraftService(projects, packages, apiDrafts,
                repository, mock(AiSessionRepository.class), mapper);
        when(projects.findByIdForUpdate("demo-camera-project")).thenReturn(Optional.of(
                new AiDeviceProjectEntity("demo-camera-project", "Demo", null, null)));
        when(packages.findById("demo-package-v1")).thenReturn(Optional.of(
                new AiPackageEntity("demo-package-v1", "demo-camera-project", "device.zip",
                        (String) ((Map<?, ?>) behavior.get("packageVersion")).get("sha256"), 12000, "test.zip")));
        when(apiDrafts.findById("demo-api-draft-v1")).thenReturn(Optional.of(
                new AiApiDraftEntity("demo-api-draft-v1", "demo-camera-project", "session", "{}")));
        when(repository.save(any(AiSimulatorDraftEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("behavior", behavior);
        request.put("sourceApiDraftId", "demo-api-draft-v1");
        assertNotNull(drafts.createDraftV2("demo-camera-project", request));
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(1)).put("when",
                Collections.singletonMap("op", "EXEC"));
        assertThrows(IllegalArgumentException.class, () -> drafts.createDraftV2("demo-camera-project", request));
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(1)).put("when",
                Collections.singletonMap("op", "exists"));
        assertThrows(IllegalArgumentException.class, () -> drafts.createDraftV2("demo-camera-project", request));
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(1)).remove("when");
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(0)).put("script", "alert(1)");
        assertThrows(IllegalArgumentException.class, () -> drafts.createDraftV2("demo-camera-project", request));
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(0)).remove("script");
        ((Map<String, Object>) ((List<?>) behavior.get("rules")).get(0)).get("response");
        ((Map<String, Object>) ((Map<?, ?>) ((List<?>) behavior.get("rules")).get(0)).get("response"))
                .put("status", 204);
        assertThrows(IllegalArgumentException.class, () -> drafts.createDraftV2("demo-camera-project", request));
        behavior.remove("packageVersion");
        assertThrows(IllegalArgumentException.class, () -> drafts.createDraftV2("demo-camera-project", request));
        verify(repository, times(1)).save(any(AiSimulatorDraftEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsStoredV2WithMissingPackageReferenceAsInvalidInput() throws Exception {
        Map<String, Object> behavior = mapper.readValue(Files.readAllBytes(Paths.get(
                "../schemas/examples/simulator-behavior-v2.example.json")), Map.class);
        behavior.remove("packageVersion");
        stored(behavior);
        assertThrows(IllegalArgumentException.class,
                () -> service.getDraft("demo-camera-project", "draft"));
    }
}
