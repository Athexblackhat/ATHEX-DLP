"""
ATHEX DLP Enterprise - Notifications Module
============================================
Processes notification data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class NotificationsHandler:
    """Handles notification data processing"""
    
    def __init__(self):
        self.notification_queue: Dict[str, List[Dict]] = {}
        self.notification_history: Dict[str, List[Dict]] = {}
        self.max_history = 500
        self.max_queue = 200
        logger.info("NotificationsHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process notification from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw notification data (dict with app_name, title, body)
            
        Returns:
            Processed notification
        """
        try:
            if isinstance(raw_data, str):
                notification = json.loads(raw_data)
            else:
                notification = raw_data
            
            if not isinstance(notification, dict):
                notification = {"raw": str(notification)}
            
            # Process notification
            processed = {
                "session_id": session_id,
                "id": notification.get("id", datetime.now().timestamp()),
                "app_name": notification.get("app_name", "Unknown App"),
                "package_name": notification.get("package_name", ""),
                "title": notification.get("title", ""),
                "body": notification.get("body", ""),
                "timestamp": notification.get("timestamp", datetime.now().isoformat()),
                "received_at": datetime.now().isoformat(),
                "category": notification.get("category", "general"),
                "priority": notification.get("priority", 0),
                "device_model": notification.get("device_model", "Unknown")
            }
            
            # Add to queue
            if session_id not in self.notification_queue:
                self.notification_queue[session_id] = []
            
            self.notification_queue[session_id].append(processed)
            
            # Trim queue
            if len(self.notification_queue[session_id]) > self.max_queue:
                self.notification_queue[session_id] = \
                    self.notification_queue[session_id][-self.max_queue:]
            
            # Add to history
            if session_id not in self.notification_history:
                self.notification_history[session_id] = []
            
            self.notification_history[session_id].append(processed)
            
            # Trim history
            if len(self.notification_history[session_id]) > self.max_history:
                self.notification_history[session_id] = \
                    self.notification_history[session_id][-self.max_history:]
            
            logger.debug(f"🔔 Notification: {processed['app_name']} - {processed['title'][:50]}")
            
            return {
                "success": True,
                "notification": processed,
                "queue_size": len(self.notification_queue.get(session_id, []))
            }
            
        except Exception as e:
            logger.error(f"Error processing notification: {e}")
            return {"success": False, "error": str(e)}
    
    def get_pending(self, session_id: str, max_count: int = 50) -> List[Dict]:
        """Get pending notifications for a device"""
        queue = self.notification_queue.get(session_id, [])
        pending = queue[:max_count]
        
        # Clear retrieved notifications
        if session_id in self.notification_queue:
            self.notification_queue[session_id] = queue[max_count:]
        
        return pending
    
    def get_history(self, session_id: str, limit: int = 50) -> List[Dict]:
        """Get notification history for a device"""
        history = self.notification_history.get(session_id, [])
        return history[-limit:]
    
    def get_stats(self, session_id: str) -> Dict:
        """Get notification statistics"""
        history = self.notification_history.get(session_id, [])
        
        # Count by app
        app_counts = {}
        for notif in history:
            app = notif.get("app_name", "Unknown")
            app_counts[app] = app_counts.get(app, 0) + 1
        
        # Top apps
        top_apps = sorted(app_counts.items(), key=lambda x: x[1], reverse=True)[:10]
        
        return {
            "total": len(history),
            "pending": len(self.notification_queue.get(session_id, [])),
            "top_apps": [{"app": app, "count": count} for app, count in top_apps]
        }
    
    def format_for_dashboard(self, notifications: List[Dict]) -> List[Dict]:
        """Format notifications for dashboard terminal display"""
        dashboard = []
        
        for notif in notifications[:20]:
            dashboard.append({
                "time": notif.get("timestamp", "")[-8:] if notif.get("timestamp") else "",
                "app": notif.get("app_name", "Unknown"),
                "title": notif.get("title", "")[:50],
                "body": notif.get("body", "")[:100],
                "color": self._get_app_color(notif.get("app_name", ""))
            })
        
        return dashboard
    
    def _get_app_color(self, app_name: str) -> str:
        """Get color for app in terminal"""
        colors = {
            "WhatsApp": "#25D366",
            "Telegram": "#0088cc",
            "Instagram": "#E4405F",
            "Facebook": "#1877F2",
            "Gmail": "#EA4335",
            "YouTube": "#FF0000",
            "Twitter": "#1DA1F2",
            "Snapchat": "#FFFC00",
            "Messages": "#00f0ff",
        }
        return colors.get(app_name, "#00f0ff")


notifications_handler = NotificationsHandler()