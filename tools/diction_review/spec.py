"""Prints what the app sends the engine for a voice folder: spec.py <voice folder>"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure import spec
sys.stdout.write(spec(sys.argv[1]))
