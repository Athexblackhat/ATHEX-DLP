"""
ATHEX DLP Enterprise - Location Module
=======================================
Processes GPS location data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any, Optional

logger = logging.getLogger(__name__)


class LocationHandler:
    """Handles location data processing"""
    
    def __init__(self):
        self.location_history: Dict[str, List[Dict]] = {}
        self.current_locations: Dict[str, Dict] = {}
        self.max_history = 1000
        logger.info("LocationHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process location data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw location data (JSON string or dict)
            
        Returns:
            Processed location data
        """
        try:
            if isinstance(raw_data, str):
                location = json.loads(raw_data)
            else:
                location = raw_data
            
            if not isinstance(location, dict):
                return {"success": False, "error": "Invalid location data"}
            
            # Process location
            processed = {
                "session_id": session_id,
                "latitude": location.get("latitude"),
                "longitude": location.get("longitude"),
                "altitude": location.get("altitude", 0),
                "accuracy": location.get("accuracy", 0),
                "speed": location.get("speed", 0),
                "bearing": location.get("bearing", 0),
                "provider": location.get("provider", "unknown"),
                "timestamp": datetime.now().isoformat(),
                "address": location.get("address", None),
                "is_mock": location.get("is_mock", False)
            }
            
            # Add precision level
            accuracy = processed["accuracy"]
            if accuracy <= 10:
                processed["precision"] = "excellent"
            elif accuracy <= 50:
                processed["precision"] = "good"
            elif accuracy <= 200:
                processed["precision"] = "moderate"
            else:
                processed["precision"] = "poor"
            
            # Store current location
            self.current_locations[session_id] = processed
            
            # Add to history
            if session_id not in self.location_history:
                self.location_history[session_id] = []
            
            self.location_history[session_id].append(processed)
            
            # Trim history
            if len(self.location_history[session_id]) > self.max_history:
                self.location_history[session_id] = \
                    self.location_history[session_id][-self.max_history:]
            
            logger.info(f"📍 Location update: {processed['latitude']}, {processed['longitude']} "
                       f"(±{accuracy}m) from {session_id}")
            
            return {
                "success": True,
                "location": processed
            }
            
        except Exception as e:
            logger.error(f"Error processing location: {e}")
            return {"success": False, "error": str(e)}
    
    def get_current(self, session_id: str) -> Optional[Dict]:
        """Get current location for a device"""
        return self.current_locations.get(session_id)
    
    def get_all_current(self) -> Dict[str, Dict]:
        """Get current locations for all devices"""
        return self.current_locations.copy()
    
    def get_history(self, session_id: str, limit: int = 50) -> List[Dict]:
        """Get location history for a device"""
        history = self.location_history.get(session_id, [])
        return history[-limit:]
    
    def get_route(self, session_id: str, 
                  start_time: datetime = None, 
                  end_time: datetime = None) -> List[Dict]:
        """Get location route between times"""
        history = self.location_history.get(session_id, [])
        
        route = []
        for loc in history:
            loc_time = datetime.fromisoformat(loc["timestamp"])
            
            if start_time and loc_time < start_time:
                continue
            if end_time and loc_time > end_time:
                continue
            
            route.append({
                "lat": loc["latitude"],
                "lng": loc["longitude"],
                "time": loc["timestamp"]
            })
        
        return route
    
    def format_for_dashboard(self, session_id: str = None) -> List[Dict]:
        """Format locations for dashboard map display"""
        locations = []
        
        if session_id:
            current = self.get_current(session_id)
            if current:
                locations.append({
                    "session_id": session_id,
                    "lat": current["latitude"],
                    "lng": current["longitude"],
                    "accuracy": current["accuracy"],
                    "precision": current["precision"],
                    "updated": current["timestamp"]
                })
        else:
            for sid, loc in self.current_locations.items():
                locations.append({
                    "session_id": sid,
                    "lat": loc["latitude"],
                    "lng": loc["longitude"],
                    "accuracy": loc["accuracy"],
                    "precision": loc["precision"],
                    "updated": loc["timestamp"]
                })
        
        return locations
    
    def calculate_distance(self, lat1: float, lon1: float, 
                           lat2: float, lon2: float) -> float:
        """Calculate distance between two coordinates in meters (Haversine)"""
        import math
        
        R = 6371000  # Earth radius in meters
        
        phi1 = math.radians(lat1)
        phi2 = math.radians(lat2)
        delta_phi = math.radians(lat2 - lat1)
        delta_lambda = math.radians(lon2 - lon1)
        
        a = math.sin(delta_phi/2)**2 + \
            math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda/2)**2
        c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
        
        return R * c


location_handler = LocationHandler()