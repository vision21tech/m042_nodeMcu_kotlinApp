package com.example.a0122.Esptool;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.zip.Inflater;

public class Util {
    private static int cnt = 0;
    public static byte[] _appendArray(byte arr1[], byte arr2[]) {

		byte c[] = new byte[arr1.length + arr2.length];

		for (int i = 0; i < arr1.length; i++) {
			c[i] = arr1[i];
		}
		for (int j = 0; j < arr2.length; j++) {
			c[arr1.length + j] = arr2[j];
		}
		return c;
	}

	// byte배열 내용 교체
	// db->dbdd, c0->dbdc
	public static byte[] replacePacket(byte[] packet) {
        byte[] tempBuf = new byte[packet.length * 2];
        int index = 0;

        for (byte b : packet) {
            if (b == (byte)0xDB) {
                tempBuf[index++] = (byte)0xDB;
                tempBuf[index++] = (byte)0xDD;
            } else if (b == (byte)0xC0) {
                tempBuf[index++] = (byte)0xDB;
                tempBuf[index++] = (byte)0xDC;
            } else {
                tempBuf[index++] = b;
            }
        }

        byte[] buf = new byte[index + 2];
        buf[0] = (byte)0xC0;
        System.arraycopy(tempBuf, 0, buf, 1, index);
        buf[buf.length - 1] = (byte)0xC0;

        return buf;
    }
    public static int readShort(byte[] byteArr){
        int result = 0;
        if(byteArr.length == 2)
            result =  byteArr[0] & 0xff | byteArr[1] << 8;
        return result;
    }
    public static int readInt(byte[] byteArr){
        int result = 0;
        if(byteArr.length == 4)
            result =  byteArr[0] & 0xff | byteArr[1] << 8 | byteArr[2] << 16 | byteArr[3] << 24;
        return result;
    }
    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder();
        for(final byte b: a)
            sb.append(String.format("\\x%02x", b&0xff));
        return sb.toString();
    }

    private static byte[] concatByte(byte[] first, byte[] second) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(first);
            outputStream.write(second);
        } catch (IOException e) {
            System.out.println("concatByte() 에러 : " + e.getMessage());
        }
        byte[] result = outputStream.toByteArray();
        return result;
    }

    private static byte[] concatByte(byte first, byte second) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(first);
        outputStream.write(second);
       
        byte[] result = outputStream.toByteArray();
        return result;
    }
    public static byte[] _int_to_bytearray(int i) {
		byte ret[] = { (byte) (i & 0xff), (byte) ((i >> 8) & 0xff), (byte) ((i >> 16) & 0xff),
				(byte) ((i >> 24) & 0xff) };
		return ret;
	}
    public static byte[] pad_to(byte[] data, int alignment){
        return pad_to(data, alignment, (byte)0xFF);
    }

    public static byte[] pad_to(byte[] data, int alignment, byte pad_character){
        byte[] pad_character_arr = {pad_character};
        int pad_mod = data.length % alignment;
        if(pad_mod != 0){
            for (int i = 0; i < alignment - pad_mod; i++) {
                data = Util._appendArray(data, pad_character_arr);
            }
        }
        return data;
    }

    public static int flash_size_bytes(String size){
        return Integer.parseInt(size.replaceAll("[^0-9]", "")) * 1024 * 1024;
    }

    public static int decompress(byte[] block, Inflater inflater){
        inflater.setInput(block);

        byte[] buffer = new byte[30000];
        int length = 0;
        try {
            length = inflater.inflate(buffer);
            
        } catch (Exception e) {
            System.out.println("decompress() 에러 : " + e.getMessage());
        }
        return length;
    }
    public static String hexify(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        String format = "%02X";
        
        for (byte b : bytes) {
            hexString.append(String.format(format, b));
        }
        
        return hexString.toString();
    } 
    public static boolean log(int op){
        boolean log = false;
        //if(op == ESPLoader.ESP_SYNC) log = true;
        //if(op == -1) log = true;
        //if(op == ESPLoader.ESP_MEM_BEGIN) log = true;
        //if(op == ESPLoader.ESP_MEM_DATA) log = true;
        //if(op == ESPLoader.ESP_MEM_END) log = true;
        //if(op == ESPLoader.ESP_READ_REG) log = true;
        //if(op == ESPLoader.ESP_WRITE_REG) log = true;
        // if(op == ESPLoader.ESP_SPI_SET_PARAMS) log = true;
        // if(op == ESPLoader.ESP_FLASH_DEFL_BEGIN) log = true;
        //if(op == ESPLoader.ESP_FLASH_DEFL_DATA) log = true;
        //if(op == ESPLoader.ESP_SPI_FLASH_MD5) log = true;

        return log;
    }
    public static void writeFile(int block_uncompressed, byte[] block){
        File file = null;
        if(cnt < 10){
            file = new File("d:\\espdata\\espchk\\java\\" + "0" + cnt + ".txt");
        }
        else{
            file = new File("d:\\espdata\\espchk\\java\\" + cnt + ".txt");
        }

        try {            
            FileOutputStream fileOutputStream = new FileOutputStream(file, false);        
        } catch (FileNotFoundException e) {
            e.printStackTrace();        
        }
        try {
            FileWriter fw = new FileWriter(file);
            BufferedWriter writer = new BufferedWriter(fw);
            writer.write(Util.byteArrayToHex(block).toString());
            writer.close();
        } catch (Exception e) {
            
        }
        cnt++;
    }
}

class HexFormatter {
    private byte[] binaryData;
    private boolean autoSplit;

    public HexFormatter(byte[] binaryData, boolean autoSplit) {
        this.binaryData = binaryData;
        this.autoSplit = autoSplit;
    }

    public HexFormatter(byte[] binaryData) {
        this(binaryData, true);
    }

    @Override
    public String toString() {
        if (autoSplit && binaryData.length > 16) {
            StringBuilder result = new StringBuilder();
            int offset = 0;

            while (offset < binaryData.length) {
                int chunkSize = Math.min(16, binaryData.length - offset);
                byte[] line = new byte[chunkSize];
                System.arraycopy(binaryData, offset, line, 0, chunkSize);
                
                StringBuilder hexPart = new StringBuilder();
                StringBuilder asciiPart = new StringBuilder();

                for (int i = 0; i < line.length; i++) {
                    hexPart.append(String.format("%02x ", line[i]));

                    char c = (char) line[i];
                    if (c == ' ' || (c >= 32 && c < 127)) {
                        asciiPart.append(c);
                    } else {
                        asciiPart.append('.');
                    }
                }

                String hexString = hexPart.toString().trim();
                if (hexString.length() > 23) { // split into two columns
                    String left = hexString.substring(0, 23).trim();
                    String right = hexString.substring(23).trim();
                    result.append(String.format("\n    %-24s %-24s | %s", left, right, asciiPart.toString()));
                } else {
                    result.append(String.format("\n    %-24s | %s", hexString, asciiPart.toString()));
                }

                offset += 16;
            }

            return result.toString();
        } else {
            return hexify(binaryData, false);
        }
    }

    private String hexify(byte[] data, boolean addSpace) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : data) {
            if (addSpace) {
                hexString.append(String.format("%02x ", b));
            } else {
                hexString.append(String.format("%02x", b));
            }
        }
        return hexString.toString();
    }
}
