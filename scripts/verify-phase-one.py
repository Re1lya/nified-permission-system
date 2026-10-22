"""Compatibility entry point for the current live acceptance command.
The historical loopback-only scenario targeted the removed platform application POST.
Use the current signed OA workflow and explicit deployment configuration instead.
"""
import runpy
from pathlib import Path
if __name__=='__main__':
    runpy.run_path(str(Path(__file__).with_name('verify-cloud-deployment.py')),run_name='__main__')
