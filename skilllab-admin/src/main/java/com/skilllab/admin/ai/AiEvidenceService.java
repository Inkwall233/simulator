package com.skilllab.admin.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiEvidenceService {
    private final AiSessionRepository sessions;
    private final AiEvidenceRepository evidence;
    private final AiWorkerClient worker;
    private final ObjectMapper mapper;

    public AiEvidenceService(AiSessionRepository sessions, AiEvidenceRepository evidence,
            AiWorkerClient worker, ObjectMapper mapper) {
        this.sessions = sessions;
        this.evidence = evidence;
        this.worker = worker;
        this.mapper = mapper;
    }

    @Transactional
    public List<Map<String, Object>> saveSelection(String projectId, String sessionId, Map<String, Object> selection) {
        requireSession(projectId, sessionId);
        Map<String, Object> result = worker.post("/internal/sessions/" + sessionId + "/evidence", selection);
        if (!(result.get("items") instanceof List)) throw new IllegalStateException("Worker returned invalid evidence");
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) result.get("items")) {
            if (!(raw instanceof Map)) throw new IllegalStateException("Worker returned invalid evidence item");
            @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) raw;
            String observationId = String.valueOf(item.get("id"));
            if (!observationId.matches("(?i)^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")) {
                throw new IllegalArgumentException("Invalid observation id");
            }
            Object sequence = item.get("sequence");
            if (!(sequence instanceof Number) || ((Number) sequence).intValue() < 1) throw new IllegalArgumentException("Invalid evidence order");
            try {
                evidence.save(new AiEvidenceEntity(observationId, projectId, sessionId,
                        ((Number) sequence).intValue(), mapper.writeValueAsString(item)));
                saved.add(item);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Could not serialize evidence", exception);
            }
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEvidence(String projectId, String sessionId) {
        requireSession(projectId, sessionId);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AiEvidenceEntity item : evidence.findAllByProjectIdAndSessionIdOrderBySequenceAsc(projectId, sessionId)) {
            result.add(view(item));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getEvidence(String projectId, String sessionId, String id) {
        requireSession(projectId, sessionId);
        return view(evidence.findByIdAndProjectIdAndSessionId(id, projectId, sessionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Evidence not found")));
    }

    private void requireSession(String projectId, String sessionId) {
        AiSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Session not found"));
        if (!projectId.equals(session.getProjectId())) throw new AiWorkbenchService.AiNotFoundException("Session not found");
    }

    private Map<String, Object> view(AiEvidenceEntity entity) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> value = mapper.readValue(entity.getPayloadJson(), Map.class);
            return new LinkedHashMap<String, Object>(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored evidence is invalid", exception);
        }
    }
}
