import json
import logging
import math
import secrets
import socket
import struct
import threading
import time

import vgamepad as vg
from flask import Flask, request, send_from_directory


log = logging.getLogger('werkzeug')
log.setLevel(logging.ERROR)

app = Flask(__name__, static_folder='public')

UDP_IP = "0.0.0.0"
UDP_PORT = 9090
CLIENT_TIMEOUT_SECONDS = 2.0
PAIRING_CODE_TTL_SECONDS = 300.0
PAIRING_SESSION_TTL_SECONDS = 12 * 60 * 60.0
PAIRING_MAX_ATTEMPTS = 8
PAIRING_ATTEMPT_WINDOW_SECONDS = 60.0
MAX_PLAYERS = 4

state_lock = threading.RLock()
current_gamepad_type = 'xbox'
gamepad = None

connected_clients = {}  # ip -> {"last_packet": float, "slot": int}
latest_gamepad_state = {}
paired_clients = {}  # ip -> {"paired_at": float, "last_seen": float}
pairing_attempts = {}  # ip -> [attempt_timestamp, ...]
pairing_code = f"{secrets.randbelow(1_000_000):06d}"
pairing_code_created_at = time.time()

NEUTRAL_PAYLOAD = {
    'type': 'xbox',
    'btn_a': 0,
    'btn_b': 0,
    'btn_x': 0,
    'btn_y': 0,
    'btn_l1': 0,
    'btn_r1': 0,
    'btn_l3': 0,
    'btn_r3': 0,
    'btn_select': 0,
    'btn_start': 0,
    'dpad': 0,
    'lt': 0.0,
    'rt': 0.0,
    'ls_x': 0.0,
    'ls_y': 0.0,
    'rs_x': 0.0,
    'rs_y': 0.0
}


def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip


def init_gamepad(type_name):
    global gamepad, current_gamepad_type
    with state_lock:
        try:
            if type_name == 'ps':
                gamepad = vg.VDS4Gamepad()
                current_gamepad_type = 'ps'
                print("[ViGEm] Virtual DualShock 4 Controller connected!")
            else:
                gamepad = vg.VX360Gamepad()
                current_gamepad_type = 'xbox'
                print("[ViGEm] Virtual Xbox 360 Controller connected!")
        except Exception as e:
            print(f"[ERROR] ViGEmBus failed to initialize. Do you have the driver installed? {e}")
            gamepad = None


def clamp_float(value, min_value=-1.0, max_value=1.0):
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(numeric):
        return 0.0
    return max(min_value, min(max_value, numeric))


def safe_button(value):
    return 1 if bool(value) else 0


def normalize_payload(payload):
    if not payload:
        return None

    profile_type = payload.get('type', 'xbox')
    if profile_type not in ('xbox', 'ps', 'generic', 'racing'):
        profile_type = 'xbox'

    try:
        dpad = int(payload.get('dpad', 0))
    except (TypeError, ValueError):
        dpad = 0
    if dpad < 0 or dpad > 8:
        dpad = 0

    return {
        'type': profile_type,
        'btn_a': safe_button(payload.get('btn_a', 0)),
        'btn_b': safe_button(payload.get('btn_b', 0)),
        'btn_x': safe_button(payload.get('btn_x', 0)),
        'btn_y': safe_button(payload.get('btn_y', 0)),
        'btn_l1': safe_button(payload.get('btn_l1', 0)),
        'btn_r1': safe_button(payload.get('btn_r1', 0)),
        'btn_l3': safe_button(payload.get('btn_l3', 0)),
        'btn_r3': safe_button(payload.get('btn_r3', 0)),
        'btn_select': safe_button(payload.get('btn_select', 0)),
        'btn_start': safe_button(payload.get('btn_start', 0)),
        'dpad': dpad,
        'lt': clamp_float(payload.get('lt', 0.0), 0.0, 1.0),
        'rt': clamp_float(payload.get('rt', 0.0), 0.0, 1.0),
        'ls_x': clamp_float(payload.get('ls_x', 0.0)),
        'ls_y': clamp_float(payload.get('ls_y', 0.0)),
        'rs_x': clamp_float(payload.get('rs_x', 0.0)),
        'rs_y': clamp_float(payload.get('rs_y', 0.0))
    }


def payload_to_frontend_state(payload):
    return {
        "type": payload.get("type", "xbox"),
        "buttons": [
            {"pressed": bool(payload.get('btn_a', 0))},
            {"pressed": bool(payload.get('btn_b', 0))},
            {"pressed": bool(payload.get('btn_x', 0))},
            {"pressed": bool(payload.get('btn_y', 0))},
            {"pressed": bool(payload.get('btn_l1', 0))},
            {"pressed": bool(payload.get('btn_r1', 0))},
            {"pressed": payload.get('lt', 0.0) > 0.5},
            {"pressed": payload.get('rt', 0.0) > 0.5},
            {"pressed": bool(payload.get('btn_select', 0))},
            {"pressed": bool(payload.get('btn_start', 0))},
            {"pressed": bool(payload.get('btn_l3', 0))},
            {"pressed": bool(payload.get('btn_r3', 0))},
            {"pressed": payload.get('dpad', 0) in [1, 2, 8]},
            {"pressed": payload.get('dpad', 0) in [4, 5, 6]},
            {"pressed": payload.get('dpad', 0) in [6, 7, 8]},
            {"pressed": payload.get('dpad', 0) in [2, 3, 4]}
        ],
        "axes": [
            payload.get('ls_x', 0.0),
            payload.get('ls_y', 0.0),
            payload.get('rs_x', 0.0),
            payload.get('rs_y', 0.0)
        ]
    }


def press_or_release(button_state, button):
    if button_state:
        gamepad.press_button(button=button)
    else:
        gamepad.release_button(button=button)


def apply_gamepad_state(data):
    global current_gamepad_type
    with state_lock:
        if not gamepad:
            return

        req_type = data.get('type', 'xbox')
        if req_type == 'ps' and current_gamepad_type != 'ps':
            init_gamepad('ps')
        elif req_type != 'ps' and current_gamepad_type == 'ps':
            init_gamepad('xbox')

        try:
            if current_gamepad_type == 'ps':
                button_map = {
                    'btn_a': vg.DS4_BUTTONS.DS4_BUTTON_CROSS,
                    'btn_b': vg.DS4_BUTTONS.DS4_BUTTON_CIRCLE,
                    'btn_x': vg.DS4_BUTTONS.DS4_BUTTON_SQUARE,
                    'btn_y': vg.DS4_BUTTONS.DS4_BUTTON_TRIANGLE,
                    'btn_l1': vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_LEFT,
                    'btn_r1': vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_RIGHT,
                    'btn_select': vg.DS4_BUTTONS.DS4_BUTTON_SHARE,
                    'btn_start': vg.DS4_BUTTONS.DS4_BUTTON_OPTIONS,
                    'btn_l3': vg.DS4_BUTTONS.DS4_BUTTON_THUMB_LEFT,
                    'btn_r3': vg.DS4_BUTTONS.DS4_BUTTON_THUMB_RIGHT
                }
                for key, button in button_map.items():
                    press_or_release(data.get(key), button)

                ds4_dpad_map = {
                    0: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NONE,
                    1: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NORTH,
                    2: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NORTHEAST,
                    3: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_EAST,
                    4: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_SOUTHEAST,
                    5: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_SOUTH,
                    6: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_SOUTHWEST,
                    7: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_WEST,
                    8: vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NORTHWEST
                }
                gamepad.directional_pad(direction=ds4_dpad_map.get(data.get('dpad', 0), vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NONE))
                gamepad.left_trigger_float(value_float=data.get('lt', 0.0))
                gamepad.right_trigger_float(value_float=data.get('rt', 0.0))
                gamepad.left_joystick_float(x_value_float=data.get('ls_x', 0.0), y_value_float=data.get('ls_y', 0.0))
                gamepad.right_joystick_float(x_value_float=data.get('rs_x', 0.0), y_value_float=data.get('rs_y', 0.0))
            else:
                button_map = {
                    'btn_a': vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
                    'btn_b': vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
                    'btn_x': vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
                    'btn_y': vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
                    'btn_l1': vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
                    'btn_r1': vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
                    'btn_select': vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
                    'btn_start': vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
                    'btn_l3': vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
                    'btn_r3': vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB
                }
                for key, button in button_map.items():
                    press_or_release(data.get(key), button)

                for button in (
                    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
                    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
                    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
                    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT
                ):
                    gamepad.release_button(button=button)

                dpad = data.get('dpad', 0)
                if dpad in (1, 2, 8):
                    gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP)
                if dpad in (2, 3, 4):
                    gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
                if dpad in (4, 5, 6):
                    gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN)
                if dpad in (6, 7, 8):
                    gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)

                gamepad.left_trigger_float(value_float=data.get('lt', 0.0))
                gamepad.right_trigger_float(value_float=data.get('rt', 0.0))
                gamepad.left_joystick_float(x_value_float=data.get('ls_x', 0.0), y_value_float=-(data.get('ls_y', 0.0)))
                gamepad.right_joystick_float(x_value_float=data.get('rs_x', 0.0), y_value_float=-(data.get('rs_y', 0.0)))

            gamepad.update()
        except Exception as e:
            print(f"[Error handling gamepad state] {e}")


def reset_virtual_gamepad(type_name=None):
    neutral = dict(NEUTRAL_PAYLOAD)
    if type_name:
        neutral['type'] = type_name
    apply_gamepad_state(neutral)


def is_local_request(remote_addr):
    return remote_addr in ('127.0.0.1', '::1', 'localhost')


def rotate_pairing_code_if_needed(now=None):
    global pairing_code, pairing_code_created_at
    if now is None:
        now = time.time()
    with state_lock:
        if now - pairing_code_created_at > PAIRING_CODE_TTL_SECONDS:
            pairing_code = f"{secrets.randbelow(1_000_000):06d}"
            pairing_code_created_at = now
        return pairing_code


def cleanup_pairing_sessions(now=None):
    global paired_clients
    if now is None:
        now = time.time()
    with state_lock:
        expired_ips = [
            ip for ip, data in paired_clients.items()
            if now - data.get('last_seen', data.get('paired_at', 0.0)) > PAIRING_SESSION_TTL_SECONDS
        ]
        for ip in expired_ips:
            paired_clients.pop(ip, None)


def is_paired_client(client_ip):
    now = time.time()
    cleanup_pairing_sessions(now)
    with state_lock:
        session = paired_clients.get(client_ip)
        if not session:
            return False
        session['last_seen'] = now
        return True


def pair_client(client_ip, submitted_code):
    now = time.time()
    active_code = rotate_pairing_code_if_needed(now)
    code = str(submitted_code or '').strip()
    with state_lock:
        recent_attempts = [
            ts for ts in pairing_attempts.get(client_ip, [])
            if now - ts <= PAIRING_ATTEMPT_WINDOW_SECONDS
        ]
        if len(recent_attempts) >= PAIRING_MAX_ATTEMPTS:
            pairing_attempts[client_ip] = recent_attempts
            return False, "RATE_LIMITED"
        recent_attempts.append(now)
        pairing_attempts[client_ip] = recent_attempts

    if code != active_code:
        return False, "INVALID_PAIRING_CODE"

    with state_lock:
        paired_clients[client_ip] = {'paired_at': now, 'last_seen': now}
        pairing_attempts.pop(client_ip, None)
    return True, None


def cleanup_dead_clients(now=None):
    global connected_clients, latest_gamepad_state
    if now is None:
        now = time.time()

    reset_needed = False
    with state_lock:
        dead_ips = [ip for ip, data in connected_clients.items() if now - data['last_packet'] > CLIENT_TIMEOUT_SECONDS]
        for ip in dead_ips:
            slot = connected_clients[ip]['slot']
            del connected_clients[ip]
            latest_gamepad_state[str(slot)] = payload_to_frontend_state(NEUTRAL_PAYLOAD)
            reset_needed = True

    if reset_needed:
        reset_virtual_gamepad()


def get_or_assign_client_slot(client_ip, now):
    with state_lock:
        if client_ip in connected_clients:
            connected_clients[client_ip]['last_packet'] = now
            return connected_clients[client_ip]['slot']

        used_slots = [d['slot'] for d in connected_clients.values()]
        slot = 1
        while slot in used_slots and slot <= MAX_PLAYERS:
            slot += 1
        if slot > MAX_PLAYERS:
            slot = MAX_PLAYERS

        connected_clients[client_ip] = {'last_packet': now, 'slot': slot}
        return slot


def decode_udp_payload(data):
    if len(data) == 28:
        tipo, btns, dpad, lt, rt, ls_x, ls_y, rs_x, rs_y = struct.unpack('<BHBffffff', data)

        type_str = 'generic'
        if tipo == 1:
            type_str = 'xbox'
        elif tipo == 2:
            type_str = 'ps'
        elif tipo == 3:
            type_str = 'racing'

        return normalize_payload({
            'type': type_str,
            'btn_a': 1 if (btns & (1 << 0)) else 0,
            'btn_b': 1 if (btns & (1 << 1)) else 0,
            'btn_x': 1 if (btns & (1 << 2)) else 0,
            'btn_y': 1 if (btns & (1 << 3)) else 0,
            'btn_l1': 1 if (btns & (1 << 4)) else 0,
            'btn_r1': 1 if (btns & (1 << 5)) else 0,
            'btn_l3': 1 if (btns & (1 << 6)) else 0,
            'btn_r3': 1 if (btns & (1 << 7)) else 0,
            'btn_select': 1 if (btns & (1 << 8)) else 0,
            'btn_start': 1 if (btns & (1 << 9)) else 0,
            'dpad': dpad,
            'lt': lt,
            'rt': rt,
            'ls_x': ls_x,
            'ls_y': ls_y,
            'rs_x': rs_x,
            'rs_y': rs_y
        })

    if len(data) > 0 and data[0] == ord('{'):
        return normalize_payload(json.loads(data.decode('utf-8')))

    return None


def udp_server_loop():
    global latest_gamepad_state
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))
    print(f"[UDP] Listening for Wi-Fi Gamepad inputs on port {UDP_PORT}")

    while True:
        data, addr = sock.recvfrom(1024)
        now = time.time()
        client_ip = addr[0]

        if not is_paired_client(client_ip):
            continue

        slot = get_or_assign_client_slot(client_ip, now)

        try:
            payload = decode_udp_payload(data)
            if not payload:
                continue

            with state_lock:
                latest_gamepad_state[str(slot)] = payload_to_frontend_state(payload)

            apply_gamepad_state(payload)
        except Exception as e:
            print(f"[Error handling UDP payload] {e}")


def watchdog_loop():
    while True:
        time.sleep(0.25)
        rotate_pairing_code_if_needed()
        cleanup_pairing_sessions()
        cleanup_dead_clients()


@app.after_request
def after_request(response):
    response.headers.add('Access-Control-Allow-Origin', '*')
    response.headers.add('Access-Control-Allow-Headers', 'Content-Type,Authorization')
    response.headers.add('Access-Control-Allow-Methods', 'GET,PUT,POST,DELETE,OPTIONS')
    return response


@app.route('/')
def index():
    return send_from_directory('public', 'index.html')


@app.route('/<path:path>')
def static_files(path):
    return send_from_directory('public', path)


@app.route('/api/status')
def get_status():
    client_ip = request.remote_addr
    cleanup_dead_clients(time.time())
    rotate_pairing_code_if_needed()

    with state_lock:
        is_connected = len(connected_clients) > 0
        is_paired = client_ip in paired_clients
        slot = 0
        if client_ip in connected_clients:
            slot = connected_clients[client_ip]['slot']
        elif is_connected:
            slot = 1

        response = {
            "service": "gamebridge",
            "ready": (gamepad is not None),
            "ip": get_local_ip(),
            "connected": is_connected,
            "vigem": (gamepad is not None),
            "slot": slot,
            "paired": is_paired,
            "pairRequired": True,
            "pairedCount": len(paired_clients),
            "timeoutMs": int(CLIENT_TIMEOUT_SECONDS * 1000)
        }
        if is_local_request(client_ip):
            response["pairCode"] = pairing_code
            response["pairCodeTtlMs"] = max(0, int((PAIRING_CODE_TTL_SECONDS - (time.time() - pairing_code_created_at)) * 1000))
        return response


@app.route('/api/pair', methods=['POST'])
def pair_device():
    client_ip = request.remote_addr
    try:
        body = request.get_json(silent=True) or {}
    except Exception:
        body = {}

    paired, error = pair_client(client_ip, body.get('code'))
    if not paired:
        status_code = 429 if error == "RATE_LIMITED" else 403
        return {"ok": False, "error": error}, status_code

    return {
        "ok": True,
        "service": "gamebridge",
        "paired": True,
        "timeoutMs": int(CLIENT_TIMEOUT_SECONDS * 1000)
    }


@app.route('/api/state')
def get_state():
    with state_lock:
        return dict(latest_gamepad_state)


if __name__ == '__main__':
    init_gamepad('xbox')
    reset_virtual_gamepad('xbox')

    udp_thread = threading.Thread(target=udp_server_loop, daemon=True)
    udp_thread.start()

    watchdog_thread = threading.Thread(target=watchdog_loop, daemon=True)
    watchdog_thread.start()

    ip = get_local_ip()
    print("\n" + "=" * 50)
    print(" GAMEBRIDGE PC SERVER INICIADO ")
    print(" 1. Abre el Dashboard en tu navegador: http://localhost:8080")
    print(f" 2. Entra esta IP en la App de Android: {ip}")
    print("=" * 50 + "\n")

    app.run(host='0.0.0.0', port=8080)
