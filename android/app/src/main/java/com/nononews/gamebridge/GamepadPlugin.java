package com.nononews.gamebridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GamepadPlugin")
public class GamepadPlugin extends Plugin {

    private BluetoothHidManager hidManager;

    @Override
    public void load() {
        super.load();
        hidManager = new BluetoothHidManager(getContext());
    }

    @PluginMethod
    public void initBluetooth(PluginCall call) {
        if (!hidManager.hasPermissions()) {
            call.reject("Missing permissions");
            return;
        }
        hidManager.start();
        call.resolve();
    }

    @PluginMethod
    public void sendReport(PluginCall call) {
        if (!hidManager.isConnected()) {
            call.reject("Not connected to any host");
            return;
        }

        try {
            int buttons = call.getInt("buttons", 0);
            int leftX = call.getInt("leftX", 128); // 0-255 (128 center)
            int leftY = call.getInt("leftY", 128);
            int rightX = call.getInt("rightX", 128);
            int rightY = call.getInt("rightY", 128);

            byte[] report = new byte[6];
            report[0] = (byte) (buttons & 0xFF);
            report[1] = (byte) ((buttons >> 8) & 0xFF);
            report[2] = (byte) leftX;
            report[3] = (byte) leftY;
            report[4] = (byte) rightX;
            report[5] = (byte) rightY;

            hidManager.sendReport(report);
            call.resolve();
        } catch (Exception e) {
            call.reject("Error sending report", e);
        }
    }
}
