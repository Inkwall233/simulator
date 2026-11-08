package com.skilllab.admin.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds short-lived, derived package copies for the isolated preview worker.
 * Raw uploaded archives are never modified or served directly.
 */
@Service
public class AiPreviewService {

    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
    private static final String PREVIEW_ID_PATTERN = "[a-f0-9]{32}";

    private final AiSimulatorDraftService simulatorDrafts;
    private final AiPackageRepository packages;
    private final AiWorkerClient worker;
    private final Path packageDirectory;
    private final Path previewDirectory;
    private final Path runDirectory;
    private final long previewTtlSeconds;
    private final ConcurrentMap<String, ActivePreview> activePreviews = new ConcurrentHashMap<String, ActivePreview>();

    public AiPreviewService(
            AiSimulatorDraftService simulatorDrafts,
            AiPackageRepository packages,
            AiWorkerClient worker,
            @Value("${skilllab.ai.data-dir:./data/ai-packages}") String dataDirectory,
            @Value("${skilllab.ai.preview-ttl-seconds:900}") long configuredPreviewTtlSeconds) {
        this.simulatorDrafts = simulatorDrafts;
        this.packages = packages;
        this.worker = worker;
        this.packageDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize();
        Path parent = this.packageDirectory.getParent();
        this.previewDirectory = (parent == null
                ? this.packageDirectory.resolveSibling("ai-previews")
                : parent.resolve("ai-previews")).toAbsolutePath().normalize();
        this.runDirectory = (parent == null ? this.packageDirectory.resolveSibling("ai-runs")
                : parent.resolve("ai-runs")).toAbsolutePath().normalize();
        this.previewTtlSeconds = Math.max(60L, Math.min(configuredPreviewTtlSeconds, 3600L));
    }

    public Path createRunPackage(String runId, String packageId) {
        if (runId == null || !runId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid run id");
        AiPackageEntity packageVersion = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
        Path archive = Paths.get(packageVersion.getStoredPath()).toAbsolutePath().normalize();
        if (!archive.startsWith(packageDirectory) || !Files.isRegularFile(archive)) throw new IllegalStateException("Stored package is unavailable");
        verifyArchive(packageVersion);
        Path destination = runDirectory.resolve(runId).normalize();
        if (!destination.getParent().equals(runDirectory)) throw new IllegalArgumentException("Invalid run package path");
        try { extractArchive(archive, destination, runDirectory); return destination; }
        catch (IOException | RuntimeException exception) { deleteRunPackage(runId); throw new IllegalStateException("Could not prepare run package", exception); }
    }

    public void verifyArchive(AiPackageEntity packageVersion) {
        Path archive = Paths.get(packageVersion.getStoredPath()).toAbsolutePath().normalize();
        if (!archive.startsWith(packageDirectory) || !Files.isRegularFile(archive))
            throw new IllegalStateException("Stored package is unavailable");
        try (InputStream input = Files.newInputStream(archive)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hash = new StringBuilder();
            for (byte item : digest.digest()) hash.append(String.format("%02x", item & 0xff));
            if (!packageVersion.getSha256().equalsIgnoreCase(hash.toString()))
                throw new IllegalStateException("Stored package digest has changed");
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not verify stored package", exception);
        }
    }

    public void deleteRunPackage(String runId) {
        if (runId == null || !runId.matches("[a-f0-9]{32}")) return;
        Path directory = runDirectory.resolve(runId).normalize();
        if (!runDirectory.equals(directory.getParent())) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> createPreview(String projectId, String draftId) {
        Map<String, Object> draft = simulatorDrafts.getDraft(projectId, draftId);
        Object rawPayload = draft.get("payload");
        if (!(rawPayload instanceof Map)) {
            throw new IllegalStateException("Simulator draft payload is invalid");
        }
        String packageId = requiredText(draft.get("packageId"), "Simulator draft package reference is missing");
        AiPackageEntity packageVersion = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
        if (!projectId.equals(packageVersion.getProjectId())) {
            throw new IllegalArgumentException("Package version does not belong to this project");
        }

        Path sourceArchive = Paths.get(packageVersion.getStoredPath()).toAbsolutePath().normalize();
        if (!sourceArchive.startsWith(packageDirectory) || !Files.isRegularFile(sourceArchive)) {
            throw new IllegalStateException("Stored package archive is unavailable");
        }
        verifyArchive(packageVersion);

        String previewId = UUID.randomUUID().toString().replace("-", "");
        Path destination = previewDirectory.resolve(previewId).normalize();
        Instant expiresAt = Instant.now().plusSeconds(previewTtlSeconds);
        try {
            extractArchive(sourceArchive, destination, previewDirectory);
            Map<String, Object> workerRequest = new LinkedHashMap<String, Object>();
            workerRequest.put("previewId", previewId);
            workerRequest.put("sourceDirectory", destination.toString());
            workerRequest.put("draft", rawPayload);
            workerRequest.put("expiresAt", expiresAt.toString());
            Map<String, Object> workerResult = worker.post("/internal/previews", workerRequest);
            String previewUrl = requiredText(workerResult.get("previewUrl"), "Preview worker did not return a URL");
            activePreviews.put(previewId, new ActivePreview(destination, expiresAt));

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("previewId", previewId);
            result.put("projectId", projectId);
            result.put("draftId", draftId);
            result.put("versionNumber", draft.get("versionNumber"));
            result.put("previewUrl", previewUrl);
            result.put("expiresAt", expiresAt.toString());
            result.put("runtimeVersion", "preview-runtime-v1");
            return result;
        } catch (IOException exception) {
            deletePreviewDirectory(destination);
            throw new IllegalStateException("Could not prepare package preview", exception);
        } catch (RuntimeException exception) {
            deletePreviewDirectory(destination);
            throw exception;
        }
    }

    @Scheduled(fixedDelay = 60000L)
    public void clearExpiredPreviews() {
        Instant now = Instant.now();
        for (Map.Entry<String, ActivePreview> entry : activePreviews.entrySet()) {
            if (entry.getValue().expiresAt.isAfter(now)) continue;
            String previewId = entry.getKey();
            if (!activePreviews.remove(previewId, entry.getValue())) continue;
            try {
                Map<String, Object> request = new LinkedHashMap<String, Object>();
                request.put("previewId", previewId);
                worker.post("/internal/previews/" + previewId + "/close", request);
            } catch (RuntimeException ignored) {
                // The worker independently expires its in-memory preview registry.
            }
            deletePreviewDirectory(entry.getValue().directory);
        }
    }

    @PreDestroy
    public void stopPreviews() {
        for (Map.Entry<String, ActivePreview> entry : activePreviews.entrySet()) {
            try {
                Map<String, Object> request = new LinkedHashMap<String, Object>();
                request.put("previewId", entry.getKey());
                worker.post("/internal/previews/" + entry.getKey() + "/close", request);
            } catch (RuntimeException ignored) {
                // Shutdown should continue even when the worker is already stopped.
            }
            deletePreviewDirectory(entry.getValue().directory);
        }
        activePreviews.clear();
    }

    private void extractArchive(Path sourceArchive, Path destination, Path allowedRoot) throws IOException {
        if (!destination.getParent().equals(allowedRoot) || !destination.getFileName().toString().matches(PREVIEW_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid preview storage path");
        }
        Files.createDirectories(allowedRoot);
        Files.createDirectory(destination);
        int entryCount = 0;
        long expandedBytes = 0L;
        boolean hasRootIndex = false;
        byte[] buffer = new byte[8192];
        try (InputStream source = Files.newInputStream(sourceArchive);
                ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Preview archive has too many entries");
                }
                String entryName = entry.getName().replace('\\', '/');
                Path target = safeDestination(destination, entryName);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            expandedBytes += count;
                            if (expandedBytes > MAX_EXPANDED_BYTES) {
                                throw new IllegalArgumentException("Expanded preview package exceeds the 512 MB limit");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                    if ("index.html".equals(entryName)) hasRootIndex = true;
                }
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException exception) {
            deletePreviewDirectory(destination);
            throw exception;
        }
        if (entryCount == 0 || !hasRootIndex) {
            deletePreviewDirectory(destination);
            throw new IllegalArgumentException("Preview package must contain a root index.html");
        }
    }

    private Path safeDestination(Path destination, String entryName) {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0
                || entryName.startsWith("/") || entryName.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Preview archive contains an invalid entry path");
        }
        Path relative = Paths.get(entryName).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith(Paths.get(".."))) {
            throw new IllegalArgumentException("Preview archive contains an unsafe entry path");
        }
        Path target = destination.resolve(relative).normalize();
        if (!target.startsWith(destination)) {
            throw new IllegalArgumentException("Preview archive entry escapes its destination");
        }
        return target;
    }

    private void deletePreviewDirectory(Path directory) {
        if (directory == null || !previewDirectory.equals(directory.getParent())
                || !directory.getFileName().toString().matches(PREVIEW_ID_PATTERN)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup; the directory stays private under ai-previews.
                }
            });
        } catch (IOException ignored) {
            // The next cleanup pass can retry when files are no longer in use.
        }
    }

    private String requiredText(Object value, String message) {
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalStateException(message);
        }
        return value.toString().trim();
    }

    private static class ActivePreview {
        private final Path directory;
        private final Instant expiresAt;

        ActivePreview(Path directory, Instant expiresAt) {
            this.directory = directory;
            this.expiresAt = expiresAt;
        }
    }
}
