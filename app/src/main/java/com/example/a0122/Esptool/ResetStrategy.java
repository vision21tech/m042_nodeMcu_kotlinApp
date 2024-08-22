package com.example.a0122.Esptool;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.fazecast.jSerialComm.SerialPort;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

public class ResetStrategy {
    public UsbSerialPort port;
    private int reset_delay;
    public static final int DEFAULT_RESET_DELAY = 50;


    ResetStrategy(UsbSerialPort port, int reset_delay){
        this.port = port;
        this.reset_delay = reset_delay;
    }
    
    public void call(UsbDeviceConnection connection){
        for (int i = 0; i < 3; i++) {
            try {
                if(!this.port.isOpen()){
                    this.port.open(connection);
                }
                this.reset();
                break;
            } catch (Exception e) {
                System.out.println("ResetStrategy call() 에러");
                try {
                    this.port.close();
                }catch (IOException ioe){
                    Log.e("fun_call_err", "call: " + ioe.getMessage());
                }
                try {
                    Thread.sleep(500);
                } catch (Exception err) {
                    System.out.println("ResetStrategy call() 0.5초 대기 실패");
                }
            }
        }
    }
    public void reset() throws IOException{
        System.out.println("reset 잘못진입");
    }
    public void _setDTR(boolean state) throws IOException{
        if(state) this.port.setDTR(true);
        else this.port.setDTR(false);
    }
    public void _setRTS(boolean state) throws IOException{
        if(state) this.port.setRTS(true);
        else this.port.setRTS(false);
        if(this.port.getDTR()) this.port.setDTR(true);
        else this.port.setDTR(false);
    }
    public int getReset_delay(){
        return this.reset_delay;
    }
}

class ClassicReset extends ResetStrategy{
    ClassicReset(UsbSerialPort port, int reset_delay){
        super(port, reset_delay);
    }

    public void reset() throws IOException{
        System.out.println("reset 잘 진입");
        port.setDTR(false);
		port.setRTS(true);
        if(port.getDTR()) port.setDTR(true);
        else port.setDTR(false);
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
		}
		port.setDTR(true);
		port.setRTS(false);
        if(port.getDTR()) port.setDTR(true);
        else port.setDTR(false);
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
		}

		port.setDTR(false);
    }

}
class HardReset extends ResetStrategy{
    boolean uses_usb;

    HardReset(UsbSerialPort port){
        super(port, DEFAULT_RESET_DELAY);
        boolean uses_usb = false;
        this.uses_usb = uses_usb;
    }

    public void reset() throws IOException{
        System.out.println("hardreset 메소드 진입");
        port.setRTS(true);
        if(port.getDTR()) port.setDTR(true);
        else port.setDTR(false);

        if(uses_usb){
            try {
                Thread.sleep(200);
            }catch (InterruptedException e){}

            port.setRTS(false);
            if(port.getDTR()) port.setDTR(true);
            else port.setDTR(false);
            try {
                Thread.sleep(200);
            }catch (InterruptedException e){}
        }
        else{
            try {
                Thread.sleep(100);
            }catch (InterruptedException e){}
            port.setRTS(false);
            if(port.getDTR()) port.setDTR(true);
            else port.setRTS(false);
        }
    }
}
