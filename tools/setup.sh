#!/bin/bash
# ============================================================
# ATHEX DLP Enterprise - Setup Script
# ============================================================
# 
# This script automates the complete setup process:
# 1. Check prerequisites (Python, Java, Android SDK)
# 2. Install Python dependencies
# 3. Download required tools (apktool, uber-apk-signer)
# 4. Generate keystore for APK signing
# 5. Create directory structure
# 6. Set up permissions
#
# Usage:
#   chmod +x setup.sh
#   ./setup.sh
#   ./setup.sh --force     # Force reinstall
#   ./setup.sh --skip-java # Skip Java check
# ============================================================

set -e  # Exit on error
 
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ============================================================
# CONFIGURATION
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SERVER_DIR="$PROJECT_DIR/server"
ANDROID_DIR="$PROJECT_DIR/android_client"
TOOLS_DIR="$PROJECT_DIR/tools"
FORCE_REINSTALL=false
SKIP_JAVA=false
SKIP_PYTHON=false
SKIP_ANDROID=false

# Tool URLs
APKTOOL_URL="https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar"
UBER_SIGNER_URL="https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar"

# ============================================================
# BANNER
# ============================================================
print_banner() {
    echo -e "${CYAN}"
    echo "                                                         "
    echo "         █████╗ ████████╗██╗  ██╗███████╗██╗  ██╗        "
    echo "        ██╔══██╗╚══██╔══╝██║  ██║██╔════╝╚██╗██╔╝        "
    echo "        ███████║   ██║   ███████║█████╗   ╚███╔╝         "
    echo "        ██╔══██║   ██║   ██╔══██║██╔══╝   ██╔██╗         "
    echo "        ██║  ██║   ██║   ██║  ██║███████╗██╔╝ ██╗        "
    echo "        ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝        "
    echo "                                                          "
    echo "              Enterprise Setup Script                     "
    echo "                    Version 2.0.0                         "
    echo -e "${NC}"
    echo ""
}

# ============================================================
# PARSE ARGUMENTS
# ============================================================
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --force)
                FORCE_REINSTALL=true
                shift
                ;;
            --skip-java)
                SKIP_JAVA=true
                shift
                ;;
            --skip-python)
                SKIP_PYTHON=true
                shift
                ;;
            --skip-android)
                SKIP_ANDROID=true
                shift
                ;;
            --help)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --force          Force reinstall all components"
                echo "  --skip-java      Skip Java check"
                echo "  --skip-python    Skip Python check"
                echo "  --skip-android   Skip Android SDK check"
                echo "  --help           Show this help"
                exit 0
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                exit 1
                ;;
        esac
    done
}

# ============================================================
# CHECK PREREQUISITES
# ============================================================
check_prerequisites() {
    echo -e "${BOLD}${BLUE}[1/6] Checking Prerequisites...${NC}"
    echo ""
    
    # Check Python
    if [ "$SKIP_PYTHON" = false ]; then
        if command -v python3 &> /dev/null; then
            PYTHON_VERSION=$(python3 --version 2>&1)
            echo -e "  ${GREEN}✓${NC} Python: ${PYTHON_VERSION}"
        elif command -v python &> /dev/null; then
            PYTHON_VERSION=$(python --version 2>&1)
            echo -e "  ${GREEN}✓${NC} Python: ${PYTHON_VERSION}"
        else
            echo -e "  ${RED}✗${NC} Python 3 not found. Please install Python 3.8+"
            exit 1
        fi
    else
        echo -e "  ${YELLOW}⊘${NC} Python check skipped"
    fi
    
    # Check pip
    if command -v pip3 &> /dev/null; then
        echo -e "  ${GREEN}✓${NC} pip: $(pip3 --version 2>&1 | head -n1)"
    elif command -v pip &> /dev/null; then
        echo -e "  ${GREEN}✓${NC} pip: $(pip --version 2>&1 | head -n1)"
    else
        echo -e "  ${YELLOW}⚠${NC} pip not found. Installing..."
        python3 -m ensurepip --upgrade 2>/dev/null || true
    fi
    
    # Check Java (required for APK building)
    if [ "$SKIP_JAVA" = false ]; then
        if command -v java &> /dev/null; then
            JAVA_VERSION=$(java -version 2>&1 | head -n1)
            echo -e "  ${GREEN}✓${NC} Java: ${JAVA_VERSION}"
        else
            echo -e "  ${YELLOW}⚠${NC} Java not found. APK building will not work."
            echo -e "    Install Java JDK 8+ from: https://adoptium.net/"
        fi
        
        # Check keytool (part of JDK)
        if command -v keytool &> /dev/null; then
            echo -e "  ${GREEN}✓${NC} keytool: available"
        else
            echo -e "  ${YELLOW}⚠${NC} keytool not found (required for APK signing)"
        fi
    else
        echo -e "  ${YELLOW}⊘${NC} Java check skipped"
    fi
    
    # Check Android SDK (optional)
    if [ "$SKIP_ANDROID" = false ]; then
        if [ -n "$ANDROID_HOME" ] || [ -n "$ANDROID_SDK_ROOT" ]; then
            ANDROID_SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
            echo -e "  ${GREEN}✓${NC} Android SDK: ${ANDROID_SDK}"
        else
            # Check common paths
            COMMON_PATHS=(
                "$HOME/Android/Sdk"
                "$HOME/Library/Android/sdk"
                "/usr/local/lib/android/sdk"
            )
            FOUND=false
            for path in "${COMMON_PATHS[@]}"; do
                if [ -d "$path" ]; then
                    echo -e "  ${GREEN}✓${NC} Android SDK: ${path}"
                    FOUND=true
                    break
                fi
            done
            if [ "$FOUND" = false ]; then
                echo -e "  ${YELLOW}⚠${NC} Android SDK not found (optional, for manual builds)"
            fi
        fi
    else
        echo -e "  ${YELLOW}⊘${NC} Android SDK check skipped"
    fi
    
    echo ""
}

# ============================================================
# INSTALL PYTHON DEPENDENCIES
# ============================================================
install_python_deps() {
    echo -e "${BOLD}${BLUE}[2/6] Installing Python Dependencies...${NC}"
    echo ""
    
    REQUIREMENTS_FILE="$SERVER_DIR/requirements.txt"
    
    if [ ! -f "$REQUIREMENTS_FILE" ]; then
        echo -e "  ${YELLOW}⚠${NC} requirements.txt not found. Creating default..."
        cat > "$REQUIREMENTS_FILE" << 'EOF'
Flask==3.0.0
Flask-SocketIO==5.3.6
Flask-Cors==4.0.0
python-socketio==5.11.1
gevent==23.9.1
gevent-websocket==0.10.1
eventlet==0.33.3
requests==2.31.0
Werkzeug==3.0.1
cryptography==41.0.7
PyJWT==2.8.0
python-dotenv==1.0.0
colorama==0.4.6
rich==13.7.0
EOF
        echo -e "  ${GREEN}✓${NC} Created requirements.txt"
    fi
    
    echo -e "  Installing packages..."
    
    if command -v pip3 &> /dev/null; then
        PIP_CMD="pip3"
    else
        PIP_CMD="pip"
    fi
    
    if [ "$FORCE_REINSTALL" = true ]; then
        $PIP_CMD install --upgrade --force-reinstall -r "$REQUIREMENTS_FILE" 2>&1 | while read line; do
            echo -e "    ${line}"
        done
    else
        $PIP_CMD install -r "$REQUIREMENTS_FILE" 2>&1 | while read line; do
            echo -e "    ${line}"
        done
    fi
    
    if [ $? -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} Python dependencies installed successfully"
    else
        echo -e "  ${RED}✗${NC} Failed to install some dependencies"
        echo -e "    Try: pip install -r $REQUIREMENTS_FILE"
    fi
    
    echo ""
}

# ============================================================
# DOWNLOAD TOOLS
# ============================================================
download_tools() {
    echo -e "${BOLD}${BLUE}[3/6] Downloading Required Tools...${NC}"
    echo ""
    
    mkdir -p "$TOOLS_DIR"
    
    # Download apktool
    APKTOOL_PATH="$TOOLS_DIR/apktool.jar"
    if [ -f "$APKTOOL_PATH" ] && [ "$FORCE_REINSTALL" = false ]; then
        echo -e "  ${GREEN}✓${NC} apktool already exists"
    else
        echo -e "  Downloading apktool..."
        if command -v wget &> /dev/null; then
            wget -q --show-progress -O "$APKTOOL_PATH" "$APKTOOL_URL" 2>&1 || {
                echo -e "  ${YELLOW}⚠${NC} Failed to download apktool. Download manually from:"
                echo -e "    $APKTOOL_URL"
                echo -e "    Place it at: $APKTOOL_PATH"
            }
        elif command -v curl &> /dev/null; then
            curl -L -o "$APKTOOL_PATH" "$APKTOOL_URL" 2>&1 || {
                echo -e "  ${YELLOW}⚠${NC} Failed to download apktool"
            }
        else
            echo -e "  ${YELLOW}⚠${NC} Neither wget nor curl found. Download apktool manually from:"
            echo -e "    $APKTOOL_URL"
            echo -e "    Place it at: $APKTOOL_PATH"
        fi
        
        if [ -f "$APKTOOL_PATH" ]; then
            echo -e "  ${GREEN}✓${NC} apktool downloaded"
        fi
    fi
    
    # Download uber-apk-signer
    SIGNER_PATH="$TOOLS_DIR/uber-apk-signer.jar"
    if [ -f "$SIGNER_PATH" ] && [ "$FORCE_REINSTALL" = false ]; then
        echo -e "  ${GREEN}✓${NC} uber-apk-signer already exists"
    else
        echo -e "  Downloading uber-apk-signer..."
        if command -v wget &> /dev/null; then
            wget -q --show-progress -O "$SIGNER_PATH" "$UBER_SIGNER_URL" 2>&1 || {
                echo -e "  ${YELLOW}⚠${NC} Failed to download uber-apk-signer"
            }
        elif command -v curl &> /dev/null; then
            curl -L -o "$SIGNER_PATH" "$UBER_SIGNER_URL" 2>&1 || {
                echo -e "  ${YELLOW}⚠${NC} Failed to download uber-apk-signer"
            }
        else
            echo -e "  ${YELLOW}⚠${NC} Download uber-apk-signer manually from:"
            echo -e "    $UBER_SIGNER_URL"
            echo -e "    Place it at: $SIGNER_PATH"
        fi
        
        if [ -f "$SIGNER_PATH" ]; then
            echo -e "  ${GREEN}✓${NC} uber-apk-signer downloaded"
        fi
    fi
    
    echo ""
}

# ============================================================
# GENERATE KEYSTORE
# ============================================================
generate_keystore() {
    echo -e "${BOLD}${BLUE}[4/6] Generating Keystore for APK Signing...${NC}"
    echo ""
    
    KEYSTORE_PATH="$TOOLS_DIR/athex.keystore"
    
    if [ -f "$KEYSTORE_PATH" ] && [ "$FORCE_REINSTALL" = false ]; then
        echo -e "  ${GREEN}✓${NC} Keystore already exists: $KEYSTORE_PATH"
    else
        if command -v keytool &> /dev/null; then
            echo -e "  Generating keystore..."
            
            keytool -genkey -v \
                -keystore "$KEYSTORE_PATH" \
                -alias athex \
                -keyalg RSA \
                -keysize 2048 \
                -validity 10000 \
                -storepass athex123 \
                -keypass athex123 \
                -dname "CN=ATHEX DLP, OU=Security, O=ATHEX Enterprise, L=Unknown, ST=Unknown, C=US" \
                2>/dev/null || {
                    # Try without dname (older keytool)
                    keytool -genkey -v \
                        -keystore "$KEYSTORE_PATH" \
                        -alias athex \
                        -keyalg RSA \
                        -keysize 2048 \
                        -validity 10000 \
                        -storepass athex123 \
                        -keypass athex123 2>/dev/null
                }
            
            if [ -f "$KEYSTORE_PATH" ]; then
                echo -e "  ${GREEN}✓${NC} Keystore generated: $KEYSTORE_PATH"
                echo -e "    Password: athex123"
                echo -e "    Alias:    athex"
            else
                echo -e "  ${RED}✗${NC} Failed to generate keystore"
            fi
        else
            echo -e "  ${YELLOW}⚠${NC} keytool not available. Keystore not generated."
            echo -e "    APK signing will not work without a keystore."
        fi
    fi
    
    echo ""
}

# ============================================================
# CREATE DIRECTORY STRUCTURE
# ============================================================
create_directories() {
    echo -e "${BOLD}${BLUE}[5/6] Creating Directory Structure...${NC}"
    echo ""
    
    DIRS=(
        "$PROJECT_DIR/logs"
        "$PROJECT_DIR/output"
        "$PROJECT_DIR/output/apks"
        "$PROJECT_DIR/data"
        "$PROJECT_DIR/data/camera_captures"
        "$PROJECT_DIR/data/audio_recordings"
        "$PROJECT_DIR/data/screenshots"
        "$PROJECT_DIR/data/recordings"
        "$PROJECT_DIR/temp"
        "$SERVER_DIR/static/css"
        "$SERVER_DIR/static/js"
        "$SERVER_DIR/static/assets"
        "$SERVER_DIR/templates"
    )
    
    for dir in "${DIRS[@]}"; do
        if [ ! -d "$dir" ]; then
            mkdir -p "$dir"
            echo -e "  ${GREEN}✓${NC} Created: $dir"
        else
            echo -e "  ${GREEN}✓${NC} Exists:  $dir"
        fi
    done
    
    echo ""
}

# ============================================================
# SET PERMISSIONS
# ============================================================
set_permissions() {
    echo -e "${BOLD}${BLUE}[6/6] Setting Permissions...${NC}"
    echo ""
    
    # Make scripts executable
    SCRIPTS=(
        "$PROJECT_DIR/android_client/build_apk.sh"
        "$TOOLS_DIR/setup.sh"
    )
    
    for script in "${SCRIPTS[@]}"; do
        if [ -f "$script" ]; then
            chmod +x "$script"
            echo -e "  ${GREEN}✓${NC} Executable: $script"
        fi
    done
    
    # Make Python files readable
    find "$SERVER_DIR" -name "*.py" -exec chmod 644 {} \; 2>/dev/null || true
    find "$SERVER_DIR" -name "*.py" -path "*/__init__.py" -exec chmod 644 {} \; 2>/dev/null || true
    
    echo -e "  ${GREEN}✓${NC} Permissions set"
    
    echo ""
}

# ============================================================
# VERIFY INSTALLATION
# ============================================================
verify_installation() {
    echo -e "${BOLD}${BLUE}Verifying Installation...${NC}"
    echo ""
    
    CHECKS=(
        "Python:$SERVER_DIR/app.py"
        "Config:$SERVER_DIR/config.py"
        "TCP Server:$SERVER_DIR/core/tcp_server.py"
        "Device Manager:$SERVER_DIR/core/device_manager.py"
        "Command Dispatcher:$SERVER_DIR/core/command_dispatcher.py"
        "Socket Manager:$SERVER_DIR/core/socket_manager.py"
        "Modules:$SERVER_DIR/modules/__init__.py"
        "APK Builder:$SERVER_DIR/apk_builder/builder.py"
        "Dashboard:$SERVER_DIR/templates/index.html"
        "CSS:$SERVER_DIR/static/css/dashboard.css"
        "JS Dashboard:$SERVER_DIR/static/js/dashboard.js"
        "JS Terminal:$SERVER_DIR/static/js/terminal.js"
    )
    
    ALL_OK=true
    
    for check in "${CHECKS[@]}"; do
        NAME="${check%%:*}"
        PATH="${check##*:}"
        
        if [ -f "$PATH" ]; then
            echo -e "  ${GREEN}✓${NC} $NAME"
        else
            echo -e "  ${RED}✗${NC} $NAME (missing: $PATH)"
            ALL_OK=false
        fi
    done
    
    echo ""
    
    if [ "$ALL_OK" = true ]; then
        echo -e "  ${GREEN}✅ All components verified!${NC}"
    else
        echo -e "  ${YELLOW}⚠ Some components are missing${NC}"
    fi
    
    echo ""
}

# ============================================================
# PRINT NEXT STEPS
# ============================================================
print_next_steps() {
    echo -e "${BOLD}${PURPLE}             SETUP COMPLETE! 🎉                          ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo -e "${BOLD}${PURPLE}  Next Steps:                                             ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo -e "${BOLD}${PURPLE}  1. Start the server:                                    ${NC}"
    echo -e "${BOLD}${PURPLE}     cd server && python app.py                           ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo -e "${BOLD}${PURPLE}  2. Open dashboard:                                      ${NC}"
    echo -e "${BOLD}${PURPLE}     http://localhost:5000                                ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo -e "${BOLD}${PURPLE}  3. Build Android APK:                                   ${NC}"
    echo -e "${BOLD}${PURPLE}     cd android_client && ./build_apk.sh                  ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo -e "${BOLD}${PURPLE}  4. Install APK on target device                         ${NC}"
    echo -e "${BOLD}${PURPLE}                                                          ${NC}"
    echo ""
}

# ============================================================
# MAIN
# ============================================================
main() {
    print_banner
    
    # Parse arguments
    parse_args "$@"
    
    echo -e "${CYAN}Starting ATHEX DLP Enterprise setup...${NC}"
    echo -e "${CYAN}Project Directory: ${PROJECT_DIR}${NC}"
    echo ""
    
    # Run setup steps
    check_prerequisites
    install_python_deps
    download_tools
    generate_keystore
    create_directories
    set_permissions
    verify_installation
    print_next_steps
    
    echo -e "${GREEN}✅ Setup completed successfully!${NC}"
    echo ""
}

# ============================================================
# RUN MAIN
# ============================================================
main "$@"