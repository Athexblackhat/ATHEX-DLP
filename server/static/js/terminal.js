/**
 * ATHEX DLP Enterprise - Terminal JavaScript
 * ============================================
 * Advanced terminal emulator for real-time notification display,
 * command execution, and system logging.
 */

// ============================================================
// TERMINAL MANAGER CLASS
// ============================================================
class TerminalManager {
    constructor(options = {}) {
        this.containerId = options.containerId || 'terminalBody';
        this.maxLines = options.maxLines || 1000;
        this.autoScroll = options.autoScroll !== false;
        this.paused = false;
        this.lineCount = 0;
        this.filters = {
            system: true,
            notification: true,
            error: true,
            warning: true,
            success: true,
            info: true,
            command: true
        };
        this.searchQuery = '';
        this.searchResults = [];
        this.searchIndex = -1;
        this.commandHistory = [];
        this.historyIndex = -1;
        this.currentCommand = '';
        
        this.container = document.getElementById(this.containerId);
        this.inputLine = null;
        this.commandMode = false;
        
        this._init();
    }
    
    _init() {
        if (!this.container) {
            console.error('Terminal container not found:', this.containerId);
            return;
        }
        
        // Add command input line
        this._createCommandInput();
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => this._handleKeyboard(e));
        
        // Welcome message
        this.addLine('system', '╔══════════════════════════════════════╗');
        this.addLine('system', '║     ATHEX DLP Enterprise v2.0.0     ║');
        this.addLine('system', '║     Secure Terminal Console         ║');
        this.addLine('system', '╚══════════════════════════════════════╝');
        this.addLine('info', 'Type "help" for available commands');
        this.addLine('info', 'Waiting for device connections...');
    }
    
    _createCommandInput() {
        const inputContainer = document.createElement('div');
        inputContainer.className = 'terminal-input-container';
        inputContainer.style.cssText = `
            display: flex;
            align-items: center;
            padding: 8px 0;
            border-top: 1px solid rgba(0, 240, 255, 0.1);
            margin-top: 8px;
        `;
        
        const prompt = document.createElement('span');
        prompt.className = 'terminal-prompt';
        prompt.textContent = 'athex> ';
        prompt.style.cssText = `
            color: #00ff41;
            font-weight: bold;
            margin-right: 8px;
            font-family: var(--font-mono);
            font-size: 13px;
        `;
        
        this.inputLine = document.createElement('input');
        this.inputLine.type = 'text';
        this.inputLine.className = 'terminal-input';
        this.inputLine.placeholder = 'Type command...';
        this.inputLine.style.cssText = `
            flex: 1;
            background: transparent;
            border: none;
            color: #00ff41;
            font-family: var(--font-mono);
            font-size: 13px;
            outline: none;
            caret-color: #00ff41;
        `;
        
        this.inputLine.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                this._executeCommand(this.inputLine.value);
                this.inputLine.value = '';
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                this._navigateHistory(-1);
            } else if (e.key === 'ArrowDown') {
                e.preventDefault();
                this._navigateHistory(1);
            } else if (e.key === 'Escape') {
                this.inputLine.value = '';
                this.historyIndex = -1;
            }
        });
        
        inputContainer.appendChild(prompt);
        inputContainer.appendChild(this.inputLine);
        this.container.appendChild(inputContainer);
    }
    
    // ============================================================
    // LINE MANAGEMENT
    // ============================================================
    
    addLine(type, message, data = null) {
        if (this.paused) return;
        if (!this.filters[type]) return;
        
        // Apply search filter
        if (this.searchQuery && !message.toLowerCase().includes(this.searchQuery.toLowerCase())) {
            return;
        }
        
        const line = document.createElement('div');
        line.className = `terminal-line ${type}`;
        line.dataset.type = type;
        line.dataset.timestamp = Date.now();
        
        // Timestamp
        const time = new Date().toLocaleTimeString('en-US', { hour12: false });
        const timestamp = document.createElement('span');
        timestamp.className = 'terminal-timestamp';
        timestamp.textContent = `[${time}]`;
        timestamp.style.cssText = `
            color: #555;
            margin-right: 8px;
            font-size: 11px;
        `;
        line.appendChild(timestamp);
        
        // Icon based on type
        const icon = this._getTypeIcon(type);
        if (icon) {
            const iconSpan = document.createElement('span');
            iconSpan.className = 'terminal-icon';
            iconSpan.textContent = icon;
            iconSpan.style.marginRight = '6px';
            line.appendChild(iconSpan);
        }
        
        // Message content
        const content = document.createElement('span');
        content.className = 'terminal-content';
        
        // Highlight search matches
        if (this.searchQuery) {
            content.innerHTML = this._highlightSearch(message);
        } else {
            content.textContent = message;
        }
        
        line.appendChild(content);
        
        // Store data reference
        if (data) {
            line.dataset.payload = JSON.stringify(data);
            line.style.cursor = 'pointer';
            line.title = 'Click to view details';
            line.addEventListener('click', () => this._showLineDetails(data));
        }
        
        // Insert before input line
        const inputContainer = this.container.querySelector('.terminal-input-container');
        if (inputContainer) {
            this.container.insertBefore(line, inputContainer);
        } else {
            this.container.appendChild(line);
        }
        
        this.lineCount++;
        
        // Trim old lines
        while (this.container.querySelectorAll('.terminal-line').length > this.maxLines) {
            const oldestLine = this.container.querySelector('.terminal-line');
            if (oldestLine) oldestLine.remove();
        }
        
        // Auto scroll
        if (this.autoScroll) {
            this.scrollToBottom();
        }
        
        return line;
    }
    
    addNotification(notification) {
        const { app_name, title, body } = notification;
        const message = `${app_name} | ${title}${body ? ' - ' + body.substring(0, 80) : ''}`;
        return this.addLine('notification', message, notification);
    }
    
    addCommand(command, result) {
        this.addLine('command', `> ${command}`);
        if (result) {
            this.addLine('info', `  ${result}`);
        }
    }
    
    addError(error) {
        this.addLine('error', `❌ ${error}`);
    }
    
    addSuccess(message) {
        this.addLine('success', `✅ ${message}`);
    }
    
    addWarning(message) {
        this.addLine('warning', `⚠️ ${message}`);
    }
    
    // ============================================================
    // COMMAND EXECUTION
    // ============================================================
    
    _executeCommand(input) {
        const command = input.trim();
        if (!command) return;
        
        // Add to history
        this.commandHistory.push(command);
        this.historyIndex = this.commandHistory.length;
        
        // Echo command
        this.addLine('command', `> ${command}`);
        
        const args = command.split(/\s+/);
        const cmd = args[0].toLowerCase();
        
        switch (cmd) {
            case 'help':
                this._showHelp();
                break;
            case 'clear':
            case 'cls':
                this.clear();
                break;
            case 'devices':
            case 'ls':
                this._listDevices();
                break;
            case 'status':
                this._showStatus();
                break;
            case 'pause':
                this.paused = true;
                this.addLine('warning', 'Terminal output paused');
                break;
            case 'resume':
                this.paused = false;
                this.addLine('success', 'Terminal output resumed');
                break;
            case 'filter':
                this._toggleFilter(args[1]);
                break;
            case 'search':
                this._search(args.slice(1).join(' '));
                break;
            case 'export':
                this._exportLog();
                break;
            case 'scroll':
                this.autoScroll = !this.autoScroll;
                this.addLine('info', `Auto-scroll: ${this.autoScroll ? 'ON' : 'OFF'}`);
                break;
            case 'stats':
                this._showStats();
                break;
            case 'version':
                this.addLine('info', 'ATHEX DLP Enterprise v2.0.0');
                break;
            default:
                // Try to execute as server command
                this._sendServerCommand(command);
                break;
        }
    }
    
    _showHelp() {
        const help = [
            '╔══════════════════════════════════════════════╗',
            '║           AVAILABLE COMMANDS                 ║',
            '╠══════════════════════════════════════════════╣',
            '║  help        - Show this help                ║',
            '║  clear/cls   - Clear terminal                ║',
            '║  devices/ls  - List connected devices        ║',
            '║  status      - Show system status            ║',
            '║  pause       - Pause terminal output         ║',
            '║  resume      - Resume terminal output        ║',
            '║  filter <t>  - Toggle filter (sys/notif/err) ║',
            '║  search <q>  - Search in terminal            ║',
            '║  scroll      - Toggle auto-scroll            ║',
            '║  stats       - Show terminal statistics      ║',
            '║  export      - Export terminal log           ║',
            '║  version     - Show version                  ║',
            '╚══════════════════════════════════════════════╝',
        ];
        help.forEach(line => this.addLine('system', line));
    }
    
    _listDevices() {
        fetch('/api/devices')
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    const online = data.online || [];
                    const offline = data.offline || [];
                    
                    this.addLine('info', `━━━ Connected Devices: ${online.length} online, ${offline.length} offline ━━━`);
                    
                    online.forEach(device => {
                        this.addLine('success', `  🟢 ${device.model} | ${device.android_version} | ${device.ip_address}`);
                    });
                    
                    offline.forEach(device => {
                        this.addLine('error', `  🔴 ${device.model} | ${device.ip_address} (Offline)`);
                    });
                }
            })
            .catch(err => this.addError('Failed to fetch devices: ' + err.message));
    }
    
    _showStatus() {
        fetch('/api/system/stats')
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    const stats = data.stats;
                    this.addLine('info', '━━━ System Status ━━━');
                    this.addLine('info', `  Version:     ${stats.version || 'N/A'}`);
                    this.addLine('info', `  Devices:     ${stats.devices?.online || 0} online / ${stats.devices?.total || 0} total`);
                    this.addLine('info', `  Commands:    ${stats.commands?.total_commands || 0} total`);
                    this.addLine('info', `  WebSocket:   ${stats.websocket?.dashboard_clients || 0} clients`);
                }
            })
            .catch(err => this.addError('Failed to fetch status: ' + err.message));
    }
    
    _showStats() {
        this.addLine('info', '━━━ Terminal Statistics ━━━');
        this.addLine('info', `  Total lines:   ${this.lineCount}`);
        this.addLine('info', `  Max lines:     ${this.maxLines}`);
        this.addLine('info', `  Auto-scroll:   ${this.autoScroll ? 'ON' : 'OFF'}`);
        this.addLine('info', `  Paused:        ${this.paused ? 'YES' : 'NO'}`);
        this.addLine('info', `  Command hist:  ${this.commandHistory.length}`);
        this.addLine('info', `  Active filters: ${Object.entries(this.filters).filter(([k, v]) => v).map(([k]) => k).join(', ')}`);
    }
    
    _toggleFilter(type) {
        const filterMap = {
            'sys': 'system',
            'notif': 'notification',
            'err': 'error',
            'warn': 'warning',
            'ok': 'success',
            'info': 'info',
            'cmd': 'command',
            'all': null
        };
        
        const filterKey = filterMap[type];
        
        if (filterKey === null) {
            // Toggle all
            const allOn = Object.values(this.filters).every(v => v);
            Object.keys(this.filters).forEach(k => this.filters[k] = !allOn);
            this.addLine('info', `All filters: ${!allOn ? 'ON' : 'OFF'}`);
        } else if (filterKey) {
            this.filters[filterKey] = !this.filters[filterKey];
            this.addLine('info', `Filter '${filterKey}': ${this.filters[filterKey] ? 'ON' : 'OFF'}`);
        } else {
            this.addLine('warning', 'Unknown filter. Use: sys, notif, err, warn, ok, info, cmd, all');
        }
    }
    
    _search(query) {
        if (!query) {
            this.searchQuery = '';
            this.searchResults = [];
            this.searchIndex = -1;
            this.addLine('info', 'Search cleared');
            return;
        }
        
        this.searchQuery = query;
        this.searchResults = [];
        this.searchIndex = -1;
        
        const lines = this.container.querySelectorAll('.terminal-line');
        lines.forEach((line, index) => {
            const content = line.querySelector('.terminal-content');
            if (content && content.textContent.toLowerCase().includes(query.toLowerCase())) {
                this.searchResults.push(line);
            }
        });
        
        this.addLine('info', `Found ${this.searchResults.length} matches for "${query}"`);
        
        if (this.searchResults.length > 0) {
            this._navigateSearch(1);
        }
    }
    
    _navigateSearch(direction) {
        if (this.searchResults.length === 0) return;
        
        // Remove previous highlight
        this.searchResults.forEach(line => line.style.background = '');
        
        this.searchIndex += direction;
        
        if (this.searchIndex >= this.searchResults.length) {
            this.searchIndex = 0;
        } else if (this.searchIndex < 0) {
            this.searchIndex = this.searchResults.length - 1;
        }
        
        const current = this.searchResults[this.searchIndex];
        current.style.background = 'rgba(0, 240, 255, 0.15)';
        current.scrollIntoView({ behavior: 'smooth', block: 'center' });
        
        this.addLine('info', `Match ${this.searchIndex + 1}/${this.searchResults.length}`);
    }
    
    _highlightSearch(text) {
        if (!this.searchQuery) return text;
        
        const regex = new RegExp(`(${this.searchQuery.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi');
        return text.replace(regex, '<mark style="background: rgba(0,240,255,0.3); color: white; padding: 0 2px; border-radius: 2px;">$1</mark>');
    }
    
    _sendServerCommand(command) {
        this.addLine('warning', `Unknown command: ${command}`);
        this.addLine('info', 'Type "help" for available commands');
    }
    
    _exportLog() {
        const lines = this.container.querySelectorAll('.terminal-line');
        let logText = '';
        
        lines.forEach(line => {
            const time = line.querySelector('.terminal-timestamp')?.textContent || '';
            const content = line.querySelector('.terminal-content')?.textContent || '';
            logText += `${time} ${content}\n`;
        });
        
        const blob = new Blob([logText], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `athex-terminal-${new Date().toISOString().slice(0, 10)}.log`;
        a.click();
        URL.revokeObjectURL(url);
        
        this.addLine('success', 'Terminal log exported');
    }
    
    // ============================================================
    // KEYBOARD HANDLING
    // ============================================================
    
    _handleKeyboard(e) {
        // Ctrl+L = Clear
        if (e.ctrlKey && e.key === 'l') {
            e.preventDefault();
            this.clear();
        }
        
        // Ctrl+F = Focus search/input
        if (e.ctrlKey && e.key === 'f') {
            e.preventDefault();
            if (this.inputLine) {
                this.inputLine.focus();
                this.inputLine.value = 'search ';
            }
        }
        
        // Ctrl+S = Toggle scroll
        if (e.ctrlKey && e.key === 's') {
            e.preventDefault();
            this.autoScroll = !this.autoScroll;
        }
        
        // F3 = Next search result
        if (e.key === 'F3') {
            e.preventDefault();
            this._navigateSearch(e.shiftKey ? -1 : 1);
        }
    }
    
    _navigateHistory(direction) {
        if (this.commandHistory.length === 0) return;
        
        this.historyIndex += direction;
        
        if (this.historyIndex >= this.commandHistory.length) {
            this.historyIndex = this.commandHistory.length;
            this.inputLine.value = '';
        } else if (this.historyIndex < 0) {
            this.historyIndex = -1;
            this.inputLine.value = '';
        } else {
            this.inputLine.value = this.commandHistory[this.historyIndex];
        }
    }
    
    // ============================================================
    // DETAIL VIEW
    // ============================================================
    
    _showLineDetails(data) {
        if (!data) return;
        
        const modal = document.createElement('div');
        modal.className = 'terminal-detail-modal';
        modal.style.cssText = `
            position: fixed;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            background: #1a1f2e;
            border: 1px solid rgba(0, 240, 255, 0.3);
            border-radius: 12px;
            padding: 20px;
            z-index: 3000;
            max-width: 600px;
            width: 90%;
            max-height: 80vh;
            overflow-y: auto;
            box-shadow: 0 20px 60px rgba(0,0,0,0.8);
        `;
        
        const overlay = document.createElement('div');
        overlay.style.cssText = `
            position: fixed;
            inset: 0;
            background: rgba(0,0,0,0.6);
            z-index: 2999;
            cursor: pointer;
        `;
        overlay.addEventListener('click', () => {
            modal.remove();
            overlay.remove();
        });
        
        modal.innerHTML = `
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h3 style="color: #00f0ff; margin: 0;">📋 Details</h3>
                <button style="background: none; border: none; color: #fff; cursor: pointer; font-size: 18px;" 
                        onclick="this.closest('.terminal-detail-modal').remove(); 
                        document.querySelector('.terminal-detail-modal + div')?.remove();">✕</button>
            </div>
            <pre style="color: #94a3b8; font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; word-break: break-all;">${JSON.stringify(data, null, 2)}</pre>
        `;
        
        document.body.appendChild(overlay);
        document.body.appendChild(modal);
    }
    
    // ============================================================
    // UTILITY
    // ============================================================
    
    _getTypeIcon(type) {
        const icons = {
            system: '⚙️',
            notification: '🔔',
            error: '❌',
            warning: '⚠️',
            success: '✅',
            info: 'ℹ️',
            command: '>'
        };
        return icons[type] || '';
    }
    
    scrollToBottom() {
        const inputContainer = this.container.querySelector('.terminal-input-container');
        if (inputContainer) {
            inputContainer.scrollIntoView({ behavior: 'smooth', block: 'end' });
        } else {
            this.container.scrollTop = this.container.scrollHeight;
        }
    }
    
    clear() {
        const lines = this.container.querySelectorAll('.terminal-line');
        lines.forEach(line => line.remove());
        this.lineCount = 0;
        this.addLine('system', 'Terminal cleared');
    }
    
    pause() {
        this.paused = true;
        this.addLine('warning', 'Output paused');
    }
    
    resume() {
        this.paused = false;
        this.addLine('success', 'Output resumed');
    }
    
    setFilter(type, enabled) {
        if (this.filters.hasOwnProperty(type)) {
            this.filters[type] = enabled;
        }
    }
    
    getLineCount() {
        return this.lineCount;
    }
    
    getVisibleLines() {
        return this.container.querySelectorAll('.terminal-line').length;
    }
    
    destroy() {
        this.clear();
        const inputContainer = this.container.querySelector('.terminal-input-container');
        if (inputContainer) inputContainer.remove();
    }
}

// ============================================================
// GLOBAL TERMINAL INSTANCE
// ============================================================
let terminal = null;

document.addEventListener('DOMContentLoaded', () => {
    terminal = new TerminalManager({
        containerId: 'terminalBody',
        maxLines: 1000,
        autoScroll: true
    });
    
    // Expose to window for global access
    window.terminal = terminal;
    
    console.log('ATHEX Terminal initialized');
});

// ============================================================
// HELPER FUNCTIONS (exposed globally)
// ============================================================

function addTerminalLine(type, message, data) {
    if (terminal) {
        terminal.addLine(type, message, data);
    }
}

function clearTerminal() {
    if (terminal) {
        terminal.clear();
    }
}

function pauseTerminal() {
    if (terminal) terminal.pause();
}

function resumeTerminal() {
    if (terminal) terminal.resume();
}

function toggleAutoScroll() {
    if (terminal) {
        terminal.autoScroll = !terminal.autoScroll;
        terminal.addLine('info', `Auto-scroll: ${terminal.autoScroll ? 'ON' : 'OFF'}`);
    }
}

function exportTerminalLog() {
    if (terminal) terminal._exportLog();
}

function searchTerminal(query) {
    if (terminal) terminal._search(query);
}