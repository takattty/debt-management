#!/usr/bin/env python3
"""Refuse to validate against a server left over from another source state."""
import socket
import sys

sockets = []
try:
    for value in sys.argv[1:]:
        server = socket.socket()
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(('127.0.0.1', int(value)))
        except OSError:
            sys.exit(f'Port {value} is occupied. Stop that server before verifying this commit.')
        sockets.append(server)
finally:
    for server in sockets:
        server.close()
