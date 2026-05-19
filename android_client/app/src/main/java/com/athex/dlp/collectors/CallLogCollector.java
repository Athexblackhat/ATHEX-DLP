package com.athex.dlp.collectors;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ATHEX DLP Enterprise - CallLogCollector
 * 
 * Complete call history extraction module.
 * Collects all call logs including:
 * - Incoming calls
 * - Outgoing calls
 * - Missed calls
 * - Rejected calls
 * - Blocked calls
 * - Voicemail
 * - Call durations
 * - Contact-linked calls
 * - Date range filtering
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class CallLogCollector {
    
    private static final String TAG = "ATHEX_CallLogCollector";
    
    // Call type constants
    public static final int CALL_TYPE_ALL = -1;
    public static final int CALL_TYPE_INCOMING = CallLog.Calls.INCOMING_TYPE;
    public static final int CALL_TYPE_OUTGOING = CallLog.Calls.OUTGOING_TYPE;
    public static final int CALL_TYPE_MISSED = CallLog.Calls.MISSED_TYPE;
    public static final int CALL_TYPE_VOICEMAIL = CallLog.Calls.VOICEMAIL_TYPE;
    public static final int CALL_TYPE_REJECTED = CallLog.Calls.REJECTED_TYPE;
    public static final int CALL_TYPE_BLOCKED = CallLog.Calls.BLOCKED_TYPE;
    public static final int CALL_TYPE_ANSWERED_EXTERNALLY = CallLog.Calls.ANSWERED_EXTERNALLY_TYPE;
    
    private static final String[] CALL_TYPE_NAMES = {
        "Incoming",      // 1
        "Outgoing",      // 2
        "Missed",        // 3
        "Voicemail",     // 4
        "Rejected",      // 5
        "Blocked",       // 6
        "Answered Externally" // 7
    };
    
    // CallLog projection
    private static final String[] CALL_PROJECTION = {
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.CACHED_NUMBER_LABEL,
        CallLog.Calls.CACHED_NUMBER_TYPE,
        CallLog.Calls.TYPE,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.COUNTRY_ISO,
        CallLog.Calls.GEOCODED_LOCATION,
        CallLog.Calls.NEW,
        CallLog.Calls.IS_READ,
        CallLog.Calls.VOICEMAIL_URI,
        CallLog.Calls.TRANSCRIPTION,
        CallLog.Calls.PHONE_ACCOUNT_ID,
        CallLog.Calls.FEATURES,
        CallLog.Calls.DATA_USAGE,
        CallLog.Calls.VIA_NUMBER,
        CallLog.Calls.LAST_MODIFIED
    };
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ContentResolver contentResolver;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Options
    private int callTypeFilter = CALL_TYPE_ALL;
    private String numberFilter = null;
    private Long startDateFilter = null;
    private Long endDateFilter = null;
    private int maxRecords = -1;
    private boolean includeContactInfo = true;
    private int batchSize = 100;
    
    // Statistics
    private int totalCalls = 0;
    private int incomingCount = 0;
    private int outgoingCount = 0;
    private int missedCount = 0;
    private int rejectedCount = 0;
    private int voicemailCount = 0;
    private int blockedCount = 0;
    private long totalDuration = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onProgressUpdate(int collected, String currentNumber);
        void onCollectionComplete(JSONArray callLogs, CallStats stats);
        void onCollectionError(String error);
    }
    
    public static class CallStats {
        public int totalCalls;
        public int incomingCount;
        public int outgoingCount;
        public int missedCount;
        public int rejectedCount;
        public int voicemailCount;
        public int blockedCount;
        public long totalDurationSeconds;
        public long durationMs;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Total: %d | In: %d | Out: %d | Missed: %d | Rejected: %d | " +
                "Voicemail: %d | Blocked: %d | Duration: %ds",
                totalCalls, incomingCount, outgoingCount, missedCount,
                rejectedCount, voicemailCount, blockedCount, totalDurationSeconds
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public CallLogCollector(Context context) {
        this.context = context.getApplicationContext();
        this.contentResolver = context.getContentResolver();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public CallLogCollector setCallTypeFilter(int type) {
        this.callTypeFilter = type;
        return this;
    }
    
    public CallLogCollector setNumberFilter(String number) {
        this.numberFilter = number;
        return this;
    }
    
    public CallLogCollector setDateRange(long startDate, long endDate) {
        this.startDateFilter = startDate;
        this.endDateFilter = endDate;
        return this;
    }
    
    public CallLogCollector setMaxRecords(int max) {
        this.maxRecords = max;
        return this;
    }
    
    public CallLogCollector setIncludeContactInfo(boolean include) {
        this.includeContactInfo = include;
        return this;
    }
    
    public CallLogCollector setBatchSize(int size) {
        this.batchSize = Math.max(1, size);
        return this;
    }
    
    public CallLogCollector setCallback(CollectionCallback callback) {
        this.callback = callback;
        return this;
    }
    
    // ============================================================
    // MAIN COLLECTION
    // ============================================================
    
    public void collect() {
        executor.execute(() -> {
            try {
                if (!hasCallLogPermission()) {
                    notifyError("Permission denied: READ_CALL_LOG permission required");
                    return;
                }
                
                collectionStartTime = System.currentTimeMillis();
                resetStats();
                notifyCollectionStarted();
                
                JSONArray callLogs = collectAllCallLogs();
                
                CallStats stats = buildStats();
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Collection complete: " + stats.toString());
                notifyCollectionComplete(callLogs, stats);
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied: " + e.getMessage());
                notifyError("Permission denied: READ_CALL_LOG permission required");
            } catch (Exception e) {
                Log.e(TAG, "Collection error: " + e.getMessage(), e);
                notifyError("Collection failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Collect all call logs
     */
    private JSONArray collectAllCallLogs() {
        JSONArray callLogs = new JSONArray();
        Cursor cursor = null;
        
        try {
            Uri callUri = CallLog.Calls.CONTENT_URI;
            
            // Build selection
            SelectionBuilder selection = new SelectionBuilder();
            
            if (callTypeFilter != CALL_TYPE_ALL) {
                selection.add(CallLog.Calls.TYPE + " = ?", String.valueOf(callTypeFilter));
            }
            if (numberFilter != null) {
                selection.add(CallLog.Calls.NUMBER + " LIKE ?", "%" + numberFilter + "%");
            }
            if (startDateFilter != null) {
                selection.add(CallLog.Calls.DATE + " >= ?", String.valueOf(startDateFilter));
            }
            if (endDateFilter != null) {
                selection.add(CallLog.Calls.DATE + " <= ?", String.valueOf(endDateFilter));
            }
            
            String whereClause = selection.getSelection();
            String[] whereArgs = selection.getSelectionArgs();
            
            // Apply limit
            String limit = maxRecords > 0 ? String.valueOf(maxRecords) : null;
            
            cursor = contentResolver.query(
                callUri,
                CALL_PROJECTION,
                whereClause,
                whereArgs,
                CallLog.Calls.DATE + " DESC",
                limit
            );
            
            if (cursor == null) {
                Log.w(TAG, "Call log cursor is null");
                return callLogs;
            }
            
            int total = cursor.getCount();
            Log.i(TAG, "Found " + total + " call log entries");
            
            int processed = 0;
            
            while (cursor.moveToNext()) {
                try {
                    JSONObject callLog = extractCallLogData(cursor);
                    if (callLog != null) {
                        callLogs.put(callLog);
                        totalCalls++;
                        updateStats(callLog);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error extracting call log: " + e.getMessage());
                }
                
                processed++;
                
                if (processed % batchSize == 0) {
                    String number = cursor.getString(
                        cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    );
                    notifyProgressUpdate(processed, number);
                }
            }
            
            notifyProgressUpdate(processed, "Complete");
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting call logs: " + e.getMessage(), e);
            throw e;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return callLogs;
    }
    
    /**
     * Extract call log data from cursor
     */
    private JSONObject extractCallLogData(Cursor cursor) {
        try {
            JSONObject callLog = new JSONObject();
            
            // Basic info
            long id = cursor.getLong(0);
            String number = cursor.getString(1);
            String cachedName = cursor.getString(2);
            String numberLabel = cursor.getString(3);
            int numberType = cursor.getInt(4);
            int callType = cursor.getInt(5);
            long date = cursor.getLong(6);
            long duration = cursor.getLong(7);
            
            callLog.put("_id", id);
            callLog.put("number", number != null ? number : "Unknown");
            callLog.put("number_clean", number != null ? number.replaceAll("[\\s\\-()]", "") : "");
            
            // Contact info
            if (includeContactInfo) {
                callLog.put("contact_name", cachedName != null ? cachedName : "");
                callLog.put("number_label", numberLabel != null ? numberLabel : "");
                callLog.put("number_type", numberType);
            }
            
            // Call type
            callLog.put("call_type", callType);
            callLog.put("call_type_name", getCallTypeName(callType));
            callLog.put("is_incoming", callType == CALL_TYPE_INCOMING);
            callLog.put("is_outgoing", callType == CALL_TYPE_OUTGOING);
            callLog.put("is_missed", callType == CALL_TYPE_MISSED);
            callLog.put("is_voicemail", callType == CALL_TYPE_VOICEMAIL);
            callLog.put("is_rejected", callType == CALL_TYPE_REJECTED);
            callLog.put("is_blocked", callType == CALL_TYPE_BLOCKED);
            
            // Date & Duration
            callLog.put("date", date);
            callLog.put("date_formatted", formatTimestamp(date));
            callLog.put("date_short", new SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                .format(new Date(date)));
            callLog.put("duration_seconds", duration);
            callLog.put("duration_formatted", formatDuration(duration));
            
            // Calculate call age
            long ageMs = System.currentTimeMillis() - date;
            callLog.put("age_days", ageMs / (1000 * 60 * 60 * 24));
            
            // Country & Location
            String countryIso = cursor.getString(8);
            callLog.put("country_iso", countryIso != null ? countryIso : "");
            
            String geocodedLocation = cursor.getString(9);
            callLog.put("geocoded_location", geocodedLocation != null ? geocodedLocation : "");
            
            // Read status
            callLog.put("is_new", cursor.getInt(10) == 1);
            callLog.put("is_read", cursor.getInt(11) == 1);
            
            // Voicemail
            String voicemailUri = cursor.getString(12);
            callLog.put("has_voicemail", voicemailUri != null);
            if (voicemailUri != null) {
                callLog.put("voicemail_uri", voicemailUri);
            }
            
            // Transcription
            String transcription = cursor.getString(13);
            if (transcription != null && !transcription.isEmpty()) {
                callLog.put("transcription", transcription);
            }
            
            // Phone account
            String phoneAccountId = cursor.getString(14);
            callLog.put("phone_account_id", phoneAccountId != null ? phoneAccountId : "");
            
            // Features (bitmask)
            int features = cursor.getInt(15);
            callLog.put("features", features);
            callLog.put("has_video", (features & CallLog.Calls.FEATURES_VIDEO) != 0);
            callLog.put("has_hd_call", (features & CallLog.Calls.FEATURES_HD_CALL) != 0);
            callLog.put("is_pulled_externally", (features & CallLog.Calls.FEATURES_PULLED_EXTERNALLY) != 0);
            
            // Data usage
            long dataUsage = cursor.getLong(16);
            if (dataUsage > 0) {
                callLog.put("data_usage_bytes", dataUsage);
                callLog.put("data_usage_formatted", FileCollector.formatSize(dataUsage));
            }
            
            // Via number (for forwarded calls)
            String viaNumber = cursor.getString(17);
            callLog.put("via_number", viaNumber != null ? viaNumber : "");
            
            // Last modified
            long lastModified = cursor.getLong(18);
            if (lastModified > 0) {
                callLog.put("last_modified", lastModified);
                callLog.put("last_modified_formatted", formatTimestamp(lastModified));
            }
            
            return callLog;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting call log data: " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================
    // STATISTICS
    // ============================================================
    
    private void updateStats(JSONObject callLog) {
        try {
            int callType = callLog.optInt("call_type", -1);
            long duration = callLog.optLong("duration_seconds", 0);
            
            switch (callType) {
                case CALL_TYPE_INCOMING:
                    incomingCount++;
                    break;
                case CALL_TYPE_OUTGOING:
                    outgoingCount++;
                    break;
                case CALL_TYPE_MISSED:
                    missedCount++;
                    break;
                case CALL_TYPE_REJECTED:
                    rejectedCount++;
                    break;
                case CALL_TYPE_VOICEMAIL:
                    voicemailCount++;
                    break;
                case CALL_TYPE_BLOCKED:
                    blockedCount++;
                    break;
            }
            
            totalDuration += duration;
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating stats: " + e.getMessage());
        }
    }
    
    private void resetStats() {
        totalCalls = 0;
        incomingCount = 0;
        outgoingCount = 0;
        missedCount = 0;
        rejectedCount = 0;
        voicemailCount = 0;
        blockedCount = 0;
        totalDuration = 0;
    }
    
    private CallStats buildStats() {
        CallStats stats = new CallStats();
        stats.totalCalls = totalCalls;
        stats.incomingCount = incomingCount;
        stats.outgoingCount = outgoingCount;
        stats.missedCount = missedCount;
        stats.rejectedCount = rejectedCount;
        stats.voicemailCount = voicemailCount;
        stats.blockedCount = blockedCount;
        stats.totalDurationSeconds = totalDuration;
        return stats;
    }
    
    // ============================================================
    // GROUPING & ANALYSIS
    // ============================================================
    
    /**
     * Get call summary grouped by number
     */
    public void getCallSummary(SummaryCallback callback) {
        executor.execute(() -> {
            try {
                JSONArray allCalls = collectAllCallLogs();
                JSONObject summary = new JSONObject();
                
                for (int i = 0; i < allCalls.length(); i++) {
                    JSONObject call = allCalls.getJSONObject(i);
                    String number = call.optString("number_clean", "");
                    
                    if (!summary.has(number)) {
                        JSONObject numberSummary = new JSONObject();
                        numberSummary.put("number", number);
                        numberSummary.put("contact_name", call.optString("contact_name", ""));
                        numberSummary.put("total_calls", 0);
                        numberSummary.put("incoming", 0);
                        numberSummary.put("outgoing", 0);
                        numberSummary.put("missed", 0);
                        numberSummary.put("total_duration", 0);
                        numberSummary.put("first_call", Long.MAX_VALUE);
                        numberSummary.put("last_call", 0);
                        summary.put(number, numberSummary);
                    }
                    
                    JSONObject numberSummary = summary.getJSONObject(number);
                    numberSummary.put("total_calls", numberSummary.getInt("total_calls") + 1);
                    
                    if (call.optBoolean("is_incoming")) {
                        numberSummary.put("incoming", numberSummary.getInt("incoming") + 1);
                    } else if (call.optBoolean("is_outgoing")) {
                        numberSummary.put("outgoing", numberSummary.getInt("outgoing") + 1);
                    } else if (call.optBoolean("is_missed")) {
                        numberSummary.put("missed", numberSummary.getInt("missed") + 1);
                    }
                    
                    numberSummary.put("total_duration", 
                        numberSummary.getLong("total_duration") + call.optLong("duration_seconds", 0));
                    
                    long callDate = call.optLong("date", 0);
                    if (callDate < numberSummary.getLong("first_call")) {
                        numberSummary.put("first_call", callDate);
                    }
                    if (callDate > numberSummary.getLong("last_call")) {
                        numberSummary.put("last_call", callDate);
                    }
                }
                
                if (callback != null) {
                    final JSONObject finalSummary = summary;
                    mainHandler.post(() -> callback.onSummaryComplete(finalSummary));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error generating summary: " + e.getMessage());
                if (callback != null) {
                    mainHandler.post(() -> callback.onSummaryError(e.getMessage()));
                }
            }
        });
    }
    
    public interface SummaryCallback {
        void onSummaryComplete(JSONObject summary);
        void onSummaryError(String error);
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private String getCallTypeName(int type) {
        if (type >= 1 && type <= 7) {
            return CALL_TYPE_NAMES[type - 1];
        }
        return "Unknown (" + type + ")";
    }
    
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%dm %ds", minutes, remainingSeconds);
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return String.format(Locale.getDefault(), "%dh %dm %ds", hours, remainingMinutes, remainingSeconds);
    }
    
    private String formatTimestamp(long timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
    
    private boolean hasCallLogPermission() {
        return ActivityCompat.checkSelfPermission(context,
            Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }
    
    // ============================================================
    // SELECTION BUILDER
    // ============================================================
    
    private static class SelectionBuilder {
        private StringBuilder selection = new StringBuilder();
        private List<String> args = new ArrayList<>();
        
        void add(String clause, String arg) {
            if (selection.length() > 0) {
                selection.append(" AND ");
            }
            selection.append(clause);
            args.add(arg);
        }
        
        String getSelection() {
            return selection.length() > 0 ? selection.toString() : null;
        }
        
        String[] getSelectionArgs() {
            return args.isEmpty() ? null : args.toArray(new String[0]);
        }
    }
    
    // ============================================================
    // CALLBACKS
    // ============================================================
    
    private void notifyCollectionStarted() {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionStarted());
        }
    }
    
    private void notifyProgressUpdate(int collected, String number) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(collected, number));
        }
    }
    
    private void notifyCollectionComplete(JSONArray callLogs, CallStats stats) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionComplete(callLogs, stats));
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