package com.example.a0122.Esptool;

import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

public class ESP8266ROM extends ESPLoader {
    public static final int SPI_REG_BASE = 0x60000200;
    public static final int SPI_USR_OFFS = 0x1C;
    public static final int SPI_USR1_OFFS = 0x20;
    public static final int SPI_USR2_OFFS = 0x24;
    public static final int SPI_W0_OFFS = 0x40;
    ESP8266ROM(){
        super();
    }

    ESP8266ROM(UsbManager manager, int initial_baud, boolean trace) throws IOException {
        super(manager, initial_baud, trace);
    }
    public static int get_erase_size(int offset, int size){
        int sectors_per_block = 16;
        int sector_size = ESPLoader.FLASH_SECTOR_SIZE;
        int num_sectors = (size + sector_size - 1) / sector_size;
        int start_sector = offset / sector_size;

        int head_sectors = sectors_per_block - (start_sector % sectors_per_block);
        if(num_sectors < head_sectors)
            head_sectors = num_sectors;

        if(num_sectors < 2 * head_sectors)
            return (num_sectors + 1) / 2 * sector_size;
        else
            return (num_sectors - head_sectors) * sector_size;
    }
}
class ESP8266StubLoader extends ESP8266ROM{
    ESP8266StubLoader(ESPLoader rom_loader){
        super();
        System.out.println("ESP8266StubLoader 진입");
        this.setSecureDownloadMode(rom_loader.getSecureDownloadMode());
        this.setPort(rom_loader.getPort());
        this.setCache(rom_loader.getCache());
        this.setSlipReader(new SlipReader(rom_loader.getPort(), false));

        //this.getPort().flushIOBuffers();
        try {
            byte[] maxInputBuffer = new byte[this.getPort().getReadEndpoint().getMaxPacketSize()];
            while(this.getPort().read(maxInputBuffer,3000) > 0){
            }
        }catch (IOException e){
            Log.d("fun_flushInputBuffer_err", "flushInputBuffer: " + e.getMessage());
        }
    }
}