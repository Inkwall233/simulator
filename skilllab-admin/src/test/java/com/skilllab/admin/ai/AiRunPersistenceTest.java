package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sim-runs;DB_CLOSE_DELAY=-1",
    "skilllab.ai.creator-username=creator",
    "skilllab.ai.creator-password-hash=$2a$10$123456789012345678901u12345678901234567890123456789012",
    "skilllab.ai.worker-url=http://localhost:3001",
    "skilllab.ai.worker-secret=test-secret",
    "skilllab.ai.data-dir=./target/test-data/packages"
})
class AiRunPersistenceTest {
    @Autowired AiSimulatorVersionRepository versions;
    @Autowired AiRunRepository runs;

    @Test
    void persistsCandidateAndOwnerScopedLease() {
        AiSimulatorVersionEntity version = versions.save(new AiSimulatorVersionEntity(
                "v-persistence", "d-persistence", "p-persistence", "creator", "CANDIDATE", hash('a'), hash('b')));
        assertEquals("CANDIDATE", versions.findById(version.getId()).get().getStatus());
        AiRunEntity run = runs.save(new AiRunEntity("r-persistence", version.getId(), "p-persistence", "creator",
                Instant.now(), Instant.now().plusSeconds(10), Instant.now().plusSeconds(30)));
        assertEquals("creator", runs.findById(run.getId()).get().getOwnerId());
        runs.delete(run);
        versions.delete(version);
    }

    private String hash(char value) { char[] chars = new char[64]; java.util.Arrays.fill(chars, value); return new String(chars); }
}
