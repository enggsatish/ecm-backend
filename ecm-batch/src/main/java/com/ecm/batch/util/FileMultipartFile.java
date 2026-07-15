package com.ecm.batch.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/**
 * Simple MultipartFile implementation that wraps a java.io.File.
 * Used by WatchFolderService to convert filesystem files into MultipartFile
 * for the BatchJobService.createBatch() API.
 */
public class FileMultipartFile implements MultipartFile {

    private final File file;
    private final String contentType;

    public FileMultipartFile(File file) {
        this.file = file;
        this.contentType = detectContentType(file);
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public String getOriginalFilename() {
        return file.getName();
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return file.length() == 0;
    }

    @Override
    public long getSize() {
        return file.length();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.copy(file.toPath(), dest.toPath());
    }

    private static String detectContentType(File file) {
        try {
            String type = Files.probeContentType(file.toPath());
            return type != null ? type : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
