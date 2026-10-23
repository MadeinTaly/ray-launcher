"""Live RAY smoke: RAY_HOST=TV_IP RAY_KEY=pairing-key python3 test/ray_smoke.py."""
import base64
import json
import os
import socket
import struct
import urllib.request
import urllib.error
import contextlib
import select
import time

HOST = os.environ['RAY_HOST']
KEY = os.environ['RAY_KEY']


def http(path, key=KEY):
    req = urllib.request.Request('http://' + HOST + ':8766' + path, headers={'X-Key': key})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def connect():
    sock = socket.create_connection((HOST, 8766), timeout=5)
    nonce = base64.b64encode(os.urandom(16)).decode()
    sock.sendall(('GET /ws HTTP/1.1\r\nHost: ' + HOST + ':8766\r\nUpgrade: websocket\r\n'
                  'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ' + nonce + '\r\n\r\n').encode())
    headers = b''
    while not headers.endswith(b'\r\n\r\n'):
        headers += exact(sock, 1)
    assert b'101 Switching Protocols' in headers, headers
    return sock


def exact(sock, n):
    data = b''
    while len(data) < n:
        part = sock.recv(n - len(data))
        assert part, 'Unexpected socket close'
        data += part
    return data


def send(sock, value, opcode=1):
    data = json.dumps(value).encode() if opcode == 1 else value
    mask = os.urandom(4)
    header = bytes([128 | opcode, 128 | len(data)]) if len(data) < 126 else bytes([128 | opcode, 254]) + struct.pack('!H', len(data))
    sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def receive(sock):
    a, b = exact(sock, 2)
    assert not b & 128
    size = b & 127
    if size == 126:
        size = struct.unpack('!H', exact(sock, 2))[0]
    elif size == 127:
        size = struct.unpack('!Q', exact(sock, 8))[0]
    payload = exact(sock, size)
    return a & 15, json.loads(payload) if a & 15 == 1 else payload


def until(sock, kind, request_id=None):
    for _ in range(30):
        opcode, value = receive(sock)
        if opcode == 1 and value.get('type') == kind and (request_id is None or value.get('id') == request_id):
            return value
    raise AssertionError('Missing ' + kind)


def fresh_snapshot():
    with connect() as sock:
        send(sock, {'type': 'auth', 'key': KEY})
        until(sock, 'auth.ok')
        return until(sock, 'state.snapshot')['payload']


info = http('/api/info')
assert info['ray']['supported'] and info['ray']['protocol'] == 1
for path in ['/api/state', '/api/audio', '/api/schedule', '/api/days']:
    http(path)
try:
    http('/api/state', 'invalid')
    raise AssertionError('Unauthenticated API accepted')
except urllib.error.HTTPError as error:
    assert error.code == 403
with connect() as sock:
    send(sock, {'type': 'auth', 'key': 'invalid'})
    assert until(sock, 'auth.error')['type'] == 'auth.error'
with connect() as sock:
    send(sock, {'type': 'auth', 'key': KEY, 'client': {'name': 'Smoke', 'version': 1}})
    assert until(sock, 'auth.ok')['protocol'] == 1
    snapshot = until(sock, 'state.snapshot')['payload']
    assert snapshot['apps'], 'No installed apps in snapshot'
    send(sock, b'ray-ping', 9)
    assert receive(sock) == (10, b'ray-ping')
    for request in [
        {'type': 'launcher.home', 'id': 'home'},
        {'type': 'remote.key', 'id': 'right', 'payload': {'key': 'DPAD_RIGHT'}},
        {'type': 'app.launch', 'id': 'bad-package', 'payload': {'package': 'invalid.not.installed'}},
        {'type': 'arbitrary.shell', 'id': 'denied', 'payload': {'command': 'id'}},
    ]:
        send(sock, request)
        ack = until(sock, 'ack', request['id'])
        assert ack['ok'] == (request['id'] in ['home', 'right']), ack
    audio = http('/api/audio')
    volume = audio.get('volume', {})
    if 'level' in volume and volume.get('max', 0) > 0:
        previous = volume['level']
        changed = previous - 1 if previous == volume['max'] else previous + 1
        try:
            send(sock, {'type': 'audio.set', 'id': 'volume', 'payload': {'volume': changed}})
            assert until(sock, 'ack', 'volume')['ok']
            assert http('/api/audio')['volume']['level'] == changed
        finally:
            send(sock, {'type': 'audio.set', 'id': 'volume-restore', 'payload': {'volume': previous}})
            assert until(sock, 'ack', 'volume-restore')['ok']
    settings = next((app for app in snapshot['apps'] if 'setting' in app['package'].lower()), None)
    if settings:
        send(sock, {'type': 'app.launch', 'id': 'launch-settings', 'payload': {'package': settings['package']}})
        assert until(sock, 'ack', 'launch-settings')['ok']
        for _ in range(10):
            outside_state = until(sock, 'state.snapshot')['payload']
            if not outside_state['active']:
                break
        else:
            raise AssertionError('Installed app was not launched')
        send(sock, {'type': 'remote.key', 'id': 'outside', 'payload': {'key': 'DPAD_LEFT'}})
        assert until(sock, 'ack', 'outside')['ok'] == bool(outside_state.get('globalInput')), 'Global input result disagrees with capability'
        try:
            if outside_state.get('motionPointer'):
                send(sock, {'type': 'remote.motion', 'payload': {'x': .35, 'y': .55}})
                time.sleep(.2)
                pointer = fresh_snapshot()
                assert not pointer['active'] and pointer['pointerActive'], 'Pointer disappeared outside launcher'
                stationary_until = time.monotonic() + 2.3
                while time.monotonic() < stationary_until:
                    send(sock, {'type': 'remote.motion', 'payload': {'x': .35, 'y': .55}})
                    time.sleep(.05)
                assert not fresh_snapshot()['pointerActive'], 'Stationary motion packets keep pointer awake'
                send(sock, {'type': 'remote.motion', 'payload': {'x': .45, 'y': .65}})
                time.sleep(.2)
                assert fresh_snapshot()['pointerActive'], 'Movement did not wake pointer outside launcher'
                send(sock, {'type': 'remote.pointer', 'payload': {'visible': False}})
                time.sleep(.1)
                assert not fresh_snapshot()['pointerActive'], 'Explicit Motion stop did not hide pointer'
                print('PASS: pointer persists outside launcher, idle hides despite incoming packets, movement wakes, explicit stop hides')
        finally:
            send(sock, {'type': 'launcher.home', 'id': 'return-home'})
            assert until(sock, 'ack', 'return-home')['ok']
with connect() as sock:
    send(sock, {'type': 'auth', 'key': KEY})
    until(sock, 'auth.ok')
    until(sock, 'state.snapshot')
    sock.sendall(b'\x81\x02{}')  # unmasked client frame must close
    try:
        for _ in range(5):
            opcode, payload = receive(sock)
            if opcode == 8:
                assert payload[:2] == struct.pack('!H', 1002)
                break
            assert opcode == 1 and payload.get('type') == 'state.snapshot', 'Unexpected frame before protocol close'
        else:
            raise AssertionError('Unmasked frame did not close the socket')
    except (ConnectionResetError, AssertionError) as error:
        if isinstance(error, AssertionError) and str(error) != 'Unexpected socket close':
            raise
with contextlib.ExitStack() as stack:
    for _ in range(4):
        sock = stack.enter_context(connect())
        send(sock, {'type': 'auth', 'key': KEY})
        until(sock, 'auth.ok')
        until(sock, 'state.snapshot')
    assert http('/api/info')['ray']['supported'], 'Four companions starve HTTP'
with connect() as sock:
    start = time.monotonic()
    closed = False
    while time.monotonic() - start < 6.5:
        try:
            sock.sendall(b'\x81')  # drip incomplete auth without exceeding idle timeout
            if select.select([sock], [], [], .8)[0]:
                closed = not sock.recv(1)
                if closed:
                    break
        except (BrokenPipeError, ConnectionResetError):
            closed = True
            break
    assert closed, 'Authentication lacks absolute deadline'
print('PASS: legacy HTTP, auth/deadline, snapshot, ping/pong, typed commands, allowlist, masks, four companions plus HTTP')
