"""
ATHEX DLP Enterprise - Apps Module
===================================
Processes installed apps data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class AppsHandler:
    """Handles installed apps data processing"""
    
    # Suspicious app patterns
    SUSPICIOUS_PATTERNS = [
        "spy", "track", "monitor", "keylog", "record",
        "hidden", "stealth", "invisible", "clone",
        "fake", "phish", "malware", "trojan", "virus"
    ]
    
    def __init__(self):
        self.cached_apps: Dict[str, List[Dict]] = {}
        logger.info("AppsHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process installed apps data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw apps data
            
        Returns:
            Processed apps data
        """
        try:
            if isinstance(raw_data, str):
                apps = json.loads(raw_data)
            else:
                apps = raw_data
            
            if not isinstance(apps, list):
                apps = [apps]
            
            processed = []
            system_count = 0
            user_count = 0
            suspicious = []
            
            for app in apps:
                proc = self._process_app(app)
                processed.append(proc)
                
                if proc.get("is_system"):
                    system_count += 1
                else:
                    user_count += 1
                
                # Check for suspicious apps
                if self._is_suspicious(proc):
                    suspicious.append(proc)
            
            # Sort by name
            processed.sort(key=lambda x: x.get("app_name", "").lower())
            
            self.cached_apps[session_id] = processed
            
            logger.info(f"📱 Processed {len(processed)} apps ({system_count} system, "
                       f"{user_count} user, {len(suspicious)} suspicious) from {session_id}")
            
            return {
                "success": True,
                "session_id": session_id,
                "apps": processed,
                "count": len(processed),
                "stats": {
                    "system": system_count,
                    "user": user_count,
                    "suspicious": len(suspicious)
                },
                "suspicious": suspicious,
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error processing apps: {e}")
            return {"success": False, "error": str(e)}
    
    def _process_app(self, app: Dict) -> Dict:
        """Process individual app"""
        return {
            "package_name": app.get("package_name", ""),
            "app_name": app.get("app_name", "Unknown"),
            "category": app.get("category", "user"),
            "is_system": app.get("is_system", False),
            "version_name": app.get("version_name", ""),
            "version_code": app.get("version_code", 0),
            "target_sdk": app.get("target_sdk_version", 0),
            "apk_size": app.get("apk_size_formatted", "Unknown"),
            "data_size": app.get("data_size_formatted", "Unknown"),
            "first_install": app.get("first_install_formatted", ""),
            "last_update": app.get("last_update_formatted", ""),
            "permissions": app.get("permission_count", 0),
            "enabled": app.get("enabled", True),
            "flags": {
                "debuggable": app.get("flags", {}).get("FLAG_DEBUGGABLE", False),
                "allow_backup": app.get("flags", {}).get("FLAG_ALLOW_BACKUP", True),
                "large_heap": app.get("flags", {}).get("FLAG_LARGE_HEAP", False)
            }
        }
    
    def _is_suspicious(self, app: Dict) -> bool:
        """Check if app is suspicious"""
        name = app.get("app_name", "").lower()
        package = app.get("package_name", "").lower()
        
        for pattern in self.SUSPICIOUS_PATTERNS:
            if pattern in name or pattern in package:
                return True
        
        return False
    
    def filter_by_category(self, session_id: str, category: str) -> List[Dict]:
        """Filter apps by category"""
        apps = self.cached_apps.get(session_id, [])
        
        if category == "system":
            return [a for a in apps if a.get("is_system")]
        elif category == "user":
            return [a for a in apps if not a.get("is_system")]
        elif category == "suspicious":
            return [a for a in apps if self._is_suspicious(a)]
        
        return apps
    
    def search(self, session_id: str, query: str) -> List[Dict]:
        """Search apps by name or package"""
        apps = self.cached_apps.get(session_id, [])
        query = query.lower()
        
        return [a for a in apps 
                if query in a.get("app_name", "").lower() 
                or query in a.get("package_name", "").lower()]
    
    def get_high_risk_permissions(self, session_id: str) -> List[Dict]:
        """Find apps with high-risk permissions"""
        apps = self.cached_apps.get(session_id, [])
        
        high_risk = ["CAMERA", "RECORD_AUDIO", "READ_SMS", "READ_CONTACTS", 
                     "ACCESS_FINE_LOCATION", "READ_CALL_LOG"]
        
        risky_apps = []
        for app in apps:
            # This would need actual permission data
            pass
        
        return risky_apps
    
    def format_for_dashboard(self, apps: List[Dict]) -> List[Dict]:
        """Format apps for dashboard display"""
        dashboard = []
        
        for app in apps[:50]:
            dashboard.append({
                "name": app.get("app_name", "Unknown"),
                "package": app.get("package_name", ""),
                "category": "System" if app.get("is_system") else "User",
                "size": app.get("apk_size", "Unknown"),
                "version": app.get("version_name", ""),
                "suspicious": self._is_suspicious(app)
            })
        
        return dashboard


apps_handler = AppsHandler()