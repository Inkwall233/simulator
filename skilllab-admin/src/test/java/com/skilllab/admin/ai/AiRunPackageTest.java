package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiRunPackageTest {
    @TempDir Path root;

    @Test
    void extractsAnIndependentRunPackageAndLeavesTheArchiveUntouched() throws Exception {
        Path packageRoot = Files.createDirectory(root.resolve("packages"));
        Path archive = packageRoot.resolve("device.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("index.html"));
            output.write("<h1>Device</h1>".getBytes("UTF-8"));
            output.closeEntry();
        }
        AiPackageRepository packages = mock(AiPackageRepository.class);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive));
        StringBuilder hash = new StringBuilder();
        for (byte item : digest) hash.append(String.format("%02x", item & 0xff));
        when(packages.findById("package")).thenReturn(Optional.of(new AiPackageEntity(
                "package", "project", "device.zip", hash.toString(), Files.size(archive), archive.toString())));
        AiPreviewService previews = new AiPreviewService(mock(AiSimulatorDraftService.class), packages,
                mock(AiWorkerClient.class), packageRoot.toString(), 900);
        String runId = "aabbccddaabbccddaabbccddaabbccdd";
        Path extracted = previews.createRunPackage(runId, "package");
        assertEquals("<h1>Device</h1>", new String(Files.readAllBytes(extracted.resolve("index.html")), "UTF-8"));
        previews.deleteRunPackage(runId);
        assertTrue(Files.isRegularFile(archive));
        assertFalse(Files.exists(extracted));
    }

    @Test
    void rejectsArchiveModifiedAfterUpload() throws Exception {
        Path packageRoot = Files.createDirectory(root.resolve("packages"));
        Path archive = packageRoot.resolve("device.zip");
        Files.write(archive, "changed".getBytes("UTF-8"));
        AiPackageRepository packages = mock(AiPackageRepository.class);
        when(packages.findById("package")).thenReturn(Optional.of(new AiPackageEntity(
                "package", "project", "device.zip", "0", Files.size(archive), archive.toString())));
        AiPreviewService previews = new AiPreviewService(mock(AiSimulatorDraftService.class), packages,
                mock(AiWorkerClient.class), packageRoot.toString(), 900);
        assertThrows(IllegalStateException.class, () -> previews.createRunPackage(
                "aabbccddaabbccddaabbccddaabbccdd", "package"));
    }
}
