package com.nononews.gamebridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.concurrent.Executors;

public class BluetoothHidManager {
    private static final String TAG = "GamepadHID";
    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedHost;

    // Standard Gamepad HID Descriptor (Subclass 0x08)
    private static final byte[] HID_DESCRIPTOR = new byte[]{
            (byte) 0x05, (byte) 0x01,       // USAGE_PAGE (Generic Desktop)
            (byte) 0x09, (byte) 0x05,       // USAGE (Gamepad)
            (byte) 0xa1, (byte) 0x01,       // COLLECTION (Application)
            (byte) 0x85, (byte) 0x01,       //   REPORT_ID (1)
            (byte) 0x05, (byte) 0x09,       //   USAGE_PAGE (Button)
            (byte) 0x19, (byte) 0x01,       //   USAGE_MINIMUM (Button 1)
            (byte) 0x29, (byte) 0x10,       //   USAGE_MAXIMUM (Button 16)
            (byte) 0x15, (byte) 0x00,       //   LOGICAL_MINIMUM (0)
            (byte) 0x25, (byte) 0x01,       //   LOGICAL_MAXIMUM (1)
            (byte) 0x75, (byte) 0x01,       //   REPORT_SIZE (1)
            (byte) 0x95, (byte) 0x10,       //   REPORT_COUNT (16)
            (byte) 0x81, (byte) 0x02,       //   INPUT (Data,Var,Abs)
            (byte) 0x05, (byte) 0x01,       //   USAGE_PAGE (Generic Desktop)
            (byte) 0x09, (byte) 0x30,       //   USAGE (X)
            (byte) 0x09, (byte) 0x31,       //   USAGE (Y)
            (byte) 0x09, (byte) 0x32,       //   USAGE (Z) -> Right X
            (byte) 0x09, (byte) 0x35,       //   USAGE (Rz) -> Right Y
            (byte) 0x15, (byte) 0x00,       //   LOGICAL_MINIMUM (0)
            (byte) 0x26, (byte) 0xff, 0x00, //   LOGICAL_MAXIMUM (255)
            (byte) 0x75, (byte) 0x08,       //   REPORT_SIZE (8)
            (byte) 0x95, (byte) 0x04,       //   REPORT_COUNT (4)
            (byte) 0x81, (byte) 0x02,       //   INPUT (Data,Var,Abs)
            (byte) 0xc0                     // END_COLLECTION
    };

    public BluetoothHidManager(Context context) {
        this.context = context;
    }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth no disponible o desactivado");
            return;
        }

        bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = (BluetoothHidDevice) proxy;
                    registerApp();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null;
                }
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    @SuppressLint("MissingPermission")
    private void registerApp() {
        if (hidDevice != null) {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "GameBridge",
                    "Virtual Gamepad",
                    "GameBridgeCorp",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                    HID_DESCRIPTOR
            );

            hidDevice.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), new BluetoothHidDevice.Callback() {
                @Override
                public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                    Log.i(TAG, "App Status Changed. Registered: " + registered);
                }

                @Override
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    Log.i(TAG, "Connection State Changed: " + state);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        connectedHost = device;
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        connectedHost = null;
                    }
                }
            });
        }
    }

    public boolean isConnected() {
        return connectedHost != null && hidDevice != null;
    }

    @SuppressLint("MissingPermission")
    public void sendReport(byte[] report) {
        if (isConnected()) {
            hidDevice.sendReport(connectedHost, 1, report);
        }
    }
}
