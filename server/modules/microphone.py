"""
ATHEX DLP Enterprise - Microphone Module
=========================================
Processes audio recording data from Android devices.
"""

import json
import logging
import base64
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class MicrophoneHandler:
    """Handles microphone recording data"""
    
    def __init__(self):
        self.recordings: Dict[str, list] = {}
        self.audio_dir = Path("data/audio_recordings")
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.active_recordings: Dict[str, Dict] = {}
        logger.info("MicrophoneHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process audio recording from device.
        
        Args:
            session_id: Device session ID
            raw_data: Base64 encoded audio or dict with audio data
            
        Returns:
            Processed audio data
        """
        try:
            audio_base64 = None
            metadata = {}
            
            if isinstance(raw_data, str):
                audio_base64 = raw_data
            elif isinstance(raw_data, dict):
                audio_base64 = raw_data.get("audio", raw_data.get("data", ""))
                metadata = {
                    "duration": raw_data.get("duration", 0),
                    "format": raw_data.get("format", "aac"),
                    "sample_rate": raw_data.get("sample_rate", 44100),
                    "size": raw_data.get("size", 0)
                }
            
            if not audio_base64:
                return {"success": False, "error": "No audio data"}
            
            # Save audio file
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"recording_{session_id[:8]}_{timestamp}.aac"
            filepath = self.audio_dir / filename
            
            try:
                audio_bytes = base64.b64decode(audio_base64)
                with open(filepath, 'wb') as f:
                    f.write(audio_bytes)
                
                logger.info(f"🎤 Audio saved: {filename} ({len(audio_bytes)} bytes)")
                
            except Exception as e:
                logger.error(f"Failed to save audio: {e}")
                filepath = None
            
            # Store
            if session_id not in self.recordings:
                self.recordings[session_id] = []
            
            recording = {
                "session_id": session_id,
                "filename": filename,
                "path": str(filepath) if filepath else None,
                "size": len(audio_bytes) if audio_bytes else 0,
                "size_formatted": self._format_size(len(audio_bytes)) if audio_bytes else "0 B",
                "duration": metadata.get("duration", 0),
                "duration_formatted": self._format_duration(metadata.get("duration", 0)),
                "format": metadata.get("format", "aac"),
                "sample_rate": metadata.get("sample_rate", 44100),
                "timestamp": datetime.now().isoformat()
            }
            
            self.recordings[session_id].append(recording)
            
            if len(self.recordings[session_id]) > 30:
                self.recordings[session_id] = self.recordings[session_id][-30:]
            
            return {
                "success": True,
                "recording": recording
            }
            
        except Exception as e:
            logger.error(f"Error processing audio: {e}")
            return {"success": False, "error": str(e)}
    
    def start_recording(self, session_id: str, duration: int = 30) -> Dict:
        """Track active recording"""
        self.active_recordings[session_id] = {
            "started_at": datetime.now().isoformat(),
            "duration": duration,
            "status": "recording"
        }
        logger.info(f"🎤 Recording started for {session_id} ({duration}s)")
        return {"success": True, "message": f"Recording {duration}s"}
    
    def stop_recording(self, session_id: str) -> Dict:
        """Stop active recording"""
        if session_id in self.active_recordings:
            del self.active_recordings[session_id]
        return {"success": True, "message": "Recording stopped"}
    
    def get_recordings(self, session_id: str, limit: int = 10) -> list:
        """Get recent recordings"""
        recordings = self.recordings.get(session_id, [])
        return recordings[-limit:]
    
    def get_audio_file(self, filename: str) -> Optional[Path]:
        """Get audio file path"""
        filepath = self.audio_dir / filename
        if filepath.exists():
            return filepath
        return None
    
    def _format_size(self, size_bytes: int) -> str:
        for unit in ['B', 'KB', 'MB']:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} GB"
    
    def _format_duration(self, seconds: int) -> str:
        if seconds < 60:
            return f"{seconds}s"
        minutes = seconds // 60
        secs = seconds % 60
        return f"{minutes}m {secs}s"
    
    def format_for_dashboard(self, recordings: list) -> list:
        return [
            {
                "filename": r.get("filename", ""),
                "duration": r.get("duration_formatted", "0s"),
                "size": r.get("size_formatted", "0 B"),
                "time": r.get("timestamp", "")[-8:] if r.get("timestamp") else ""
            }
            for r in recordings
        ]


microphone_handler = MicrophoneHandler()