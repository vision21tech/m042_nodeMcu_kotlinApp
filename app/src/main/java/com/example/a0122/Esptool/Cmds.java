package com.example.a0122.Esptool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Cmds{
    public static Map<Integer, String> DETECTED_FLASH_SIZES = new HashMap<Integer, String>(){{
        put(0x12, "256KB");
        put(0x13, "512KB");
        put(0x14, "1MB");
        put(0x15, "2MB");
        put(0x16, "4MB");
        put(0x17, "8MB");
        put(0x18, "16MB");
        put(0x19, "32MB");
        put(0x1A, "64MB");
        put(0x1B, "128MB");
        put(0x1C, "256MB");
        put(0x20, "64MB");
        put(0x21, "128MB");
        put(0x22, "256MB");
        put(0x32, "256KB");
        put(0x33, "512KB");
        put(0x34, "1MB");
        put(0x35, "2MB");
        put(0x36, "4MB");
        put(0x37, "8MB");
        put(0x38, "16MB");
        put(0x39, "32MB");
        put(0x3A, "64MB");
    }};
   

    public static String detect_flash_size(ESP8266StubLoader espStubLoader){
        int flash_id = espStubLoader.flash_id();
        int size_id = flash_id >> 16;
        String flash_size = DETECTED_FLASH_SIZES.get(size_id);

        return flash_size;
    }

    public static void write_flash(ESP8266StubLoader esp, byte[] data) throws IOException, NoSuchAlgorithmException{
        boolean compress = true;
        int flash_end = Util.flash_size_bytes(detect_flash_size(esp));
        //Path argfile = Paths.get(EFJ.getFilename());
        int address = EFJ.getAddress();
        
        if(address + data.length > flash_end){
            System.out.println("파일의 길이가 플래시 사이즈를 초과했습니다.");
        }

        //int write_end = address + (int)Files.size(argfile);
        System.out.println("Flash will be erased..."); 
        
        int offs = address;
        
        //byte[] image = Util.pad_to(Files.readAllBytes(argfile), 4);
        byte[] image = Util.pad_to(data, 4);
        

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(image);
        byte[] calcmd5 = md.digest();
        int uncsize = image.length;
        
        if(compress){
            byte[] uncimage = image;
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(uncimage);
            deflater.finish();
            
            byte[] tmpByteArr = new byte[uncimage.length];
            int compressedImageLength = deflater.deflate(tmpByteArr);
            deflater.end();
            byte[] cimage =  new byte[compressedImageLength];
            System.arraycopy(tmpByteArr, 0, cimage, 0, compressedImageLength);
            image = cimage;
        }
        byte[] original_image = image;
        int blocks = 0;
        for (int attempt = 1; attempt < esp.getWriteFlashAttempts(); attempt++) {
            if(compress){
                blocks = esp.flash_defl_begin(uncsize, image.length, address);
            }
        }

        int seq = 0;
        int byte_sent = 0;
        int byte_written = 0;
        int timeout = esp.getDefalutTimeout();

        Inflater inflater = new Inflater();
        
        // try {
        //     File file = new File("d:\\zipjava.txt");
        //     file.createNewFile();
        //     FileWriter fw = new FileWriter(file);            
        //     BufferedWriter writer = new BufferedWriter(fw);
        //     writer.write(Util.byteArrayToHex(original_image));
        //     writer.close();
        // } catch (Exception e) {
        //     // TODO: handle exception
        // }
        while(image.length>0){
//            System.out.println(String.format(
//                "Writing at 0x%08x... (%d %%)",
//                address+byte_written, 100 * (seq + 1) / blocks));
            // System.out.println("\r" + 
            //     String.format(
            //         "Writing at 0x%08x... (%d %%)",
            //         address+byte_written, 100 * (seq + 1) / blocks));
            // System.out.flush();
            byte[] block = new byte[esp.getFlashWriteSize()];

            if(image.length - esp.getFlashWriteSize() >= 0)
                System.arraycopy(image, 0, block, 0, esp.getFlashWriteSize());
            else{
                System.arraycopy(image, 0, block, 0, image.length);
            }
            // byte[] tmp = new byte[10];
            // System.arraycopy(block, 0, tmp, 0, 10);
            // System.out.println("block : " + Util.byteArrayToHex(tmp));
            if(compress){
                int block_uncompressed = Util.decompress(block, inflater);
                
                byte_written += block_uncompressed;
                int block_timeout = Math.max(
                    esp.getDefalutTimeout(),
                    esp.timeout_per_mb(esp.getEraseWriteTimeoutPerMb(), block_uncompressed)
                    );
                // System.out.println("block : " + Util.byteArrayToHex(block));
                // Util.writeFile(10, block);

                esp.flash_defl_block(block, seq, timeout);
                timeout = block_timeout;
                // try {
                //     Thread.sleep(block_timeout);
                    
                // } catch (Exception e) {
                //     // TODO: handle exception
                // }
            }
            byte_sent += block.length;
            if(image.length - esp.getFlashWriteSize() > 0){
                byte[] tmpImage = new byte[image.length - esp.getFlashWriteSize()];
                System.arraycopy(image, esp.getFlashWriteSize(), tmpImage, 0, image.length-esp.getFlashWriteSize());
                image = tmpImage;
                seq += 1;
            }
            else{
                image = new byte[0];
            }
            
        }
        inflater.end();
        // stub이 true일때
        esp.read_reg(esp.getChipDetectMagicRegAddr(), timeout);

        Md5sumResult m5res = esp.flash_md5sum(address, uncsize);
        if(m5res != null){
            if(m5res.getResLen() == 32){
                String strCalcmd5 = new String(calcmd5, StandardCharsets.UTF_8);
                if(m5res.getRes().equals(strCalcmd5)){
                    //System.out.println("성공!");
                }
            }
            else if(m5res.getResLen() == 16){
                String strCalcmd5 = Util.hexify(calcmd5).toLowerCase();
                if(m5res.getRes().equals(strCalcmd5)){
                    //System.out.println("성공!");
                }
            }
            else{
                System.out.println("실패!");
                System.out.println("m5res : " + m5res);
                System.out.println("calcmd5 : " + calcmd5);
            }
        }
        System.out.println("\nLeaving...");
        
        esp.flash_begin(0, 0);

        esp.flash_defl_finish(false);
    }
    public byte[] _update_image_flash_params(ESP8266StubLoader esp, int address, byte[] image){
        if(image.length < 8)
            return image;
            
        return null;
    }
}