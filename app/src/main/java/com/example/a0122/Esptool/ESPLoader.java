package com.example.a0122.Esptool;

import android.content.res.AssetManager;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.io.Reader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.RuntimeException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.Math;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fazecast.jSerialComm.SerialPort;


import org.json.simple.parser.JSONParser;
import org.json.simple.JSONObject;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class ESPLoader {
    static class cmdRet {
        int retCode;
        byte retValue[] = new byte[256];
    
    }

    private Optional<Object> STUB_CLASS = Optional.empty();
    private static String STUB_DIR = "";
    
    static final int ESP_ROM_BAUD = 115200;
    private static final int ROM_INVALID_RECV_MSG = 0x05;

    private static final int ESP_RAM_BLOCK = 0x1800;
    // esp8266은 0x4000을 사용 esp8266.py 확인
    private static final int FLASH_WRITE_SIZE = 0x4000;
    public static final int FLASH_SECTOR_SIZE = 0x1000;

    public static final int ESP_FLASH_BEGIN = 0x02;
    public static final int ESP_MEM_BEGIN = 0x05;
    public static final int ESP_MEM_END = 0x06;
    public static final int ESP_MEM_DATA = 0x07;
    public static final byte ESP_SYNC = 0x08;
    public static final int ESP_WRITE_REG = 0x09;
    public static final byte ESP_READ_REG = 0x0A;
    public static final int ESP_SPI_SET_PARAMS = 0x0B;
    public static final int ESP_FLASH_DEFL_BEGIN = 0x10;
    public static final int ESP_FLASH_DEFL_DATA = 0x11;
    public static final int ESP_FLASH_DEFL_END = 0x12;
    public static final int ESP_SPI_FLASH_MD5 = 0x13;
    
    private static final int ESP_CHECKSUM_MAGIC = 0xEF;
    
    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    private static final int UART_DATE_REG_ADDR = 0x60000078;

    // 공식문서의 DEFAULT_TIMEOUT을 참조 
    // https://docs.espressif.com/projects/esptool/en/latest/esp32/esptool/configuration-file.html
    // jserial은 타임아웃을 밀리초로 설정함
    private static final int DEFAULT_TIMEOUT = 3000;
    private static final int DEFAULT_SERIAL_WRITE_TIMEOUT = 10000;
    private static final int MAX_TIMEOUT = 240000;
    private static final int MEM_END_ROM_TIMEOUT = 200;
    private static final int SYNC_TIMEOUT = 100;
    private static final int ERASE_WRITE_TIMEOUT_PER_MB = 40000;
    private static final int MD5_TIMEOUT_PER_MB = 8000;

    private static boolean IS_STUB = false;

    private static final int STATUS_BYTES_LENGTH = 2;
    private static final int WRITE_FLASH_ATTEMPTS = 2;
    private static final int WRITE_BLOCK_ATTEMPTS = 3;
    
    private boolean sync_stub_detected = false;
    
    private UsbSerialPort _port;
    private int chip;
    private SlipReader _slip_reader;
    private boolean _trace_enabled;
    private boolean secure_download_mode;
    private boolean stub_is_disabled;
    private boolean in_bootloader;

    private UsbDeviceConnection _connection;
    private Map<String, Integer> cache;

    ESPLoader(UsbManager manager, int baud, boolean trace) throws IOException {
        this.secure_download_mode = false;
        this.stub_is_disabled = false;
        cache = new HashMap<>();
        cache.put("flash_id", 0);
        cache.put("chip_id", 0);
        cache.put("uart_no", 0);
        cache.put("usb_pid", 0);

        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        this._connection = manager.openDevice(driver.getDevice());
        if (_connection == null) {
            return;
        }

        this._port = driver.getPorts().get(0);

        if(this._port != null) {
            this._port.open(_connection);
            //this._port.clearRTS();
            this._port.setRTS(false);
            //this._port.clearDTR();
            this._port.setDTR(false);
            // RTS, DTR 비활성화. 윈도우는 비활성화.
            // TODO : 윈도우는 RTS, DTR을 비활성화하는데 안드도 그런지 확인하기

            this._slip_reader = new SlipReader(_port, false);

            this._set_port_baudrate(baud);
            this._trace_enabled = trace;
            // UsbSerialPort는 timeout을 write, read 함수에 할당함.
//            System.out.println("read timeout 출력 : " + this._port.getReadTimeout());
//            this._port.setComPortTimeouts(
//                SerialPort.TIMEOUT_WRITE_BLOCKING,
//                this._port.getReadTimeout(),
//                ESPLoader.DEFAULT_SERIAL_WRITE_TIMEOUT
//                );
        }
    }

    public void _set_port_baudrate(int baud){
        try {
            this._port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            System.out.println("_set_port_baudrate() 에러");
        }
    }

    public void connect(String mode, int attempts){
        //System.out.println("Connecting...");
        System.out.flush();
        Exception last_error;
        Iterator<ClassicReset> iterator = this._construct_reset_strategy_sequence().iterator();
        while(iterator.hasNext()){
            last_error = this._connect_attempt(iterator.next(), mode);
            if(last_error == null){
                break;
            }
        }
        System.out.println();
    }

    public ArrayList<ClassicReset> _construct_reset_strategy_sequence(){
        int delay = ResetStrategy.DEFAULT_RESET_DELAY;
        int extra_delay = ResetStrategy.DEFAULT_RESET_DELAY + 500;

        ClassicReset classicReset1 = new ClassicReset(this._port, delay);
        ClassicReset classicReset2 = new ClassicReset(this._port, extra_delay);

        ArrayList<ClassicReset> classicResets = new ArrayList<>();
        classicResets.add(classicReset1);
        classicResets.add(classicReset2);

        return classicResets;
    }

    public Exception _connect_attempt(ClassicReset reset_strategy, String mode){
        Exception last_error = null;
        if(!mode.equals("no_reset")){
            try {
                byte[] maxInputBuffer = new byte[this._port.getReadEndpoint().getMaxPacketSize()];
                if(this._port.read(maxInputBuffer, 3000) > 0)
                    this.flushInputBuffer(this._port);
            } catch (IOException e) {
                System.out.println("_connect_attempt() : 입력 버퍼 비우기 실패" + e.getMessage());
            }
            reset_strategy.call(this._connection);
        }
        for (int i = 0; i < 5; i++) {
            try {
                this.flush_input();
                // 플러시 작업 생략
                // this._port.flushIOBuffers();
                this.sync();
                return null;
            } catch (Exception e) {
                System.out.flush();
                try {
                    Thread.sleep(50);
                } catch (Exception err) {
                    System.out.println("Thread 에러");
                }
                last_error = e;
            }
        }

        return last_error;
    }

    public void flush_input(){
        this.flushInputBuffer(this._port);
        /*        try {
            if(this._port.bytesAvailable() > 0)
                this._port.getInputStream().skip(this._port.bytesAvailable());
        } catch (IOException e) {
            System.out.println("flush_input() 에러 : " + e.getMessage());
        }*/
        this._slip_reader = new SlipReader(this._port, false);
    }

    public void sync(){
        byte[] byteArray1 = {0x07, 0x07, 0x12, 0x20};
        byte[] byteArray2 = new byte[32];
        Arrays.fill(byteArray2, (byte)0x55);
        // 이거 바꿔볼까
        byteArray1 = Util._appendArray(byteArray1, byteArray2);
        CommandResult result  = this.command(ESPLoader.ESP_SYNC, byteArray1, SYNC_TIMEOUT);
        if(result == null){
            System.out.println("##########command() 실패##########");
        }
        int val = result.getVal();
        
        this.sync_stub_detected = (val == 0);
        for (int i = 0; i < 7; i++) {
            result = this.command();
            if(result == null) continue;
            val = result.getVal();
            this.sync_stub_detected &= (val == 0);
        }
    }

    public ESP8266StubLoader run_stub(){
        StubFlasher stub = new StubFlasher(ESPLoader.STUB_DIR);

        System.out.println("Uploading stub...");

        for (int i = 0; i < 2; i++) {
            byte[] field = (i==0)?stub.text:stub.data;
            int offs = (i==0)?stub.text_start:stub.data_start;
            int length = field.length;
            int blocks = (length + ESPLoader.ESP_RAM_BLOCK-1) / ESPLoader.ESP_RAM_BLOCK; // 몫을 구하는 부분
            mem_begin(length, blocks, ESPLoader.ESP_RAM_BLOCK, offs);
            for (int j = 0; j < blocks; j++) {
                int seq = j;
                int from_offs = seq * ESPLoader.ESP_RAM_BLOCK;
                int to_offs = from_offs + ESPLoader.ESP_RAM_BLOCK;
                // 잘 안되면 copyOfRange로 복사한 배열 출력해보기 esptool과 같은지 비교
                if(to_offs > field.length)
                    to_offs = field.length;
                byte[] tmpByte = new byte[to_offs-from_offs];
                System.arraycopy(field, from_offs, tmpByte, 0, to_offs-from_offs);
                mem_block(tmpByte, seq);
            }
        }
        System.out.println("Running stub...");
        mem_finish(stub.getEntry());

        byte[] p = null;
        try{
            byte[] mustReadByte = new byte[this._port.getReadEndpoint().getMaxPacketSize()];
            while(true){
                if(this._slip_reader.getLeftReadBytes() != null)
                    break;
                int byteLength = this._port.read(mustReadByte, ESPLoader.DEFAULT_TIMEOUT);
                if(byteLength < 1){
                    continue;
                }
                else{
                    this._slip_reader.setMustReadByte(mustReadByte);
                    this._slip_reader.setMustReadByteLen(byteLength);
                    break;
                }
            }
            p = this._slip_reader.next();
        }catch (IOException e){
            System.out.println("OHAI 읽기 에러");
        }
 


        byte[] byteArr = "OHAI".getBytes(StandardCharsets.US_ASCII);
        if(p != null){
            System.out.println("p : " + Util.byteArrayToHex(p));
            if(!Arrays.equals(p, byteArr)){
                System.out.println("Failed to start stub. Unexpected response: " + Util.byteArrayToHex(p));
                return null;
            }
            else{
                System.out.println("Stub running...");
            }
        }
        

        return new ESP8266StubLoader(this);
    }
    // 반환값 임시로 void
    public void mem_begin(int size, int blocks, int blocksize, int offset){
        if(ESPLoader.IS_STUB){
            StubFlasher stub = new StubFlasher(STUB_DIR);
            int load_start = offset;
            int load_end = offset + size;

            for (int i = 0; i < 2; i++) {
                int stub_start = (i==0)?stub.bss_start:stub.text_start;
                int stub_end = (i==0)?stub.data_start + stub.data.length:stub.text_start + stub.text.length;

                if(load_start < stub_end && load_end > stub_start){
                    String message = String.format(
                        "Software loader is resident at 0x%08x-0x%08x. "
                        + "Can't load binary at overlapping address range 0x%08x-0x%08x. "
                        + "Either change binary loading address, or use the --no-stub "
                        + "option to disable the software loader.",
                        stub_start, stub_end, load_start, load_end
                    );

                    throw new RuntimeException(message);
                }
            }       
            
        }
        // esptool loader.py line835 : struct.pack("<IIII", size, blocks, blocksize, offset)
        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(size);
        tmpBuffer.putInt(blocks);
        tmpBuffer.putInt(blocksize);
        tmpBuffer.putInt(offset);
        
        check_command(
            "enter RAM download mode",
            ESPLoader.ESP_MEM_BEGIN,
            tmpBuffer.array()
            );
    }

    public void mem_block(byte[] data, int seq){
        // esptool loader.py line8 : struct.pack("<IIII", size, blocks, blocksize, offset)
        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(data.length);
        tmpBuffer.putInt(seq);
        tmpBuffer.putInt(0);
        tmpBuffer.putInt(0);

        check_command(
            "write to target RAM", 
            ESPLoader.ESP_MEM_DATA, 
            Util._appendArray(tmpBuffer.array(), data),
            checksum(data, ESPLoader.ESP_CHECKSUM_MAGIC),
            DEFAULT_TIMEOUT
            );
    }

    public void mem_finish(int entrypoint){
        int timeout = IS_STUB?DEFAULT_TIMEOUT:MEM_END_ROM_TIMEOUT;

        // data = struct.pack("<II", int(entrypoint == 0), entrypoint)
        ByteBuffer tmpBuffer = ByteBuffer.allocate(8);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(((entrypoint==0)?1:0));
        tmpBuffer.putInt(entrypoint);
        byte[] data = tmpBuffer.array();
        check_command(
            "leave RAM download mode",
            ESPLoader.ESP_MEM_END,
            data,
            0,
            timeout);
    }

    public int flash_id(){
        int SPIFLASH_RDID = 0x9F;
        byte[] byteArr = null;
        int flash_id = run_spiflash_command(SPIFLASH_RDID, byteArr, 24);
        this.cache.put("flash_id", flash_id);
        return flash_id;
    }
    public int run_spiflash_command(int spiflash_command, byte[] data, int read_bits){
        return run_spiflash_command(spiflash_command, data, read_bits, 0, 0, 0);
    }

    public int run_spiflash_command(int spiflash_command, byte[] data, int read_bits, int addr, int addr_len, int dummy_len){
        int SPI_USR_COMMAND = 1 << 31;
        int SPI_USR_ADDR = 1 << 30;
        int SPI_USR_DUMMY = 1 << 29;
        int SPI_USR_MISO = 1 << 28;
        int SPI_USR_MOSI = 1 << 27;

        int base = ESP8266ROM.SPI_REG_BASE;
        int SPI_CMD_REG = base + 0x00;
        int SPI_ADDR_REG = base + 0x04;
        int SPI_USR_REG = base + ESP8266ROM.SPI_USR_OFFS;
        int SPI_USR1_REG = base + ESP8266ROM.SPI_USR1_OFFS;
        int SPI_USR2_REG = base + ESP8266ROM.SPI_USR2_OFFS;
        int SPI_W0_REG = base + ESP8266ROM.SPI_W0_OFFS;

        int SPI_CMD_USR = 1 << 18;

        int SPI_USR2_COMMAND_LEN_SHIFT = 28;
        int SPI_USR_ADDR_LEN_SHIFT = 26;
        
        int data_bits = 0;
        if(data != null)
            data_bits = data.length*8;
         
        int old_spi_usr = read_reg(SPI_USR_REG);
        int old_spi_usr2 = read_reg(SPI_USR2_REG);

        int flags = SPI_USR_COMMAND;
        if(read_bits > 0) flags |= SPI_USR_MISO;
        if(data_bits > 0) flags |= SPI_USR_MOSI;
        if(addr_len > 0) flags |= SPI_USR_ADDR;
        if(dummy_len > 0) flags |= SPI_USR_DUMMY;
        set_data_length(data_bits, read_bits, SPI_USR1_REG, addr_len, dummy_len);
        write_reg(SPI_USR_REG, flags);
        write_reg(SPI_USR2_REG, (7 << SPI_USR2_COMMAND_LEN_SHIFT) | spiflash_command);

        if(addr != 0 && addr_len > 0)
            write_reg(SPI_ADDR_REG, flags);
        if(data_bits == 0)
            write_reg(SPI_W0_REG, 0);

        write_reg(SPI_CMD_REG, SPI_CMD_USR);
        wait_done(SPI_CMD_REG, SPI_CMD_USR);

        int status = read_reg(SPI_W0_REG);
        write_reg(SPI_USR_REG, old_spi_usr);
        write_reg(SPI_USR2_REG, old_spi_usr2);
        
        return status;
    }

    public void set_data_length(int mosi_bits, int miso_bits, int SPI_USR1_REG, int addr_len, int dummy_len){
        int SPI_DATA_LEN_REG = SPI_USR1_REG;
        int SPI_MOSI_BITLEN_S = 17;
        int SPI_MISO_BITLEN_S = 8;
        int mosi_mask = (mosi_bits == 0)?0:mosi_bits-1;
        int miso_mask = (miso_bits == 0)?0:miso_bits-1;
        // flag 찍어보기
        int flags = (miso_mask << SPI_MISO_BITLEN_S) | (mosi_mask << SPI_MOSI_BITLEN_S);
        if(dummy_len > 0)
            flags |= dummy_len -1;
        if(addr_len > 0)
            flags |= (addr_len -1);
        write_reg(SPI_DATA_LEN_REG, flags);
    }

    public CommandResult write_reg(int addr, int value){
        return write_reg(addr, value, 0xFFFFFFFF, 0, 0);
    }

    public CommandResult write_reg(int addr, int value, int mask, int delay_us, int delay_after_us){
        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(addr);
        tmpBuffer.putInt(value);
        tmpBuffer.putInt(mask);
        tmpBuffer.putInt(delay_us);
        byte[] command = tmpBuffer.array();
        // if문은 안들어갈듯
        if(delay_after_us>0){
            ByteBuffer tmpBuffer2 = ByteBuffer.allocate(16);
            tmpBuffer2.order(ByteOrder.LITTLE_ENDIAN);
            tmpBuffer2.putInt(ESPLoader.UART_DATE_REG_ADDR);
            tmpBuffer2.putInt(0);
            tmpBuffer2.putInt(0);
            tmpBuffer2.putInt(delay_after_us);
            command = Util._appendArray(command, tmpBuffer2.array());
        }
        return check_command("write target memory", ESPLoader.ESP_WRITE_REG, command);
    }

    public int read_reg(int addr){
        return read_reg(addr, ESPLoader.DEFAULT_TIMEOUT);
    }

    public int read_reg(int addr, int timeout){
        ByteBuffer tmpBuffer = ByteBuffer.allocate(4);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(addr);
        
        CommandResult result = command(ESPLoader.ESP_READ_REG, tmpBuffer.array(), timeout);
        if(result == null){
            result = command(ESPLoader.ESP_READ_REG, tmpBuffer.array(), timeout);
        }

        if(result.getData()[0] != 0){
            System.out.println("read_reg() 에러");
        }

        return result.getVal();
    }

    public void wait_done(int SPI_CMD_REG, int SPI_CMD_USR){
        for (int i = 0; i < 10; i++) {
            if((read_reg(SPI_CMD_REG) & SPI_CMD_USR) == 0) 
                return;
        }
    }

    public CommandResult check_command(String op_description, int op, byte[] data){
        return check_command(op_description, op, data, 0, DEFAULT_TIMEOUT);
    }
    public CommandResult check_command(String op_description, int op, byte[] data, int timeout){
        return check_command(op_description, op, data, 0, timeout);
    }

    public CommandResult check_command(String op_description, int op, byte[] data, int chk, int timeout){
        boolean log = Util.log(op);

        CommandResult result = command(op, data, chk, timeout);

        if(result.getData().length < ESPLoader.STATUS_BYTES_LENGTH){
            System.out.println("check_command 에러 1");
            return null;
        }
        byte[] byte1 = new byte[1];
        byte[] byte2 = new byte[1];
        byte1[0] = result.getData()[result.getData().length-2];
        byte2[0] = result.getData()[result.getData().length-1];
        
        byte[] status_bytes = Util._appendArray(byte1, byte2);
        if(log) System.out.println(Util.byteArrayToHex(status_bytes));
        
        if(status_bytes[0] != 0x00){
            System.out.println("check_command 에러 2");
            return null;
        }
        // 아래의 조건문은 서로 다른 것을 반환한다고 생각.
        if(result.getData().length > ESPLoader.STATUS_BYTES_LENGTH){
            byte[] tmp = new byte[result.getData().length-2];
            System.arraycopy(result.getData(), 0, tmp, 0, result.getData().length-2);
            result.setData(tmp);
            result.setVal(0);
            return result;
        }
        else {
            // val을 반환함.
            result.setData(null);
            return result;
        }
    }
    
    public CommandResult command(){
        return command(-1, new byte[0], 0, true, ESPLoader.DEFAULT_TIMEOUT);
    }

    public CommandResult command(int op, byte[] data, int chk, int timeout){
        return command(op, data, chk, true, timeout);
    }
    public CommandResult command(int op, byte[] data, int timeout){
        return command(op, data, 0, true, timeout);
    }

    public CommandResult command(int op, byte[] data, int chk, boolean wait_response, int timeout){
        boolean log = Util.log(op);
        int new_timeout = 0;
        new_timeout = 100;
        this._slip_reader.setTimeout(new_timeout);
//        if(timeout < 500){
//            new_timeout = timeout;
//            this._slip_reader.setTimeout(new_timeout);
//        }
//        else if(op == ESPLoader.ESP_FLASH_DEFL_DATA || op == ESPLoader.ESP_SPI_FLASH_MD5){
//            new_timeout = Math.min(timeout, MAX_TIMEOUT);
//            this._slip_reader.setTimeout(new_timeout);
//        }
//        else{
//            new_timeout = 300;
//            this._slip_reader.setTimeout(new_timeout);
//        }

        try {
            if(op != -1){
                ByteBuffer buffer = ByteBuffer.allocate(8);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                buffer.put(((byte)0x00));
                buffer.put((byte)op);
                buffer.putShort((short)data.length);
                buffer.putInt(chk);
    
                byte[] pkt = Util._appendArray(buffer.array(), data);
                this.write(pkt, op, ESPLoader.DEFAULT_SERIAL_WRITE_TIMEOUT);
            }

            if(!wait_response){
                return null;
            }
            for (int retry = 0; retry < 100; retry++) {
                byte[] mustReadByte = new byte[this._port.getReadEndpoint().getMaxPacketSize()];

                byte[] p = null;
                if(op != ESPLoader.ESP_SYNC){
                    int cnt = 0;
                    while(true){
                        cnt +=1;
                        if(this._slip_reader.getLeftReadBytes() != null)
                            break;
                        int byteLength = this._port.read(mustReadByte, new_timeout);
                        if(byteLength < 1){
                            continue;
                        }
                        else{
                            this._slip_reader.setMustReadByte(mustReadByte);
                            this._slip_reader.setMustReadByteLen(byteLength);
                            break;
                        }
                    }
                }
                p = this._slip_reader.next();

                if(p != null){
                    //if(log) System.out.println("p : " + Util.byteArrayToHex(p));
                }
                try {
                    if(p.length <10){
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                byte[] byte2 = new byte[2];
                byte[] byte4 = new byte[4];
                byte resp = p[0];
                byte op_ret = p[1];
                for (int i = 0; i < byte2.length; i++) {
                    byte2[i] = p[i+2];
                }
                int len_ret = Util.readShort(byte2);
                for (int i = 0; i < byte4.length; i++) {
                    byte4[i] = p[i+4];
                }
                int val = Util.readInt(byte4);
                if(resp != 1){
                    continue;
                }
                data = new byte[p.length-8];
                System.arraycopy(p, 8, data, 0, data.length);
                if(op_ret == op || op == -1){
                    return new CommandResult(val, data);
                }
                if(data[0] != 0 && data[1] == ESPLoader.ROM_INVALID_RECV_MSG){
                    this.flush_input();
                } 
            }
        }catch (Exception e) {
            System.out.println("command() 에러: "+e.getMessage());
        }
        return null;
    }
    public int checksum(byte[] data){
        return checksum(data, ESPLoader.ESP_CHECKSUM_MAGIC, 0);
    }
    public int checksum(byte[] data, int op){
        return checksum(data, ESPLoader.ESP_CHECKSUM_MAGIC, op);
    }

    public int checksum(byte[] data, int state, int op){
        // if(op == ESPLoader.ESP_FLASH_DEFL_DATA)
        //     Util.writeFile(0, data);
        // 파이썬은 unsigned byte를 사용해서 음수의 바이트를 변환함.
        for (byte b : data){
            int n = 0;
            if(b < 0) n = (int)b + 256;
            else n = (int)b;
            state ^= n;
        }
        return state;
    }
        public void write(byte[] pkt, int op, int timeout) throws IOException{
        boolean log = Util.log(op);
        
        byte[] buf;
        buf = Util.replacePacket(pkt);
//        if(log) System.out.println("buf : " + Util.byteArrayToHex(buf));
//        if(log) System.out.println("buflen : " + buf.length);
        // if(op == ESPLoader.ESP_FLASH_DEFL_DATA)
        //     Util.writeFile(op, buf);
        this._port.write(buf, buf.length, timeout);
    }

    public void flash_set_parameters(int size){
        int fl_id = 0;
        int total_size = size;
        int block_size = 64 * 1024;
        int sector_size = 4 * 1024;
        int page_size = 256;
        int status_mask = 0xFFFF;

        ByteBuffer tmpBuffer = ByteBuffer.allocate(24);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(fl_id);
        tmpBuffer.putInt(total_size);
        tmpBuffer.putInt(block_size);
        tmpBuffer.putInt(sector_size);
        tmpBuffer.putInt(page_size);
        tmpBuffer.putInt(status_mask);
        this.check_command("set SPI params",
                            ESPLoader.ESP_SPI_SET_PARAMS,
                            tmpBuffer.array()
                            );
    }
    public int flash_defl_begin(int size, int compsize, int offset){
        int num_blocks = (compsize + ESPLoader.FLASH_WRITE_SIZE -1) / ESPLoader.FLASH_WRITE_SIZE;
        int erase_blocks = (size + ESPLoader.FLASH_WRITE_SIZE -1) / ESPLoader.FLASH_WRITE_SIZE;

        int write_size = size;
        int timeout = ESPLoader.DEFAULT_TIMEOUT;

        System.out.println("Compressed " + size + " bytes to " + compsize + "...");

        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(write_size);
        tmpBuffer.putInt(num_blocks);
        tmpBuffer.putInt(ESPLoader.FLASH_WRITE_SIZE);
        tmpBuffer.putInt(offset);

        check_command(
            "enter compressed flash mode",
            ESPLoader.ESP_FLASH_DEFL_BEGIN, 
            tmpBuffer.array(),
            timeout);

        return num_blocks;
    }

    public void flash_defl_block(byte[] data, int seq, int timeout){
        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
                
        for (int attempts = ESPLoader.WRITE_BLOCK_ATTEMPTS; attempts > -1 ; attempts--) {
            try {
                tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
                tmpBuffer.putInt(data.length);
                tmpBuffer.putInt(seq);
                tmpBuffer.putInt(0);
                tmpBuffer.putInt(0);
                byte[] pkt = Util._appendArray(tmpBuffer.array(), data);
                CommandResult check = check_command(
                                        String.format("write compressed data to flash after seq %d", seq),
                                        ESPLoader.ESP_FLASH_DEFL_DATA,
                                        pkt,
                                        checksum(data, ESPLoader.ESP_FLASH_DEFL_DATA),
                                        timeout);
                break;
            } catch (Exception e) {
                if(attempts != 0){
                    System.out.println("flash_defl_block() 에러. 남은 횟수("+ attempts + ")만큼 다시 실행합니다.");
                    continue;
                }
                else{
                    System.out.println("flash_defl_block() 에러 : " + e.getMessage());
                    break;
                }
            }
        }
    }
    public void flash_defl_finish(boolean reboot){
        ByteBuffer tmpBuffer = ByteBuffer.allocate(4);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(1);

        check_command(
            "leave compressed flash mode",
            ESPLoader.ESP_FLASH_DEFL_END,
            tmpBuffer.array());
        this.in_bootloader = false;
    }

    public Md5sumResult flash_md5sum(int addr, int size){
        int timeout = timeout_per_mb(ESPLoader.MD5_TIMEOUT_PER_MB, size);
        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(addr);
        tmpBuffer.putInt(size);
        tmpBuffer.putInt(0);
        tmpBuffer.putInt(0);
        CommandResult res = check_command(
                                "calculate md5sum",
                                ESPLoader.ESP_SPI_FLASH_MD5,
                                tmpBuffer.array(),
                                timeout);

        Md5sumResult m5res = null;
        if(res.getData().length == 32){
            String resToString = new String(res.getData(), StandardCharsets.UTF_8);
            m5res = new Md5sumResult(resToString, resToString.length());
            return m5res;

        }
        else if(res.getData().length == 16){
            String resToString = Util.hexify(res.getData()).toLowerCase();
            m5res = new Md5sumResult(resToString, resToString.length());
            return m5res;
        }
        else{
            System.out.println("flash_md5sum() 에러");
            return null;
        }
    }

    public int flash_begin(int size, int offset){
        int num_blocks = (size + ESPLoader.FLASH_WRITE_SIZE - 1) / ESPLoader.FLASH_WRITE_SIZE;
        int erase_size = ESP8266ROM.get_erase_size(offset, size);

        // stub true일때
        int timeout = ESPLoader.DEFAULT_TIMEOUT;

        ByteBuffer tmpBuffer = ByteBuffer.allocate(16);
        tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
        tmpBuffer.putInt(erase_size);
        tmpBuffer.putInt(num_blocks);
        tmpBuffer.putInt(ESPLoader.FLASH_WRITE_SIZE);
        tmpBuffer.putInt(offset);

        check_command(
            "enter Flash download mode",
            ESPLoader.ESP_FLASH_BEGIN,
            tmpBuffer.array(),
            timeout);
        
        return num_blocks;
    }
    public int timeout_per_mb(int second_per_mb, int size_bytes){
        int result = second_per_mb * (size_bytes / 1000000);
        if(result < ESPLoader.DEFAULT_TIMEOUT)
            return ESPLoader.DEFAULT_TIMEOUT;
        return result;
    }

    public void hard_reset(){
        System.out.println("Hard resetting via RTS pin...");
        new HardReset(this._port).call(this._connection);
    }




    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    void flushInputBuffer(UsbSerialPort port){
        try {
            byte[] maxInputBuffer = new byte[port.getReadEndpoint().getMaxPacketSize()];
            while(port.read(maxInputBuffer,500) > 0){
            }
        }catch (IOException e){
            Log.e("fun_flushInputBuffer_err", "flushInputBuffer: " + e.getMessage());
        }
    }



    ESPLoader(){
    }

    public boolean getSecureDownloadMode(){
        return this.secure_download_mode;
    }
    public UsbSerialPort getPort() {
        return this._port;
    }
    public Map<String, Integer> getCache() {
        return cache;
    }
    public SlipReader getSlipReader(){
        return this._slip_reader;
    }
    public void setSecureDownloadMode(boolean secure_download_mode){
        this.secure_download_mode = secure_download_mode;
    }
    public void setPort(UsbSerialPort port){
        this._port = port;
    }
    public void setCache(Map<String, Integer> cache){
        this.cache = cache;
    }
    public void setSlipReader(SlipReader slipReader){
        this._slip_reader = slipReader;
    }
    public int getWriteFlashAttempts(){
        return ESPLoader.WRITE_FLASH_ATTEMPTS;
    }
    public int getDefalutTimeout(){
        return ESPLoader.DEFAULT_TIMEOUT;
    }
    public int getFlashWriteSize(){
        return ESPLoader.FLASH_WRITE_SIZE;
    }
    public int getEraseWriteTimeoutPerMb() {
        return ESPLoader.ERASE_WRITE_TIMEOUT_PER_MB;
    }
    public int getChipDetectMagicRegAddr() {
        return ESPLoader.CHIP_DETECT_MAGIC_REG_ADDR;
    }
}

class StubFlasher {
    public static String JSON_DIR = "";
    
    byte[] text;
    int text_start;
    int entry;
    byte[] data;
    int data_start;
    int bss_start;

    StubFlasher(String json_path){
        byte[] textByte = Base64.getDecoder().decode("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");
        this.text = textByte;
        this.text_start = 1074843648;
        this.entry = 1074843652;
        byte[] dataByte = Base64.getDecoder().decode("CIH+PwUFBAACAwcAAwMLANTXEEAL2BBAOdgQQNbYEECF5xBAOtkQQJDZEEDc2RBAhecQQKLaEEAf2xBA4NsQQIXnEECF5xBAeNwQQIXnEEBV3xBAHOAQQFfgEECF5xBAhecQQPPgEECF5xBA2+EQQIHiEEDA4xBAf+QQQFDlEECF5xBAhecQQIXnEECF5xBAfuYQQIXnEEB05xBAsN0QQKnYEEDC5RBAydoQQBvaEECF5xBACOcQQE/nEECF5xBAhecQQIXnEECF5xBAhecQQIXnEECF5xBAhecQQELaEEB/2hBA2uUQQAEAAAACAAAAAwAAAAQAAAAFAAAABwAAAAkAAAANAAAAEQAAABkAAAAhAAAAMQAAAEEAAABhAAAAgQAAAMEAAAABAQAAgQEAAAECAAABAwAAAQQAAAEGAAABCAAAAQwAAAEQAAABGAAAASAAAAEwAAABQAAAAWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAABAAAAAgAAAAIAAAADAAAAAwAAAAQAAAAEAAAABQAAAAUAAAAGAAAABgAAAAcAAAAHAAAACAAAAAgAAAAJAAAACQAAAAoAAAAKAAAACwAAAAsAAAAMAAAADAAAAA0AAAANAAAAAAAAAAAAAAADAAAABAAAAAUAAAAGAAAABwAAAAgAAAAJAAAACgAAAAsAAAANAAAADwAAABEAAAATAAAAFwAAABsAAAAfAAAAIwAAACsAAAAzAAAAOwAAAEMAAABTAAAAYwAAAHMAAACDAAAAowAAAMMAAADjAAAAAgEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAQAAAAEAAAABAAAAAgAAAAIAAAACAAAAAgAAAAMAAAADAAAAAwAAAAMAAAAEAAAABAAAAAQAAAAEAAAABQAAAAUAAAAFAAAABQAAAAAAAAAAAAAAAAAAABAREgAIBwkGCgULBAwDDQIOAQ8AAQEAAAEAAAAEAAAA");
        this.data = dataByte;
        this.data_start = 1073720488;
        this.bss_start = 1073643776;
        /*JSONObject stub = null;
        JSONParser parser = new JSONParser();
        System.out.println(json_path);

        try {
            Reader reader = new FileReader(json_path);
            stub = (JSONObject)parser.parse(reader);
        } catch (Exception e) {
            System.out.println("stubJson 읽기 오류:" + e.getMessage());
        }
        if(stub != null){
            System.out.println("json 데이터 추출 진입");
            byte[] textByte = Base64.getDecoder().decode((String)stub.get("text"));
            this.text = textByte;
            this.text_start = Integer.parseInt(stub.get("text_start").toString());
            this.entry = Integer.parseInt(stub.get("entry").toString());
    
            try {
                byte[] dataByte = Base64.getDecoder().decode((String)stub.get("data"));
                this.data = dataByte;
                this.data_start = Integer.parseInt(stub.get("data_start").toString());
            } catch (Exception e) {
                System.out.println("json data 읽기 실패:" + e.getMessage());
            }
            this.bss_start = Integer.parseInt(stub.get("bss_start").toString());
        }
        else{
            System.out.println("stub == null");
        }*/
    }
    public byte[] getData() {
        return data;
    }
    public  byte[] getText() {
        return text;
    }
    public int getBss_start() {
        return bss_start;
    }
    public int getData_start() {
        return data_start;
    }
    public int getEntry() {
        return entry;
    }
    public int getText_start() {
        return text_start;
    }
}

class SlipReader implements Iterator<byte[]> {
    private UsbSerialPort port;
    private boolean traceEnabled;
    private long lastTrace;
    private boolean inEscape = false;
    private byte[] partialPacket = null;
    private boolean successfulSlip = false;
    private byte[] leftReadBytes = null;

    private byte[] mustReadByte = null;
    private int mustReadByteLen = 0;

    private int timeout;
    public SlipReader(UsbSerialPort port, boolean traceEnabled) {
        this.port = port;
        this.traceEnabled = traceEnabled;
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public byte[] next() {
        try{
            byte[] readBytes = new byte[this.port.getReadEndpoint().getMaxPacketSize()];
            if(leftReadBytes == null){
                int readPacketLength = port.read(readBytes, timeout);
                if(readPacketLength > 0){
                    byte[] realReadBytes = new byte[readPacketLength];
                    System.arraycopy(readBytes, 0, realReadBytes, 0, readPacketLength);
                    readBytes = realReadBytes;
                }
                else
                    readBytes = new byte[0];
            }
            else{
                readBytes = leftReadBytes;
            }
            if(mustReadByte != null){
                byte[] realReadBytes = new byte[mustReadByteLen];
                System.arraycopy(mustReadByte, 0, realReadBytes, 0, mustReadByteLen);
                readBytes = Util._appendArray(realReadBytes, readBytes);
                mustReadByte = null;
            }

            int index = 0;
            int forCnt = 0;
            for (byte b : readBytes) {
                if(forCnt == 1) index++;
                if(forCnt == 0) forCnt++;
                if (partialPacket == null) { // waiting for packet header
                    if (b == (byte) 0xc0) {
                        partialPacket = new byte[0];
                    } else {
                        Log.e("fun_slipReader_next_err", "next: 남은 데이터를 읽지 못함.");
                    }
                } else if (inEscape) { // part-way through escape sequence
                    inEscape = false;
                    if (b == (byte) 0xdc) {
                        partialPacket = appendByte(partialPacket, (byte) 0xc0);
                    } else if (b == (byte) 0xdd) {
                        partialPacket = appendByte(partialPacket, (byte) 0xdb);
                    } else {
                        Log.e("fun_slipReader_next_err", "next: 남은 데이터를 읽지 못함2.");
                    }
                } else if (b == (byte) 0xdb) { // start of escape sequence
                    inEscape = true;
                } else if (b == (byte) 0xc0) { // end of packet
                    byte[] packet = partialPacket;
                    partialPacket = null;
                    successfulSlip = true;
                    byte[] tmp = new byte[readBytes.length-index-1];
                    System.arraycopy(readBytes, index+1, tmp, 0, readBytes.length-index-1);
                    //System.out.println("tmp : " + Util.byteArrayToHex(tmp));
                    leftReadBytes = tmp;
                    if(leftReadBytes.length == 0) leftReadBytes = null;
                    return packet;
                } else { // normal byte in packet
                    partialPacket = appendByte(partialPacket, b);
                }
            }
        }catch (IOException e){
            Log.e("fun_slipReader_next_err", "next: " + e.getMessage());
        }

        leftReadBytes = null;
        return null;
    }

    private byte[] appendByte(byte[] array, byte b) {
        byte[] newArray = new byte[array.length + 1];
        System.arraycopy(array, 0, newArray, 0, array.length);
        newArray[array.length] = b;
        return newArray;
    }

    public UsbSerialPort getPort() {
        return port;
    }
    public byte[] getLeftReadBytes() {
        return leftReadBytes;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setMustReadByte(byte[] mustReadByte) {
        this.mustReadByte = mustReadByte;
    }
    public void setMustReadByteLen(int len){
        this.mustReadByteLen = len;
    }
}

class CommandResult{
    private int val;
    private byte[] data;
    CommandResult(int val, byte[] data){
        this.val = val;
        this.data = data;
    }
    public byte[] getData() {
        return data;
    }
    public int getVal() {
        return val;
    }
    public void setData(byte[] data) {
        this.data = data;
    }
    public void setVal(int val) {
        this.val = val;
    }
}

class Md5sumResult{
    private String res;
    private int resLen;

    Md5sumResult(String res , int resLen){
        this.res = res;
        this.resLen = resLen;
    }
    public String getRes() {
        return res;
    }
    public int getResLen() {
        return resLen;
    }
}