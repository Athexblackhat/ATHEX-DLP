package com.athex.dlp.collectors;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ATHEX DLP Enterprise - AppListCollector
 * 
 * Comprehensive installed applications enumeration module.
 * Collects detailed information about all installed packages:
 * - System apps vs user apps
 * - App permissions
 * - Installation dates
 * - App sizes (APK + data)
 * - Version info
 * - Target SDK
 * - Signatures
 * - Activities, Services, Receivers
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class AppListCollector {
    
    private static final String TAG = "ATHEX_AppListCollector";
    
    // App categories
    public static final String CATEGORY_SYSTEM = "system";
    public static final String CATEGORY_USER = "user";
    public static final String CATEGORY_UPDATED_SYSTEM = "updated_system";
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final PackageManager packageManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Options
    private boolean includeSystemApps = true;
    private boolean includePermissions = true;
    private boolean includeActivities = false;
    private boolean includeServices = false;
    private boolean includeReceivers = false;
    private boolean includeSignatures = false;
    private boolean includeIcons = false;
    private boolean includeApkPath = true;
    private String nameFilter = null;
    private String packageFilter = null;
    
    // Statistics
    private int totalApps = 0;
    private int systemApps = 0;
    private int userApps = 0;
    private int updatedSystemApps = 0;
    private long totalApkSize = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onProgressUpdate(int collected, String currentApp);
        void onCollectionComplete(JSONArray apps, AppStats stats);
        void onCollectionError(String error);
    }
    
    public static class AppStats {
        public int totalApps;
        public int systemApps;
        public int userApps;
        public int updatedSystemApps;
        public long totalApkSizeBytes;
        public long durationMs;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Total: %d | System: %d | User: %d | Updated: %d | Size: %s | Duration: %dms",
                totalApps, systemApps, userApps, updatedSystemApps,
                formatSize(totalApkSizeBytes), durationMs
            );
        }
        
        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public AppListCollector(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public AppListCollector setIncludeSystemApps(boolean include) {
        this.includeSystemApps = include;
        return this;
    }
    
    public AppListCollector setIncludePermissions(boolean include) {
        this.includePermissions = include;
        return this;
    }
    
    public AppListCollector setIncludeActivities(boolean include) {
        this.includeActivities = include;
        return this;
    }
    
    public AppListCollector setIncludeServices(boolean include) {
        this.includeServices = include;
        return this;
    }
    
    public AppListCollector setIncludeReceivers(boolean include) {
        this.includeReceivers = include;
        return this;
    }
    
    public AppListCollector setIncludeSignatures(boolean include) {
        this.includeSignatures = include;
        return this;
    }
    
    public AppListCollector setIncludeIcons(boolean include) {
        this.includeIcons = include;
        return this;
    }
    
    public AppListCollector setNameFilter(String filter) {
        this.nameFilter = filter != null ? filter.toLowerCase() : null;
        return this;
    }
    
    public AppListCollector setPackageFilter(String filter) {
        this.packageFilter = filter != null ? filter.toLowerCase() : null;
        return this;
    }
    
    public AppListCollector setCallback(CollectionCallback callback) {
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
                
                JSONArray apps = collectAllApps();
                
                AppStats stats = buildStats();
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Collection complete: " + stats.toString());
                notifyCollectionComplete(apps, stats);
                
            } catch (Exception e) {
                Log.e(TAG, "Collection error: " + e.getMessage(), e);
                notifyError("Collection failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Collect all installed applications
     */
    private JSONArray collectAllApps() {
        JSONArray apps = new JSONArray();
        
        try {
            List<ApplicationInfo> appList = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA
            );
            
            int total = appList.size();
            Log.i(TAG, "Found " + total + " installed applications");
            
            int processed = 0;
            
            for (ApplicationInfo appInfo : appList) {
                try {
                    // Filter system apps if disabled
                    if (!includeSystemApps && isSystemApp(appInfo)) {
                        continue;
                    }
                    
                    // Apply name filter
                    String appName = packageManager.getApplicationLabel(appInfo).toString();
                    if (nameFilter != null && !appName.toLowerCase().contains(nameFilter)) {
                        continue;
                    }
                    
                    // Apply package filter
                    if (packageFilter != null && 
                        !appInfo.packageName.toLowerCase().contains(packageFilter)) {
                        continue;
                    }
                    
                    JSONObject appData = extractAppData(appInfo, appName);
                    
                    if (appData != null) {
                        apps.put(appData);
                        totalApps++;
                        updateStats(appInfo);
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing app: " + appInfo.packageName + 
                        " - " + e.getMessage());
                }
                
                processed++;
                
                if (processed % 20 == 0) {
                    notifyProgress(processed, 
                        packageManager.getApplicationLabel(appInfo).toString());
                }
            }
            
            notifyProgress(processed, "Complete");
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting apps: " + e.getMessage(), e);
            throw e;
        }
        
        return apps;
    }
    
    /**
     * Extract comprehensive app data
     */
    private JSONObject extractAppData(ApplicationInfo appInfo, String appName) {
        try {
            JSONObject app = new JSONObject();
            
            // Basic info
            app.put("package_name", appInfo.packageName);
            app.put("app_name", appName);
            app.put("process_name", appInfo.processName != null ? 
                appInfo.processName : appInfo.packageName);
            
            // App type
            String category;
            if (isUpdatedSystemApp(appInfo)) {
                category = CATEGORY_UPDATED_SYSTEM;
                updatedSystemApps++;
            } else if (isSystemApp(appInfo)) {
                category = CATEGORY_SYSTEM;
                systemApps++;
            } else {
                category = CATEGORY_USER;
                userApps++;
            }
            app.put("category", category);
            app.put("is_system", isSystemApp(appInfo));
            app.put("is_updated_system", isUpdatedSystemApp(appInfo));
            
            // UID and data directory
            app.put("uid", appInfo.uid);
            if (appInfo.dataDir != null) {
                app.put("data_dir", appInfo.dataDir);
            }
            
            // Source directory
            if (appInfo.sourceDir != null) {
                app.put("source_dir", appInfo.sourceDir);
            }
            
            // Public source directory
            if (appInfo.publicSourceDir != null) {
                app.put("public_source_dir", appInfo.publicSourceDir);
            }
            
            // APK path and size
            if (includeApkPath && appInfo.sourceDir != null) {
                File apkFile = new File(appInfo.sourceDir);
                if (apkFile.exists()) {
                    long apkSize = apkFile.length();
                    app.put("apk_path", appInfo.sourceDir);
                    app.put("apk_size", apkSize);
                    app.put("apk_size_formatted", formatSize(apkSize));
                    totalApkSize += apkSize;
                    
                    app.put("apk_last_modified", apkFile.lastModified());
                    app.put("apk_last_modified_formatted", 
                        formatTimestamp(apkFile.lastModified()));
                }
            }
            
            // Data size (approximate)
            if (appInfo.dataDir != null) {
                File dataDir = new File(appInfo.dataDir);
                if (dataDir.exists()) {
                    long dataSize = getDirectorySize(dataDir);
                    app.put("data_size", dataSize);
                    app.put("data_size_formatted", formatSize(dataSize));
                }
            }
            
            // Flags
            JSONObject flags = new JSONObject();
            flags.put("FLAG_DEBUGGABLE", (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            flags.put("FLAG_ALLOW_BACKUP", (appInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0);
            flags.put("FLAG_ALLOW_CLEAR_USER_DATA", 
                (appInfo.flags & ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA) != 0);
            flags.put("FLAG_HAS_CODE", (appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0);
            flags.put("FLAG_PERSISTENT", (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
            flags.put("FLAG_STOPPED", (appInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0);
            flags.put("FLAG_SYSTEM", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            flags.put("FLAG_UPDATED_SYSTEM_APP", 
                (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
            flags.put("FLAG_EXTERNAL_STORAGE", 
                (appInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
            flags.put("FLAG_LARGE_HEAP", (appInfo.flags & ApplicationInfo.FLAG_LARGE_HEAP) != 0);
            app.put("flags", flags);
            
            // Target SDK
            app.put("target_sdk_version", appInfo.targetSdkVersion);
            app.put("min_sdk_version", 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? 
                appInfo.minSdkVersion : -1);
            
            // Enabled state
            app.put("enabled", appInfo.enabled);
            
            // Get detailed package info
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(
                    appInfo.packageName,
                    PackageManager.GET_PERMISSIONS |
                    PackageManager.GET_ACTIVITIES |
                    PackageManager.GET_SERVICES |
                    PackageManager.GET_RECEIVERS |
                    PackageManager.GET_SIGNATURES
                );
                
                // Version info
                app.put("version_name", packageInfo.versionName != null ? 
                    packageInfo.versionName : "");
                app.put("version_code", packageInfo.versionCode);
                
                // Install time
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    app.put("first_install_time", packageInfo.firstInstallTime);
                    app.put("first_install_formatted", 
                        formatTimestamp(packageInfo.firstInstallTime));
                    app.put("last_update_time", packageInfo.lastUpdateTime);
                    app.put("last_update_formatted", 
                        formatTimestamp(packageInfo.lastUpdateTime));
                }
                
                // Shared user ID
                if (packageInfo.sharedUserId != null) {
                    app.put("shared_user_id", packageInfo.sharedUserId);
                }
                
                // Permissions
                if (includePermissions && packageInfo.requestedPermissions != null) {
                    JSONArray permissions = extractPermissions(packageInfo);
                    app.put("permissions", permissions);
                    app.put("permission_count", permissions.length());
                }
                
                // Activities
                if (includeActivities && packageInfo.activities != null) {
                    JSONArray activities = new JSONArray();
                    for (android.content.pm.ActivityInfo activity : 
                         packageInfo.activities) {
                        activities.put(activity.name);
                    }
                    app.put("activities", activities);
                    app.put("activity_count", activities.length());
                }
                
                // Services
                if (includeServices && packageInfo.services != null) {
                    JSONArray services = new JSONArray();
                    for (android.content.pm.ServiceInfo service : 
                         packageInfo.services) {
                        services.put(service.name);
                    }
                    app.put("services", services);
                    app.put("service_count", services.length());
                }
                
                // Receivers
                if (includeReceivers && packageInfo.receivers != null) {
                    JSONArray receivers = new JSONArray();
                    for (android.content.pm.ActivityInfo receiver : 
                         packageInfo.receivers) {
                        receivers.put(receiver.name);
                    }
                    app.put("receivers", receivers);
                    app.put("receiver_count", receivers.length());
                }
                
                // Signatures
                if (includeSignatures && packageInfo.signatures != null) {
                    JSONArray signatures = new JSONArray();
                    for (android.content.pm.Signature sig : packageInfo.signatures) {
                        signatures.put(sig.toCharsString());
                    }
                    app.put("signatures", signatures);
                }
                
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Package not found: " + appInfo.packageName);
            }
            
            // Icon (Base64)
            if (includeIcons) {
                try {
                    Drawable icon = packageManager.getApplicationIcon(appInfo);
                    if (icon != null) {
                        android.graphics.Bitmap bitmap = 
                            android.graphics.Bitmap.createBitmap(
                                icon.getIntrinsicWidth(),
                                icon.getIntrinsicHeight(),
                                android.graphics.Bitmap.Config.ARGB_8888
                            );
                        android.graphics.Canvas canvas = 
                            new android.graphics.Canvas(bitmap);
                        icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                        icon.draw(canvas);
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bitmap.compress(
                            android.graphics.Bitmap.CompressFormat.PNG, 80, baos
                        );
                        String iconBase64 = Base64.encodeToString(
                            baos.toByteArray(), Base64.NO_WRAP
                        );
                        baos.close();
                        
                        app.put("icon_base64", iconBase64);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get icon: " + e.getMessage());
                }
            }
            
            return app;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting app data: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract app permissions
     */
    private JSONArray extractPermissions(PackageInfo packageInfo) {
        JSONArray permissions = new JSONArray();
        
        if (packageInfo.requestedPermissions == null) return permissions;
        
        for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
            try {
                String permName = packageInfo.requestedPermissions[i];
                int permFlags = packageInfo.requestedPermissionsFlags[i];
                
                JSONObject perm = new JSONObject();
                perm.put("name", permName);
                perm.put("granted", 
                    (permFlags & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0);
                
                // Categorize permission
                if (permName.startsWith("android.permission")) {
                    String shortName = permName.substring(
                        "android.permission.".length()
                    );
                    perm.put("short_name", shortName);
                    
                    // Determine risk level
                    if (shortName.contains("CAMERA") || 
                        shortName.contains("RECORD_AUDIO") ||
                        shortName.contains("READ_SMS") ||
                        shortName.contains("READ_CONTACTS") ||
                        shortName.contains("ACCESS_FINE_LOCATION")) {
                        perm.put("risk", "high");
                    } else if (shortName.contains("INTERNET") ||
                               shortName.contains("ACCESS_NETWORK")) {
                        perm.put("risk", "medium");
                    } else {
                        perm.put("risk", "low");
                    }
                }
                
                permissions.put(perm);
                
            } catch (Exception e) {
                Log.e(TAG, "Error extracting permission: " + e.getMessage());
            }
        }
        
        return permissions;
    }
    
    // ============================================================
    // SINGLE APP INFO
    // ============================================================
    
    /**
     * Get detailed info for a single app
     */
    public void getAppInfo(String packageName, SingleAppCallback callback) {
        executor.execute(() -> {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(
                    packageName, PackageManager.GET_META_DATA
                );
                
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                JSONObject appData = extractAppData(appInfo, appName);
                
                if (callback != null) {
                    final JSONObject finalData = appData;
                    mainHandler.post(() -> callback.onAppInfoReady(finalData));
                }
                
            } catch (PackageManager.NameNotFoundException e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onAppInfoError(
                        "Package not found: " + packageName
                    ));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onAppInfoError(e.getMessage()));
                }
            }
        });
    }
    
    public interface SingleAppCallback {
        void onAppInfoReady(JSONObject appData);
        void onAppInfoError(String error);
    }
    
    // ============================================================
    // APP OPERATIONS
    // ============================================================
    
    /**
     * Launch an app
     */
    public boolean launchApp(String packageName) {
        try {
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                Log.d(TAG, "Launched: " + packageName);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching app: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Get app install intent (for APK extraction)
     */
    public Intent getInstallIntent(String packageName) {
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.setData(android.net.Uri.parse("package:" + packageName));
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        return intent;
    }
    
    // ============================================================
    // SEARCH & FILTER
    // ============================================================
    
    /**
     * Search apps by name
     */
    public void searchApps(String query, CollectionCallback callback) {
        this.nameFilter = query;
        this.callback = callback;
        collect();
    }
    
    /**
     * Get apps by category
     */
    public void getAppsByCategory(String category, CollectionCallback callback) {
        this.callback = callback;
        
        executor.execute(() -> {
            try {
                JSONArray apps = collectAllApps();
                JSONArray filtered = new JSONArray();
                
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    if (category.equals(app.optString("category", ""))) {
                        filtered.put(app);
                    }
                }
                
                if (callback != null) {
                    final JSONArray finalFiltered = filtered;
                    mainHandler.post(() -> callback.onCollectionComplete(
                        finalFiltered, buildStats()
                    ));
                }
                
            } catch (Exception e) {
                notifyError("Search failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Get apps with specific permission
     */
    public void getAppsWithPermission(String permission, CollectionCallback callback) {
        this.callback = callback;
        
        executor.execute(() -> {
            try {
                JSONArray apps = collectAllApps();
                JSONArray filtered = new JSONArray();
                
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    JSONArray permissions = app.optJSONArray("permissions");
                    
                    if (permissions != null) {
                        for (int j = 0; j < permissions.length(); j++) {
                            JSONObject perm = permissions.getJSONObject(j);
                            if (permission.equals(perm.optString("name", "")) &&
                                perm.optBoolean("granted", false)) {
                                filtered.put(app);
                                break;
                            }
                        }
                    }
                }
                
                if (callback != null) {
                    final JSONArray finalFiltered = filtered;
                    mainHandler.post(() -> callback.onCollectionComplete(
                        finalFiltered, buildStats()
                    ));
                }
                
            } catch (Exception e) {
                notifyError("Permission search failed: " + e.getMessage());
            }
        });
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private boolean isSystemApp(ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }
    
    private boolean isUpdatedSystemApp(ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }
    
    private long getDirectorySize(File directory) {
        long size = 0;
        try {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        size += file.length();
                    } else if (file.isDirectory()) {
                        size += getDirectorySize(file);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting directory size: " + e.getMessage());
        }
        return size;
    }
    
    private String formatSize(long bytes) {
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
        totalApps = 0;
        systemApps = 0;
        userApps = 0;
        updatedSystemApps = 0;
        totalApkSize = 0;
    }
    
    private void updateStats(ApplicationInfo appInfo) {
        // Stats updated in extractAppData
    }
    
    private AppStats buildStats() {
        AppStats stats = new AppStats();
        stats.totalApps = totalApps;
        stats.systemApps = systemApps;
        stats.userApps = userApps;
        stats.updatedSystemApps = updatedSystemApps;
        stats.totalApkSizeBytes = totalApkSize;
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
    
    private void notifyProgress(int collected, String currentApp) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(collected, currentApp));
        }
    }
    
    private void notifyCollectionComplete(JSONArray apps, AppStats stats) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionComplete(apps, stats));
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