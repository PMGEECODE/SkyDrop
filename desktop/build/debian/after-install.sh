#!/bin/bash

# Update desktop and mime databases
if hash update-mime-database 2>/dev/null; then
    update-mime-database /usr/share/mime || true
fi

if hash update-desktop-database 2>/dev/null; then
    update-desktop-database /usr/share/applications || true
fi

# Terminate any old running instances so the newly installed version launches fresh
pkill -f '/opt/SkyDrop/skydrop-desktop' 2>/dev/null || true
pkill -f 'skydrop-daemon' 2>/dev/null || true

exit 0
