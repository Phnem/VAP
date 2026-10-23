"""Read-only loopback FTP/SFTP fixtures. Run with the ignored network-test-tools venv."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import threading
import time

import paramiko
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--directory', required=True)
parser.add_argument('--state', required=True)
args = parser.parse_args()
root = Path(args.directory).resolve()
state = Path(args.state).resolve()
state.mkdir(parents=True, exist_ok=True)
assert root.is_dir() and root.name == 'network-fixtures'
user, password = 'fixture', 'fixture-secret'


class Auth(paramiko.ServerInterface):
    def check_auth_password(self, username, supplied):
        return paramiko.AUTH_SUCCESSFUL if (username, supplied) == (user, password) else paramiko.AUTH_FAILED

    def get_allowed_auths(self, username):
        return 'password'

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED


class ReadOnlySftp(paramiko.SFTPServerInterface):
    @staticmethod
    def local(path):
        if path != '/media' and not path.startswith('/media/'):
            raise PermissionError(path)
        resolved = (root / path.removeprefix('/media').lstrip('/')).resolve()
        resolved.relative_to(root)
        return resolved

    def list_folder(self, path):
        try:
            result = []
            for item in self.local(path).iterdir():
                attr = paramiko.SFTPAttributes.from_stat(item.stat())
                attr.filename = item.name
                result.append(attr)
            return result
        except (OSError, ValueError):
            return paramiko.SFTP_PERMISSION_DENIED

    def stat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
        except (OSError, ValueError):
            return paramiko.SFTP_NO_SUCH_FILE

    lstat = stat

    def canonicalize(self, path):
        return path if path.startswith('/media') else '/media'

    def open(self, path, flags, attr):
        if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND):
            return paramiko.SFTP_PERMISSION_DENIED
        try:
            handle = paramiko.SFTPHandle(flags)
            handle.readfile = self.local(path).open('rb')
            handle.stat = lambda: paramiko.SFTPAttributes.from_stat(os.fstat(handle.readfile.fileno()))
            return handle
        except (OSError, ValueError):
            return paramiko.SFTP_NO_SUCH_FILE


key_path = state / 'host_key'
if key_path.exists():
    key = paramiko.RSAKey.from_private_key_file(str(key_path))
else:
    key = paramiko.RSAKey.generate(2048)
    key.write_private_key_file(str(key_path))
fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')


def serve_ssh():
    listener = socket.create_server(('127.0.0.1', 14422))
    def session(client):
        transport = paramiko.Transport(client)
        try:
            transport.add_server_key(key)
            transport.set_subsystem_handler('sftp', paramiko.SFTPServer, ReadOnlySftp)
            transport.start_server(server=Auth())
            while transport.is_active():
                time.sleep(.2)
        finally:
            transport.close()
    while True:
        client, _ = listener.accept()
        threading.Thread(target=session, args=(client,), daemon=True).start()


authorizer = DummyAuthorizer()
authorizer.add_user(user, password, str(root), perm='elr')
FTPHandler.authorizer = authorizer
FTPHandler.passive_ports = range(14460, 14470)
FTPHandler.masquerade_address = '127.0.0.1'
ftp = FTPServer(('127.0.0.1', 14421), FTPHandler)
threading.Thread(target=serve_ssh, daemon=True).start()
threading.Thread(target=ftp.serve_forever, daemon=True).start()
(state / 'server.json').write_text(json.dumps({'fingerprint': fingerprint, 'pid': os.getpid()}), encoding='utf-8')
print('VAP fixture servers bound to loopback; state ready', flush=True)
while True:
    time.sleep(1)
