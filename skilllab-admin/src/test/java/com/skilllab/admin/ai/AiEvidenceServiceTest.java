package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiEvidenceServiceTest {
    @Test
    void savesOnlySessionOwnedEvidenceAndRefusesCrossProjectRead() {
        AiSessionRepository sessions = mock(AiSessionRepository.class);
        AiEvidenceRepository evidence = mock(AiEvidenceRepository.class);
        AiWorkerClient worker = mock(AiWorkerClient.class);
        when(sessions.findById("session")).thenReturn(Optional.of(new AiSessionEntity("session", "project", "http://device.test")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "01234567-89ab-4cde-8fab-0123456789ab"); item.put("sequence", 1); item.put("method", "GET");
        item.put("path", "/api/config"); item.put("bodyUsable", false);
        when(worker.post(eq("/internal/sessions/session/evidence"), anyMap()))
                .thenReturn(Collections.singletonMap("items", Collections.singletonList(item)));
        when(evidence.save(any(AiEvidenceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiEvidenceService service = new AiEvidenceService(sessions, evidence, worker, new ObjectMapper());
        assertEquals(1, service.saveSelection("project", "session", Collections.singletonMap("observationIds", Collections.singletonList("01234567-89ab-4cde-8fab-0123456789ab"))).size());
        assertThrows(AiWorkbenchService.AiNotFoundException.class, () -> service.listEvidence("other", "session"));
        verify(evidence, never()).findAllByProjectIdAndSessionIdOrderBySequenceAsc("other", "session");
    }
}
