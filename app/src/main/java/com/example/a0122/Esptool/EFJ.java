package com.example.a0122.Esptool;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.lang.Math;



public class EFJ {
    private static String chip = new String("esp8266");
    private UsbManager manager;
    private static int baud = 115200;
    private static String before = new String("default_reset");
    private static String after = new String("hard_reset");
    private static String operation = new String("write_flash");
    private static int address = 0x00;
    // private static String filename = new String("C:\\Users\\tjs91\\AppData\\Local\\Temp\\arduino\\sketches\\22B1A5CE994390E0AE00E64DEBC302CF\\esptest.ino.bin");
    // new String("D:\\vision21\\Arduino_backbone-6f8a1f57abeabee16ec52e7378489f47ce096606\\Arduino_backbone-6f8a1f57abeabee16ec52e7378489f47ce096606\\temp\\w8XWRP\\build\\w8XWRP.ino.bin");
    private static String filename;

    private static int connect_attempts = 7;

    private static int initial_baud = 0;

    public EFJ(UsbManager manager){
        this.setPort(manager);
    }

    public boolean flashStart(byte[] data){
        if(before.equals("no_reset_no_sync")){
            initial_baud = Math.min(ESPLoader.ESP_ROM_BAUD, baud);
        }
        else{
            initial_baud = baud;
        }
        ESP8266ROM esp = get_defalut_connected_device(manager, initial_baud, connect_attempts, chip, before, false);
        if(esp == null) {
            return false;
        }
        else Log.d("flow", "보드 연결 성공");
        

        ESP8266StubLoader espStubLoader = esp.run_stub();
        if(espStubLoader == null) return false;

        System.out.println("Configuring flash size...");
        String flash_size = Cmds.detect_flash_size(espStubLoader);
        System.out.println(flash_size);
        if(flash_size != null){
            espStubLoader.flash_set_parameters(Util.flash_size_bytes(flash_size));
        }
        try {
            Cmds.write_flash(espStubLoader, data);
        } catch (Exception e) {
            System.out.println("write_flash() 에러 : " + e.getMessage());
            return false;
        }
        if(after.equals("hard_reset"))
            esp.hard_reset();
        try {
            esp.getPort().close();         
        }catch (IOException e){
            System.out.println("fun_flashstart: port.close(): 실패");
            return false;
        }
        return true;
    }

    public static ESP8266ROM get_defalut_connected_device(UsbManager manager, int initial_baud, int connect_attempts,String chip, String before, boolean trace){
        ESP8266ROM _esp = null;
        try {
            _esp = new ESP8266ROM(manager, initial_baud, trace);
        }catch (IOException e){
            Log.e("flow", "ESP8266ROM : " + e.getMessage());
        }
        _esp.connect(before, connect_attempts);
        return _esp;
    }
    public void setPort(UsbManager manager) {
        this.manager = manager;
    }

    public static String getFilename(){
        return EFJ.filename;
    }
    public static int getAddress(){
        return EFJ.address;
    }
}
