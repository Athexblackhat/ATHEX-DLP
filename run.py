#!/usr/bin/env python3
"""
             ATHEX DLP ENTERPRISE - MAIN CONTROL SCRIPT             
                                                                      
  Usage: python run.py [command]                                     
                                                                      
  Commands:                                                           
    start    - Start server & open dashboard (default)               
    setup    - Install all dependencies                              
    build    - Build APK from template                               
    clean    - Clean temporary files                                 
    status   - Check system status                                   
    help     - Show this help                                        

"""

import os
import sys
import time
import json
import shutil
import socket
import signal
import subprocess
import webbrowser
from pathlib import Path
from datetime import datetime

# ============================================================
# CONFIGURATION
# ============================================================

class Config:
    """Global configuration"""
    PROJECT_NAME = "ATHEX DLP Enterprise"
    VERSION = "2.0.0"
    
    # Server settings
    WEB_HOST = "127.0.0.1"
    WEB_PORT = 5000
    TCP_HOST = "0.0.0.0"
    TCP_PORT = 22533
    
    # Paths
    BASE_DIR = Path(__file__).parent
    SERVER_DIR = BASE_DIR / "server"
    ANDROID_DIR = BASE_DIR / "android_client"
    TOOLS_DIR = BASE_DIR / "tools"
    TEMPLATES_DIR = SERVER_DIR / "templates"
    STATIC_DIR = SERVER_DIR / "static"
    OUTPUT_DIR = BASE_DIR / "output"
    LOGS_DIR = BASE_DIR / "logs"
    
    APK_TEMPLATE = SERVER_DIR / "apk_builder" / "template.apk"
    KEYSTORE = SERVER_DIR / "apk_builder" / "athex.keystore"

# ============================================================
# BANNER & UI
# ============================================================

def print_banner():
    """Print cool banner"""
    banner = f"""

                                                                      
         █████╗ ████████╗██╗  ██╗███████╗██╗  ██╗                    
        ██╔══██╗╚══██╔══╝██║  ██║██╔════╝╚██╗██╔╝                    
        ███████║   ██║   ███████║█████╗   ╚███╔╝                     
        ██╔══██║   ██║   ██╔══██║██╔══╝   ██╔██╗                     
        ██║  ██║   ██║   ██║  ██║███████╗██╔╝ ██╗                    
        ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝                    
                                                                      
           Data Loss Prevention & Enterprise Control                  
                      Version {Config.VERSION}                            
"""
    print(banner)

def print_status(message, status="info"):
    """Print colored status message"""
    colors = {
        "info": "\033[96m",     # Cyan
        "success": "\033[92m",  # Green
        "warning": "\033[93m",  # Yellow
        "error": "\033[91m",    # Red
        "reset": "\033[0m"
    }
    icon = {
        "info": "ℹ️",
        "success": "✅",
        "warning": "⚠️",
        "error": "❌"
    }
    print(f"{colors.get(status, '')}[{icon.get(status, '')}] {message}{colors['reset']}")

# ============================================================
# SETUP FUNCTION
# ============================================================

def setup_environment():
    """Install all dependencies"""
    print_banner()
    print_status("Setting up ATHEX DLP Environment...", "info")
    print()
    
    # Create necessary directories
    dirs_to_create = [
        Config.LOGS_DIR,
        Config.OUTPUT_DIR,
        Config.STATIC_DIR / "css",
        Config.STATIC_DIR / "js",
    ]
    
    for d in dirs_to_create:
        d.mkdir(parents=True, exist_ok=True)
        print_status(f"Created directory: {d}", "success")
    
    # Install Python dependencies
    print_status("Installing Python dependencies...", "info")
    requirements = Config.SERVER_DIR / "requirements.txt"
    
    if requirements.exists():
        try:
            subprocess.check_call([
                sys.executable, "-m", "pip", "install", "-r", str(requirements)
            ])
            print_status("Dependencies installed successfully!", "success")
        except subprocess.CalledProcessError:
            print_status("Failed to install dependencies", "error")
            return False
    else:
        # Create requirements if not exists
        with open(requirements, 'w') as f:
            f.write("flask==3.0.0\n")
            f.write("flask-socketio==5.3.6\n")
            f.write("flask-cors==4.0.0\n")
            f.write("gevent==23.9.1\n")
            f.write("gevent-websocket==0.10.1\n")
            f.write("python-socketio==5.11.1\n")
            f.write("requests==2.31.0\n")
        print_status("Created requirements.txt", "success")
        
        # Try install again
        try:
            subprocess.check_call([
                sys.executable, "-m", "pip", "install", "-r", str(requirements)
            ])
            print_status("Dependencies installed successfully!", "success")
        except:
            print_status("Please install manually: pip install -r " + str(requirements), "warning")
    
    print()
    print_status("Setup complete! Run 'python run.py start' to begin.", "success")
    return True

# ============================================================
# START SERVER
# ============================================================

def check_port(host, port):
    """Check if port is available"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1)
        result = sock.connect_ex((host, port))
        sock.close()
        return result != 0  # True if port is free
    except:
        return False

def start_server():
    """Start the ATHEX DLP server"""
    print_banner()
    print_status("Starting ATHEX DLP Enterprise Server...", "info")
    print()
    
    # Check if server code exists
    app_py = Config.SERVER_DIR / "app.py"
    if not app_py.exists():
        print_status("server/app.py not found! Creating default server...", "warning")
        create_default_server()
    
    # Check ports
    if not check_port(Config.WEB_HOST, Config.WEB_PORT):
        print_status(f"Port {Config.WEB_PORT} is already in use!", "error")
        print_status(f"Try: python run.py start --port {Config.WEB_PORT + 1}", "warning")
        return False

    print("              SERVER STARTING...                          ")
    print(f" 🌐 Dashboard:  http://{Config.WEB_HOST}:{Config.WEB_PORT}           ")
    print(f" 📱 TCP Server: {Config.TCP_HOST}:{Config.TCP_PORT}                      ")
    print(f"  📁 Server Dir: {Config.SERVER_DIR}                      ")
    print(f"  📱 Android Dir: {Config.ANDROID_DIR}                    ")
    print()
    
    # Start server process
    print_status("Starting Flask server...", "info")
    
    try:
        # Change to server directory and run
        os.chdir(Config.SERVER_DIR)
        
        # Start the Flask app
        cmd = [
            sys.executable, "app.py",
            "--host", Config.WEB_HOST,
            "--port", str(Config.WEB_PORT),
            "--tcp-port", str(Config.TCP_PORT)
        ]
        
        print_status("Server is running! Press Ctrl+C to stop.", "success")
        print()
        
        # Open browser after short delay
        def open_browser():
            time.sleep(2)
            url = f"http://{Config.WEB_HOST}:{Config.WEB_PORT}"
            print_status(f"Opening dashboard: {url}", "info")
            webbrowser.open(url)
        
        import threading
        threading.Thread(target=open_browser, daemon=True).start()
        
        # Run server
        subprocess.run(cmd)
        
    except KeyboardInterrupt:
        print()
        print_status("Shutting down server...", "warning")
        print_status("Server stopped.", "info")
    except Exception as e:
        print_status(f"Error: {e}", "error")
        return False
    
    return True

def create_default_server():
    """Create default app.py if not exists"""
    app_code = '''#!/usr/bin/env python3
"""
ATHEX DLP Enterprise - Main Server
"""
import sys
import argparse
from flask import Flask, render_template
from flask_socketio import SocketIO
from flask_cors import CORS

app = Flask(__name__)
app.config['SECRET_KEY'] = 'athex-dlp-secret-key-2024'
CORS(app)
socketio = SocketIO(app, cors_allowed_origins="*")

@app.route('/')
def index():
    """Main dashboard"""
    return render_template('index.html')

@app.route('/api/status')
def status():
    """Server status"""
    return {"status": "online", "version": "2.0.0"}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=5000)
    parser.add_argument('--tcp-port', type=int, default=22533)
    args = parser.parse_args()
    
    print(f"ATHEX DLP Server starting on {args.host}:{args.port}")
    print(f"TCP Server will listen on 0.0.0.0:{args.tcp_port}")
    
    socketio.run(app, host=args.host, port=args.port, debug=True)
'''
    
    app_py = Config.SERVER_DIR / "app.py"
    with open(app_py, 'w') as f:
        f.write(app_code)
    print_status(f"Created: {app_py}", "success")
    
    # Create basic dashboard if not exists
    dashboard_html = Config.TEMPLATES_DIR / "index.html"
    if not dashboard_html.exists():
        with open(dashboard_html, 'w') as f:
            f.write('''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ATHEX DLP Enterprise</title>
    <style>
        body { background: #0a0e17; color: #00f0ff; font-family: monospace; 
               display: flex; align-items: center; justify-content: center; 
               height: 100vh; margin: 0; }
        .container { text-align: center; }
        h1 { font-size: 3em; text-shadow: 0 0 20px #00f0ff; }
        p { color: #94a3b8; }
        .status { color: #10b981; }
    </style>
</head>
<body>
    <div class="container">
        <h1>◆ ATHEX DLP</h1>
        <p>Enterprise Control Dashboard</p>
        <p class="status">● Server Online</p>
        <p>Dashboard coming soon...</p>
    </div>
</body>
</html>''')
        print_status(f"Created: {dashboard_html}", "success")

# ============================================================
# BUILD APK
# ============================================================

def build_apk(config_file=None):
    """Build custom APK"""
    print_banner()
    print_status("APK Builder - Coming Soon!", "info")
    print()
    print("This feature will:")
    print("  1. Take base APK template")
    print("  2. Inject your server IP & port")
    print("  3. Sign the APK")
    print("  4. Provide download link")
    print()
    print("Currently under development...")
    return True

# ============================================================
# STATUS CHECK
# ============================================================

def check_status():
    """Check system status"""
    print_banner()
    print_status("System Status Check", "info")
    print()
    
    # Check directories
    checks = [
        ("Server Directory", Config.SERVER_DIR.exists()),
        ("Android Directory", Config.ANDROID_DIR.exists()),
        ("Tools Directory", Config.TOOLS_DIR.exists()),
        ("Dashboard HTML", (Config.TEMPLATES_DIR / "index.html").exists()),
        ("Server App", (Config.SERVER_DIR / "app.py").exists()),
    ]
    
    for name, status in checks:
        icon = "✅" if status else "❌"
        print(f"  {icon} {name}: {'Found' if status else 'Missing'}")
    
    # Check port
    if check_port(Config.WEB_HOST, Config.WEB_PORT):
        print(f"  ✅ Port {Config.WEB_PORT}: Available")
    else:
        print(f"  ❌ Port {Config.WEB_PORT}: In use")
    
    # Python version
    print(f"  ℹ️  Python: {sys.version}")
    
    print()

# ============================================================
# CLEANUP
# ============================================================

def clean_files():
    """Clean temporary files"""
    print_banner()
    print_status("Cleaning temporary files...", "info")
    
    patterns = ["*.pyc", "__pycache__", "*.log", ".DS_Store"]
    cleaned = 0
    
    for pattern in patterns:
        for file in Config.BASE_DIR.rglob(pattern):
            try:
                if file.is_dir():
                    shutil.rmtree(file)
                else:
                    file.unlink()
                print(f"  🗑️  Removed: {file}")
                cleaned += 1
            except:
                pass
    
    print_status(f"Cleaned {cleaned} files/directories", "success")

# ============================================================
# MAIN ENTRY POINT
# ============================================================

def main():
    """Main entry point"""
    
    # Parse arguments
    if len(sys.argv) > 1:
        command = sys.argv[1].lower()
    else:
        command = "start"  # Default command
    
    # Handle flags
    if "--port" in sys.argv:
        try:
            port_index = sys.argv.index("--port")
            Config.WEB_PORT = int(sys.argv[port_index + 1])
        except:
            pass
    
    # Execute command
    commands = {
        "start": start_server,
        "setup": setup_environment,
        "build": build_apk,
        "clean": clean_files,
        "status": check_status,
        "help": lambda: (print_banner(), print(__doc__)),
    }
    
    if command in commands:
        try:
            commands[command]()
        except KeyboardInterrupt:
            print()
            print_status("Operation cancelled by user", "warning")
    else:
        print_banner()
        print_status(f"Unknown command: {command}", "error")
        print(__doc__)

if __name__ == "__main__":
    main()