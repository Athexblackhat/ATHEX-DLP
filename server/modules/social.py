"""
ATHEX DLP Enterprise - Social Media Module
===========================================
Processes social media data from Android devices.
(WhatsApp, Telegram, Instagram, Facebook, Signal)
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class SocialHandler:
    """Handles social media data processing"""
    
    def __init__(self):
        self.social_data: Dict[str, Dict] = {}
        logger.info("SocialHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process social media data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Social media data dictionary
            
        Returns:
            Processed social media data
        """
        try:
            if isinstance(raw_data, str):
                data = json.loads(raw_data)
            else:
                data = raw_data
            
            if not isinstance(data, dict):
                return {"success": False, "error": "Invalid social media data"}
            
            processed = {
                "session_id": session_id,
                "apps_found": data.get("apps_found", 0),
                "apps": {},
                "total_messages": 0,
                "total_contacts": 0,
                "total_media": 0,
                "timestamp": datetime.now().isoformat()
            }
            
            # Process WhatsApp
            if "whatsapp" in data:
                processed["apps"]["whatsapp"] = self._process_whatsapp(data["whatsapp"])
                processed["total_messages"] += processed["apps"]["whatsapp"]["message_count"]
                processed["total_contacts"] += processed["apps"]["whatsapp"]["contact_count"]
                processed["total_media"] += processed["apps"]["whatsapp"]["media_count"]
            
            # Process Telegram
            if "telegram" in data:
                processed["apps"]["telegram"] = self._process_telegram(data["telegram"])
                processed["total_messages"] += processed["apps"]["telegram"]["message_count"]
                processed["total_contacts"] += processed["apps"]["telegram"]["chat_count"]
            
            # Process Instagram
            if "instagram" in data:
                processed["apps"]["instagram"] = self._process_instagram(data["instagram"])
                processed["total_messages"] += processed["apps"]["instagram"]["message_count"]
            
            # Process Facebook
            if "facebook_messenger" in data:
                processed["apps"]["facebook"] = self._process_facebook(data["facebook_messenger"])
                processed["total_messages"] += processed["apps"]["facebook"]["message_count"]
            
            # Process Signal
            if "signal" in data:
                processed["apps"]["signal"] = self._process_signal(data["signal"])
                processed["total_messages"] += processed["apps"]["signal"]["message_count"]
            
            self.social_data[session_id] = processed
            
            logger.info(f"💬 Social data: {processed['apps_found']} apps, "
                       f"{processed['total_messages']} messages from {session_id}")
            
            return {
                "success": True,
                "data": processed
            }
            
        except Exception as e:
            logger.error(f"Error processing social data: {e}")
            return {"success": False, "error": str(e)}
    
    def _process_whatsapp(self, data: Dict) -> Dict:
        """Process WhatsApp data"""
        messages = data.get("messages", [])
        contacts = data.get("contacts", [])
        
        # Get unique chats
        chats = {}
        for msg in messages:
            chat_id = msg.get("chat_id", "unknown")
            if chat_id not in chats:
                chats[chat_id] = {
                    "chat_id": chat_id,
                    "contact_name": msg.get("contact_name", ""),
                    "message_count": 0,
                    "last_message": None
                }
            chats[chat_id]["message_count"] += 1
            if not chats[chat_id]["last_message"] or \
               msg.get("timestamp", 0) > chats[chat_id]["last_message"].get("timestamp", 0):
                chats[chat_id]["last_message"] = msg
        
        return {
            "message_count": len(messages),
            "contact_count": len(contacts),
            "media_count": data.get("media_count", 0),
            "chat_count": len(chats),
            "chats": list(chats.values()),
            "messages_preview": messages[:50],
            "contacts_preview": contacts[:20]
        }
    
    def _process_telegram(self, data: Dict) -> Dict:
        """Process Telegram data"""
        messages = data.get("messages", [])
        chats = data.get("chats", [])
        
        return {
            "message_count": len(messages),
            "chat_count": len(chats),
            "messages_preview": messages[:50],
            "chats_preview": chats[:20]
        }
    
    def _process_instagram(self, data: Dict) -> Dict:
        """Process Instagram data"""
        messages = data.get("messages", [])
        
        return {
            "message_count": len(messages),
            "messages_preview": messages[:50]
        }
    
    def _process_facebook(self, data: Dict) -> Dict:
        """Process Facebook Messenger data"""
        messages = data.get("messages", [])
        
        return {
            "message_count": len(messages),
            "messages_preview": messages[:50]
        }
    
    def _process_signal(self, data: Dict) -> Dict:
        """Process Signal data"""
        messages = data.get("messages", [])
        
        return {
            "message_count": len(messages),
            "messages_preview": messages[:50]
        }
    
    def get_chat_messages(self, session_id: str, app: str, chat_id: str) -> list:
        """Get messages for a specific chat"""
        data = self.social_data.get(session_id, {})
        app_data = data.get("apps", {}).get(app, {})
        
        messages = app_data.get("messages_preview", [])
        return [m for m in messages if m.get("chat_id") == chat_id or m.get("dialog_id") == chat_id]
    
    def get_contacts(self, session_id: str, app: str = "whatsapp") -> list:
        """Get contacts for an app"""
        data = self.social_data.get(session_id, {})
        app_data = data.get("apps", {}).get(app, {})
        return app_data.get("contacts_preview", [])
    
    def format_for_dashboard(self, data: Dict) -> Dict:
        """Format social data for dashboard"""
        dashboard = {"apps": {}}
        
        for app_name, app_data in data.get("apps", {}).items():
            dashboard["apps"][app_name] = {
                "messages": app_data.get("message_count", 0),
                "contacts": app_data.get("contact_count", app_data.get("chat_count", 0)),
                "media": app_data.get("media_count", 0),
                "icon": self._get_app_icon(app_name)
            }
        
        return dashboard
    
    def _get_app_icon(self, app_name: str) -> str:
        icons = {
            "whatsapp": "💬",
            "telegram": "✈️",
            "instagram": "📷",
            "facebook": "📘",
            "signal": "🔒"
        }
        return icons.get(app_name, "💬")


social_handler = SocialHandler()