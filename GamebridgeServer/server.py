import socket
import json
import threading
import vgamepad as vg
from flask import Flask, send_from_directory, request
import os
import struct
import time
import logging

log = logging.getLogger('werkzeug')
log.setLevel(logging.ERROR)

app = Flask(__name__, static_folder='public')

current_gamepad_type = 'xbox'
gamepad = None

def init_gamepad(type_name):
    global gamepad, current_gamepad_type
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

init_gamepad('xbox')

# Global state
connected_clients = {}  # ip -> {"last_packet": float, "slot": int}
latest_gamepad_state = {}

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

def apply_gamepad_state(data):
    global current_gamepad_type
    if not gamepad:
        return

    req_type = data.get('type', 'xbox')
    if req_type == 'ps' and current_gamepad_type != 'ps':
        init_gamepad('ps')
    elif req_type != 'ps' and current_gamepad_type == 'ps':
        init_gamepad('xbox')

    try:
        if current_gamepad_type == 'ps':
            # DS4 Mapping
            if data.get('btn_a'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_CROSS)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_CROSS)
            if data.get('btn_b'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_CIRCLE)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_CIRCLE)
            if data.get('btn_x'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SQUARE)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SQUARE)
            if data.get('btn_y'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_TRIANGLE)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_TRIANGLE)
            
            # Bumpers
            if data.get('btn_l1'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_LEFT)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_LEFT)
            if data.get('btn_r1'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_RIGHT)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_RIGHT)
            
            # Menu
            if data.get('btn_select'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHARE)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_SHARE)
            if data.get('btn_start'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_OPTIONS)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_OPTIONS)
            
            # D-pad
            dpad = data.get('dpad', 0)
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
            gamepad.directional_pad(direction=ds4_dpad_map.get(dpad, vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NONE))
            
            # Triggers (0.0 - 1.0 -> 0 to 255 internally handled by vgamepad)
            gamepad.left_trigger_float(value_float=data.get('lt', 0.0))
            gamepad.right_trigger_float(value_float=data.get('rt', 0.0))
            
            # Joysticks (-1.0 to 1.0) - Removed minus inversion for PS4 specifically
            gamepad.left_joystick_float(x_value_float=data.get('ls_x', 0.0), y_value_float=data.get('ls_y', 0.0))
            gamepad.right_joystick_float(x_value_float=data.get('rs_x', 0.0), y_value_float=data.get('rs_y', 0.0))
            
            # Thumbs
            if data.get('btn_l3'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_THUMB_LEFT)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_THUMB_LEFT)
            if data.get('btn_r3'): gamepad.press_button(button=vg.DS4_BUTTONS.DS4_BUTTON_THUMB_RIGHT)
            else: gamepad.release_button(button=vg.DS4_BUTTONS.DS4_BUTTON_THUMB_RIGHT)

        else:
            # XBOX Mapping
            if data.get('btn_a'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
            if data.get('btn_b'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
            if data.get('btn_x'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_X)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_X)
            if data.get('btn_y'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_Y)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_Y)
    
            if data.get('btn_l1'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
            if data.get('btn_r1'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
    
            if data.get('btn_select'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK)
            if data.get('btn_start'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)
    
            dpad = data.get('dpad', 0)
            gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP)
            gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
            gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN)
            gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
            if dpad == 1: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP)
            if dpad == 2: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP); gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
            if dpad == 3: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
            if dpad == 4: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN); gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
            if dpad == 5: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN)
            if dpad == 6: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN); gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
            if dpad == 7: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
            if dpad == 8: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP); gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
    
            gamepad.left_trigger_float(value_float=data.get('lt', 0.0))
            gamepad.right_trigger_float(value_float=data.get('rt', 0.0))
    
            gamepad.left_joystick_float(x_value_float=data.get('ls_x', 0.0), y_value_float=-(data.get('ls_y', 0.0)))
            gamepad.right_joystick_float(x_value_float=data.get('rs_x', 0.0), y_value_float=-(data.get('rs_y', 0.0)))
    
            if data.get('btn_l3'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB)
            if data.get('btn_r3'): gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB)
            else: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB)

        gamepad.update()
    except Exception as e:
        print(f"[Error handling UDP payload] {e}")


def udp_server_loop():
    global connected_clients, latest_gamepad_state
    UDP_IP = "0.0.0.0"
    UDP_PORT = 9090
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))
    print(f"[UDP] Listening for Wi-Fi Gamepad inputs on port {UDP_PORT}")

    while True:
        data, addr = sock.recvfrom(1024)
        now = time.time()
        client_ip = addr[0]
        
        if client_ip in connected_clients:
            connected_clients[client_ip]['last_packet'] = now
        else:
            used_slots = [d['slot'] for d in connected_clients.values()]
            slot = 1
            while slot in used_slots and slot <= 4:
                slot += 1
            if slot > 4:
                slot = 4
            connected_clients[client_ip] = {'last_packet': now, 'slot': slot}
        
        try:
            payload = {}
            if len(data) == 28:
                # Fast Binary Struct Unpack
                # Format: '<BHBffffff' (28 bytes)
                tipo, btns, dpad, lt, rt, ls_x, ls_y, rs_x, rs_y = struct.unpack('<BHBffffff', data)
                
                type_str = 'generic'
                if tipo == 1: type_str = 'xbox'
                elif tipo == 2: type_str = 'ps'
                elif tipo == 3: type_str = 'racing'
                
                payload = {
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
                }
            elif len(data) > 0 and data[0] == ord('{'):
                # Legacy JSON fallback
                payload = json.loads(data.decode('utf-8'))
            
            if payload:
                latest_gamepad_state = payload
                apply_gamepad_state(payload)
        except Exception as e:
            pass

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
    global connected_clients
    client_ip = request.remote_addr
    now = time.time()
    
    dead_ips = [ip for ip, data in connected_clients.items() if now - data['last_packet'] > 2.0]
    for ip in dead_ips:
        del connected_clients[ip]
        
    is_connected = len(connected_clients) > 0
    
    if client_ip in connected_clients:
        slot = connected_clients[client_ip]['slot']
        client_is_active = True
    else:
        used_slots = [d['slot'] for d in connected_clients.values()]
        slot = 1
        while slot in used_slots and slot <= 4:
            slot += 1
        if slot > 4: slot = 4
        connected_clients[client_ip] = {'last_packet': now, 'slot': slot}
        client_is_active = True
        is_connected = True

    return {"ip": get_local_ip(), "connected": client_is_active, "vigem": (gamepad is not None), "slot": slot}

@app.route('/api/state')
def get_state():
    global latest_gamepad_state
    # Solo en caso de requerirlo la UI de pruebas
    return latest_gamepad_state

if __name__ == '__main__':
    # Start UDP listener in background
    udp_thread = threading.Thread(target=udp_server_loop, daemon=True)
    udp_thread.start()

    # Start Flask Web Dashboard
    ip = get_local_ip()
    print("\n" + "="*50)
    print(" 🎮 GAMEBRIDGE PC SERVER INICIADO 🎮")
    print(f" 1. Abre el Dashboard en tu navegador: http://localhost:8080")
    print(f" 2. Entra esta IP en la App de Android: {ip}")
    print("="*50 + "\n")
    
    app.run(host='0.0.0.0', port=8080)
