package com.athex.dlp.collectors;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ATHEX DLP Enterprise - SMSCollector
 * 
 * Complete SMS/MMS data extraction module.
 * Collects all SMS and MMS messages including:
 * - Inbox messages
 * - Sent messages
 * - Draft messages
 * - Failed/Pending messages
 * - Message threads/conversations
 * - MMS content (images, videos, audio)
 * 
 * Features:
 * - Multi-threaded collection
 * - Thread-based grouping
 * - Date range filtering
 * - Contact-based filtering
 * - Progress tracking
 * - JSON serialization
 * - Batch processing
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class SMSCollector {
    
    private static final String TAG = "ATHEX_SMSCollector";
    
    // SMS projection columns
    private static final String[] SMS_PROJECTION = {
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.PERSON,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.BODY,
        Telephony.Sms.TYPE,
        Telephony.Sms.STATUS,
        Telephony.Sms.READ,
        Telephony.Sms.SEEN,
        Telephony.Sms.SUBJECT,
        Telephony.Sms.REPLY_PATH_PRESENT,
        Telephony.Sms.SERVICE_CENTER,
        Telephony.Sms.PROTOCOL,
        Telephony.Sms.LOCKED,
        Telephony.Sms.ERROR_CODE
    };
    
    // MMS projection columns
    private static final String[] MMS_PROJECTION = {
        Telephony.Mms._ID,
        Telephony.Mms.THREAD_ID,
        Telephony.Mms.DATE,
        Telephony.Mms.DATE_SENT,
        Telephony.Mms.MESSAGE_BOX,
        Telephony.Mms.READ,
        Telephony.Mms.SUBJECT,
        Telephony.Mms.MESSAGE_TYPE,
        Telephony.Mms.MESSAGE_SIZE,
        Telephony.Mms.TEXT_ONLY,
        Telephony.Mms.STATUS,
        Telephony.Mms.RESPONSE_TEXT
    };
    
    // Message type constants
    public static final int TYPE_ALL = 0;
    public static final int TYPE_INBOX = 1;
    public static final int TYPE_SENT = 2;
    public static final int TYPE_DRAFT = 3;
    public static final int TYPE_OUTBOX = 4;
    public static final int TYPE_FAILED = 5;
    public static final int TYPE_QUEUED = 6;
    
    // Status constants
    public static final int STATUS_NONE = -1;
    public static final int STATUS_COMPLETE = 0;
    public static final int STATUS_PENDING = 32;
    public static final int STATUS_FAILED = 64;
    
    private static final String[] SMS_TYPE_NAMES = {
        "All",      // 0
        "Inbox",    // 1
        "Sent",     // 2
        "Draft",    // 3
        "Outbox",   // 4
        "Failed",   // 5
        "Queued"    // 6
    };
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ContentResolver contentResolver;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Collection options
    private int messageTypeFilter = TYPE_ALL;
    private String contactFilter = null;      // Filter by phone number
    private Long startDateFilter = null;      // Filter messages after this date
    private Long endDateFilter = null;        // Filter messages before this date
    private int maxMessages = -1;             // Max messages to collect
    private boolean includeMMS = true;         // Include MMS messages
    private boolean groupByThread = false;     // Group by conversation thread
    private int batchSize = 100;
    
    // Statistics
    private int totalSMS = 0;
    private int totalMMS = 0;
    private int inboxCount = 0;
    private int sentCount = 0;
    private int draftCount = 0;
    private int unreadCount = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onProgressUpdate(int collected, String currentAddress);
        void onCollectionComplete(JSONArray messages, SMSStats stats);
        void onCollectionError(String error);
    }
    
    public static class SMSStats {
        public int totalSMS;
        public int totalMMS;
        public int inboxCount;
        public int sentCount;
        public int draftCount;
        public int unreadCount;
        public long durationMs;
        public long totalBytes;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "SMS: %d | MMS: %d | Inbox: %d | Sent: %d | Drafts: %d | Unread: %d | Duration: %dms",
                totalSMS, totalMMS, inboxCount, sentCount, draftCount, unreadCount, durationMs
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public SMSCollector(Context context) {
        this.context = context.getApplicationContext();
        this.contentResolver = context.getContentResolver();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public SMSCollector setMessageTypeFilter(int type) {
        this.messageTypeFilter = type;
        return this;
    }
    
    public SMSCollector setContactFilter(String phoneNumber) {
        this.contactFilter = phoneNumber;
        return this;
    }
    
    public SMSCollector setDateRange(long startDate, long endDate) {
        this.startDateFilter = startDate;
        this.endDateFilter = endDate;
        return this;
    }
    
    public SMSCollector setMaxMessages(int max) {
        this.maxMessages = max;
        return this;
    }
    
    public SMSCollector setIncludeMMS(boolean include) {
        this.includeMMS = include;
        return this;
    }
    
    public SMSCollector setGroupByThread(boolean group) {
        this.groupByThread = group;
        return this;
    }
    
    public SMSCollector setBatchSize(int size) {
        this.batchSize = Math.max(1, size);
        return this;
    }
    
    public SMSCollector setCallback(CollectionCallback callback) {
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
                notifyCollectionStarted();
                
                JSONArray allMessages;
                
                if (groupByThread) {
                    allMessages = collectByThreads();
                } else {
                    allMessages = collectAllMessages();
                }
                
                // Collect MMS if enabled
                if (includeMMS) {
                    JSONArray mmsMessages = collectMMSMessages();
                    for (int i = 0; i < mmsMessages.length(); i++) {
                        allMessages.put(mmsMessages.get(i));
                    }
                }
                
                SMSStats stats = buildStats();
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Collection complete: " + stats.toString());
                notifyCollectionComplete(allMessages, stats);
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied: " + e.getMessage());
                notifyCollectionError("Permission denied: READ_SMS permission required");
            } catch (Exception e) {
                Log.e(TAG, "Collection error: " + e.getMessage(), e);
                notifyCollectionError("Collection failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Collect all SMS messages
     */
    private JSONArray collectAllMessages() {
        JSONArray messages = new JSONArray();
        Cursor cursor = null;
        
        try {
            Uri smsUri = Telephony.Sms.CONTENT_URI;
            
            // Build selection
            SelectionBuilder selection = new SelectionBuilder();
            
            if (messageTypeFilter != TYPE_ALL) {
                selection.add("type = ?", String.valueOf(messageTypeFilter));
            }
            if (contactFilter != null) {
                selection.add("address = ?", contactFilter);
            }
            if (startDateFilter != null) {
                selection.add("date >= ?", String.valueOf(startDateFilter));
            }
            if (endDateFilter != null) {
                selection.add("date <= ?", String.valueOf(endDateFilter));
            }
            
            String whereClause = selection.getSelection();
            String[] whereArgs = selection.getSelectionArgs();
            
            // Apply limit
            String limit = maxMessages > 0 ? String.valueOf(maxMessages) : null;
            
            cursor = contentResolver.query(
                smsUri,
                SMS_PROJECTION,
                whereClause,
                whereArgs,
                Telephony.Sms.DATE + " DESC",
                limit
            );
            
            if (cursor == null) {
                Log.w(TAG, "SMS cursor is null");
                return messages;
            }
            
            int total = cursor.getCount();
            Log.i(TAG, "Found " + total + " SMS messages");
            
            int processed = 0;
            
            while (cursor.moveToNext()) {
                try {
                    JSONObject message = extractSMSData(cursor);
                    if (message != null) {
                        messages.put(message);
                        totalSMS++;
                        
                        // Update counts
                        int type = message.optInt("type", -1);
                        switch (type) {
                            case 1: inboxCount++; break;
                            case 2: sentCount++; break;
                            case 3: draftCount++; break;
                        }
                        
                        if (message.optInt("read", 1) == 0) {
                            unreadCount++;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error extracting SMS: " + e.getMessage());
                }
                
                processed++;
                
                if (processed % batchSize == 0) {
                    String address = cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    );
                    notifyProgressUpdate(processed, address);
                }
            }
            
            notifyProgressUpdate(processed, "Complete");
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting SMS: " + e.getMessage(), e);
            throw e;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return messages;
    }
    
    /**
     * Collect messages grouped by conversation threads
     */
    private JSONArray collectByThreads() {
        JSONArray threads = new JSONArray();
        Cursor cursor = null;
        
        try {
            // Get distinct thread IDs
            Uri threadUri = Telephony.Threads.CONTENT_URI;
            
            cursor = contentResolver.query(
                threadUri,
                new String[]{
                    Telephony.Threads._ID,
                    Telephony.Threads.RECIPIENT_IDS,
                    Telephony.Threads.MESSAGE_COUNT,
                    Telephony.Threads.SNIPPET,
                    Telephony.Threads.DATE
                },
                null, null,
                Telephony.Threads.DATE + " DESC"
            );
            
            if (cursor == null) return threads;
            
            int total = cursor.getCount();
            Log.i(TAG, "Found " + total + " conversation threads");
            
            int processed = 0;
            
            while (cursor.moveToNext()) {
                try {
                    JSONObject thread = new JSONObject();
                    
                    long threadId = cursor.getLong(0);
                    String recipientIds = cursor.getString(1);
                    int messageCount = cursor.getInt(2);
                    String snippet = cursor.getString(3);
                    long date = cursor.getLong(4);
                    
                    thread.put("thread_id", threadId);
                    thread.put("recipient_ids", recipientIds != null ? recipientIds : "");
                    thread.put("message_count", messageCount);
                    thread.put("snippet", snippet != null ? snippet : "");
                    thread.put("date", date);
                    thread.put("date_formatted", formatTimestamp(date));
                    
                    // Get messages for this thread
                    JSONArray threadMessages = getThreadMessages(threadId);
                    thread.put("messages", threadMessages);
                    thread.put("message_count_actual", threadMessages.length());
                    
                    threads.put(thread);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error extracting thread: " + e.getMessage());
                }
                
                processed++;
                
                if (processed % batchSize == 0) {
                    notifyProgressUpdate(processed, "Thread #" + processed);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting threads: " + e.getMessage(), e);
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return threads;
    }
    
    /**
     * Get messages for a specific thread
     */
    private JSONArray getThreadMessages(long threadId) {
        JSONArray messages = new JSONArray();
        Cursor cursor = null;
        
        try {
            String selection = "thread_id = ?";
            String[] selectionArgs = { String.valueOf(threadId) };
            
            cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                SMS_PROJECTION,
                selection,
                selectionArgs,
                Telephony.Sms.DATE + " ASC"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject message = extractSMSData(cursor);
                    if (message != null) {
                        messages.put(message);
                        totalSMS++;
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting thread messages: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return messages;
    }
    
    /**
     * Collect MMS messages
     */
    private JSONArray collectMMSMessages() {
        JSONArray mmsMessages = new JSONArray();
        Cursor cursor = null;
        
        try {
            Uri mmsUri = Telephony.Mms.CONTENT_URI;
            
            String limit = maxMessages > 0 ? String.valueOf(maxMessages) : null;
            
            cursor = contentResolver.query(
                mmsUri,
                MMS_PROJECTION,
                null, null,
                Telephony.Mms.DATE + " DESC",
                limit
            );
            
            if (cursor == null) return mmsMessages;
            
            int total = cursor.getCount();
            Log.i(TAG, "Found " + total + " MMS messages");
            
            while (cursor.moveToNext()) {
                try {
                    JSONObject mms = new JSONObject();
                    
                    mms.put("_id", cursor.getLong(0));
                    mms.put("thread_id", cursor.getLong(1));
                    mms.put("date", cursor.getLong(2));
                    mms.put("date_sent", cursor.getLong(3));
                    mms.put("date_formatted", formatTimestamp(cursor.getLong(2)));
                    
                    int msgBox = cursor.getInt(4);
                    mms.put("message_box", msgBox);
                    mms.put("message_box_name", getSmsTypeName(msgBox));
                    
                    mms.put("read", cursor.getInt(5) == 1);
                    
                    String subject = cursor.getString(6);
                    mms.put("subject", subject != null ? subject : "");
                    
                    mms.put("message_type", cursor.getInt(7));
                    mms.put("message_size", cursor.getLong(8));
                    mms.put("is_text_only", cursor.getInt(9) == 1);
                    mms.put("status", cursor.getInt(10));
                    
                    String responseText = cursor.getString(11);
                    mms.put("response_text", responseText != null ? responseText : "");
                    
                    mms.put("is_mms", true);
                    
                    // Get MMS parts (address, content)
                    JSONObject mmsParts = getMMSParts(cursor.getLong(0));
                    if (mmsParts.length() > 0) {
                        mms.put("parts", mmsParts);
                    }
                    
                    mmsMessages.put(mms);
                    totalMMS++;
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error extracting MMS: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting MMS: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return mmsMessages;
    }
    
    /**
     * Get MMS parts (address, text, media)
     */
    private JSONObject getMMSParts(long mmsId) {
        JSONObject parts = new JSONObject();
        Cursor cursor = null;
        
        try {
            // Get MMS address
            Uri addrUri = Uri.parse("content://mms/" + mmsId + "/addr");
            cursor = contentResolver.query(addrUri, null, null, null, null);
            
            if (cursor != null) {
                JSONArray addresses = new JSONArray();
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndex("address"));
                    int type = cursor.getInt(cursor.getColumnIndex("type"));
                    
                    JSONObject addr = new JSONObject();
                    addr.put("address", address != null ? address : "");
                    addr.put("type", type); // 137 = From, 151 = To
                    addresses.put(addr);
                }
                cursor.close();
                parts.put("addresses", addresses);
            }
            
            // Get MMS text part
            Uri partUri = Uri.parse("content://mms/" + mmsId + "/part");
            cursor = contentResolver.query(partUri, null, null, null, null);
            
            if (cursor != null) {
                JSONArray mediaParts = new JSONArray();
                
                while (cursor.moveToNext()) {
                    String contentType = cursor.getString(cursor.getColumnIndex("ct"));
                    String text = cursor.getString(cursor.getColumnIndex("text"));
                    String name = cursor.getString(cursor.getColumnIndex("name"));
                    
                    JSONObject part = new JSONObject();
                    if (contentType != null) part.put("content_type", contentType);
                    if (text != null) part.put("text", text);
                    if (name != null) part.put("name", name);
                    
                    // Detect if this is an image/video/audio
                    if (contentType != null) {
                        if (contentType.startsWith("image/")) {
                            part.put("media_type", "image");
                        } else if (contentType.startsWith("video/")) {
                            part.put("media_type", "video");
                        } else if (contentType.startsWith("audio/")) {
                            part.put("media_type", "audio");
                        } else if (contentType.equals("text/plain")) {
                            part.put("media_type", "text");
                        }
                    }
                    
                    mediaParts.put(part);
                }
                cursor.close();
                
                parts.put("media", mediaParts);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting MMS parts: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return parts;
    }
    
    // ============================================================
    // DATA EXTRACTION
    // ============================================================
    
    /**
     * Extract SMS data from cursor
     */
    private JSONObject extractSMSData(Cursor cursor) {
        try {
            JSONObject message = new JSONObject();
            
            message.put("_id", cursor.getLong(0));
            message.put("thread_id", cursor.getLong(1));
            
            String address = cursor.getString(2);
            message.put("address", address != null ? address : "Unknown");
            
            String person = cursor.getString(3);
            message.put("person", person != null ? person : "");
            
            long date = cursor.getLong(4);
            message.put("date", date);
            message.put("date_formatted", formatTimestamp(date));
            
            long dateSent = cursor.getLong(5);
            message.put("date_sent", dateSent);
            
            String body = cursor.getString(6);
            message.put("body", body != null ? body : "");
            message.put("body_length", body != null ? body.length() : 0);
            
            int type = cursor.getInt(7);
            message.put("type", type);
            message.put("type_name", getSmsTypeName(type));
            message.put("is_incoming", type == 1);
            message.put("is_outgoing", type == 2);
            
            int status = cursor.getInt(8);
            message.put("status", status);
            message.put("status_name", getStatusName(status));
            
            message.put("read", cursor.getInt(9) == 1);
            message.put("seen", cursor.getInt(10) == 1);
            
            String subject = cursor.getString(11);
            message.put("subject", subject != null ? subject : "");
            
            message.put("reply_path_present", cursor.getInt(12) == 1);
            
            String serviceCenter = cursor.getString(13);
            message.put("service_center", serviceCenter != null ? serviceCenter : "");
            
            String protocol = cursor.getString(14);
            message.put("protocol", protocol != null ? protocol : "");
            
            message.put("locked", cursor.getInt(15) == 1);
            message.put("error_code", cursor.getInt(16));
            
            message.put("is_mms", false);
            
            // Add timestamp in different formats
            message.put("date_iso", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
                .format(new Date(date)));
            message.put("date_short", new SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                .format(new Date(date)));
            
            // Calculate message age
            long ageMs = System.currentTimeMillis() - date;
            message.put("age_days", ageMs / (1000 * 60 * 60 * 24));
            
            return message;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting SMS data: " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private String getSmsTypeName(int type) {
        if (type >= 0 && type < SMS_TYPE_NAMES.length) {
            return SMS_TYPE_NAMES[type];
        }
        return "Unknown (" + type + ")";
    }
    
    private String getStatusName(int status) {
        switch (status) {
            case STATUS_NONE: return "None";
            case STATUS_COMPLETE: return "Complete";
            case STATUS_PENDING: return "Pending";
            case STATUS_FAILED: return "Failed";
            default: return "Unknown (" + status + ")";
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
    
    private SMSStats buildStats() {
        SMSStats stats = new SMSStats();
        stats.totalSMS = totalSMS;
        stats.totalMMS = totalMMS;
        stats.inboxCount = inboxCount;
        stats.sentCount = sentCount;
        stats.draftCount = draftCount;
        stats.unreadCount = unreadCount;
        return stats;
    }
    
    // ============================================================
    // SELECTION BUILDER HELPER
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
    
    private void notifyProgressUpdate(int collected, String address) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(collected, address));
        }
    }
    
    private void notifyCollectionComplete(JSONArray messages, SMSStats stats) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCollectionComplete(messages, stats));
        }
    }
    
    private void notifyCollectionError(String error) {
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