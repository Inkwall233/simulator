package com.skilllab.admin.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AiWorkbenchService {

    private static final long MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 20000;

    private final AiDeviceProjectRepository projects;
    private final AiPackageRepository packages;
    private final AiSessionRepository sessions;
    private final AiWorkerClient worker;
    private final AiTargetPolicy targetPolicy;
    private final Path dataDirectory;

    public AiWorkbenchService(
            AiDeviceProjectRepository projects,
            AiPackageRepository packages,
            AiSessionRepository sessions,
            AiWorkerClient worker,
            AiTargetPolicy targetPolicy,
            @Value("${skilllab.ai.data-dir}") String dataDirectory) {
        this.projects = projects;
        this.packages = packages;
        this.sessions = sessions;
        this.worker = worker;
        this.targetPolicy = targetPolicy;
        this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProjects() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AiDeviceProjectEntity project : projects.findAll()) {
            result.add(projectView(project));
        }
        result.sort((left, right) -> ((String) right.get("createdAt")).compareTo((String) left.get("createdAt")));
        return result;
    }

    @Transactional
    public Map<String, Object> createProject(Map<String, Object> request) {
        String name = requiredText(request.get("name"), "Project name is required");
        if (name.length() > 160) {
            throw new IllegalArgumentException("Project name must be 160 characters or fewer");
        }
        String manufacturer = optionalText(request.get("manufacturer"), 160);
        String model = optionalText(request.get("model"), 160);
        AiDeviceProjectEntity project = projects.save(new AiDeviceProjectEntity(
                UUID.randomUUID().toString(), name, manufacturer, model));
        return projectView(project);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProject(String projectId) {
        AiDeviceProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new AiNotFoundException("Device project not found"));
        Map<String, Object> view = projectView(project);
        List<Map<String, Object>> packageViews = new ArrayList<Map<String, Object>>();
        for (AiPackageEntity packageEntity : packages.findAllByProjectIdOrderByUploadedAtDesc(projectId)) {
            packageViews.add(packageView(packageEntity));
        }
        view.put("packages", packageViews);
        return view;
    }

    @Transactional
    public Map<String, Object> uploadPackage(String projectId, MultipartFile file) {
        projects.findById(projectId).orElseThrow(() -> new AiNotFoundException("Device project not found"));
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a non-empty ZIP package");
        }
        if (file.getSize() > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("ZIP package exceeds the 200 MB limit");
        }
        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Only ZIP packages are accepted");
        }

        Path projectDirectory = dataDirectory.resolve(projectId).normalize();
        if (!projectDirectory.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Invalid project storage path");
        }
        Path temporaryFile = null;
        try {
            Files.createDirectories(projectDirectory);
            temporaryFile = Files.createTempFile(projectDirectory, "upload-", ".zip");
            file.transferTo(temporaryFile.toFile());
            Map<String, Object> scan = inspectZip(temporaryFile);
            String id = UUID.randomUUID().toString();
            Path storedFile = projectDirectory.resolve(id + ".zip");
            Files.move(temporaryFile, storedFile, StandardCopyOption.ATOMIC_MOVE);
            temporaryFile = null;

            AiPackageEntity packageEntity = packages.save(new AiPackageEntity(
                    id, projectId, filename, sha256(storedFile), file.getSize(), storedFile.toString()));
            Map<String, Object> view = packageView(packageEntity);
            view.put("scan", scan);
            return view;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store or inspect ZIP package", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    temporaryFile.toFile().deleteOnExit();
                }
            }
        }
    }

    @Transactional
    public Map<String, Object> createSession(String projectId, Map<String, Object> request) {
        projects.findById(projectId).orElseThrow(() -> new AiNotFoundException("Device project not found"));
        String targetUrl = requiredText(request.get("targetUrl"), "Target URL is required");
        targetPolicy.validateTarget(targetUrl);
        String sessionId = UUID.randomUUID().toString();
        worker.createSession(sessionId, targetUrl);
        AiSessionEntity session = sessions.save(new AiSessionEntity(
                sessionId, projectId, safeTargetDisplay(targetUrl)));
        return sessionView(session);
    }

    @Transactional(readOnly = true)
    public AiSessionEntity requireActiveSession(String sessionId) {
        AiSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new AiNotFoundException("Exploration session not found"));
        if (!"RUNNING".equals(session.getStatus())) {
            throw new AiNotFoundException("Exploration session is closed");
        }
        return session;
    }

    public Map<String, Object> workerJson(String sessionId, String path) {
        requireActiveSession(sessionId);
        return worker.getJson("/internal/sessions/" + sessionId + path);
    }

    public Map<String, Object> workerPost(String sessionId, String path, Map<String, ?> body) {
        requireActiveSession(sessionId);
        return worker.post("/internal/sessions/" + sessionId + path, body);
    }

    public byte[] screenshot(String sessionId) {
        requireActiveSession(sessionId);
        return worker.getBytes("/internal/sessions/" + sessionId + "/screenshot");
    }

    @Transactional
    public Map<String, Object> closeSession(String sessionId) {
        AiSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new AiNotFoundException("Exploration session not found"));
        if ("RUNNING".equals(session.getStatus())) {
            worker.post("/internal/sessions/" + sessionId + "/close", new LinkedHashMap<String, Object>());
            session.close();
            sessions.save(session);
        }
        return sessionView(session);
    }

    private Map<String, Object> inspectZip(Path zipFile) throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        boolean hasIndex = false;
        List<String> rootPathReferences = new ArrayList<String>();
        byte[] buffer = new byte[8192];
        try (InputStream fileInput = Files.newInputStream(zipFile);
             ZipInputStream zipInput = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException("ZIP contains too many entries");
                }
                String entryName = entry.getName().replace('\\', '/');
                java.nio.file.Path normalized = Paths.get(entryName).normalize();
                if (entryName.startsWith("/") || entryName.matches("^[A-Za-z]:.*")
                        || normalized.isAbsolute() || normalized.startsWith("..")) {
                    throw new IllegalArgumentException("ZIP contains an unsafe path");
                }
                if (!entry.isDirectory() && entryName.toLowerCase().endsWith("index.html")) {
                    hasIndex = true;
                    ByteArrayOutputStream html = new ByteArrayOutputStream();
                    int htmlBytes = 0;
                    int count;
                    while ((count = zipInput.read(buffer)) != -1) {
                        expandedBytes += count;
                        htmlBytes += count;
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            throw new IllegalArgumentException("Expanded ZIP exceeds the 512 MB limit");
                        }
                        if (htmlBytes <= 2 * 1024 * 1024) {
                            html.write(buffer, 0, count);
                        }
                    }
                    String markup = new String(html.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    if (markup.matches("(?is).*?(src|href)\\s*=\\s*['\"]/(?!/)[^'\"]+.*")) {
                        rootPathReferences.add(entryName);
                    }
                } else if (!entry.isDirectory()) {
                    int count;
                    while ((count = zipInput.read(buffer)) != -1) {
                        expandedBytes += count;
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            throw new IllegalArgumentException("Expanded ZIP exceeds the 512 MB limit");
                        }
                    }
                }
                zipInput.closeEntry();
            }
        }
        if (entryCount == 0 || !hasIndex) {
            throw new IllegalArgumentException("ZIP must contain an index.html entry");
        }
        Map<String, Object> scan = new LinkedHashMap<String, Object>();
        scan.put("entryCount", entryCount);
        scan.put("expandedBytes", expandedBytes);
        scan.put("hasIndexHtml", hasIndex);
        scan.put("rootPathReferenceHtml", rootPathReferences);
        scan.put("pathCompatibility", rootPathReferences.isEmpty() ? "RELATIVE_OR_UNKNOWN" : "ROOT_PATHS_DETECTED");
        return scan;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, Object> projectView(AiDeviceProjectEntity project) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", project.getId());
        view.put("name", project.getName());
        view.put("manufacturer", project.getManufacturer());
        view.put("model", project.getModel());
        view.put("createdAt", project.getCreatedAt().toString());
        return view;
    }

    private Map<String, Object> packageView(AiPackageEntity packageEntity) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", packageEntity.getId());
        view.put("filename", packageEntity.getFilename());
        view.put("sha256", packageEntity.getSha256());
        view.put("sizeBytes", packageEntity.getSizeBytes());
        view.put("uploadedAt", packageEntity.getUploadedAt().toString());
        return view;
    }

    private Map<String, Object> sessionView(AiSessionEntity session) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", session.getId());
        view.put("projectId", session.getProjectId());
        view.put("targetUrl", session.getTargetUrl());
        view.put("status", session.getStatus());
        view.put("createdAt", session.getCreatedAt().toString());
        return view;
    }

    private String requiredText(Object value, String message) {
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.toString().trim();
    }

    private String optionalText(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("Field is too long");
        }
        return text.isEmpty() ? null : text;
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Package filename is required");
        }
        String normalized = filename.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (baseName.isEmpty() || baseName.length() > 255 || baseName.contains("..")) {
            throw new IllegalArgumentException("Package filename is invalid");
        }
        return baseName;
    }

    private String safeTargetDisplay(String targetUrl) {
        try {
            java.net.URL url = new java.net.URL(targetUrl);
            String port = url.getPort() < 0 ? "" : ":" + url.getPort();
            String path = url.getPath() == null ? "" : url.getPath();
            return url.getProtocol() + "://" + url.getHost() + port + path;
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalArgumentException("Target URL is invalid", exception);
        }
    }

    public static class AiNotFoundException extends RuntimeException {
        public AiNotFoundException(String message) { super(message); }
    }
}
