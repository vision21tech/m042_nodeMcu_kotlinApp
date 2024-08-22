import ArduinoUploader.ArduinoSketchUploader
import ArduinoUploader.ArduinoUploaderException
import ArduinoUploader.Config.Arduino
import ArduinoUploader.Config.McuIdentifier
import ArduinoUploader.Config.Protocol
import CSharpStyle.IProgress
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.a0122.Boards
import com.example.a0122.LineReader
import com.example.a0122.MainActivity
import com.example.a0122.SerialPortStreamImpl
import com.example.a0122.UsbSerialManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.Reader

class UsbHelper(private val context: Context) {

    interface UsbConnectListener {
        fun onUsbConnectChange(state: UsbConnectState)
        fun onUsbPermissionGranted(usbKey: String)
    }
    private var usbConnectListener: UsbConnectListener? = null
    private var usbSerialManager: UsbSerialManager? = null
    enum class UsbConnectState {
        DISCONNECTED,
        CONNECT
    }
    fun usbConnectChange(state: UsbConnectState) {
        if (state == UsbConnectState.DISCONNECTED) {
            //if (requestButton != null) requestButton.setVisibility(View.INVISIBLE);
            //if (fab != null) fab.hide();
        } else if (state == UsbConnectState.CONNECT) {
            //if (requestButton != null) requestButton.setVisibility(View.VISIBLE);
        }
    }
    private var usbStatus: UsbConnectState? = null

    private val mUsbHardwareReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbSerialManager.ACTION_USB_PERMISSION_REQUEST) {
                val granted = intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                if (granted) {
                    val grantedDevice = intent.extras!!.getParcelable<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    usbConnectListener?.onUsbPermissionGranted(grantedDevice!!.deviceName)
                    val it = Intent(UsbSerialManager.ACTION_USB_PERMISSION_GRANTED)
                    context.sendBroadcast(it)
                } else {
                    val it = Intent(UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED)
                    context.sendBroadcast(it)
                }
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val it = Intent(UsbSerialManager.ACTION_USB_CONNECT)
                context.sendBroadcast(it)
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val it = Intent(UsbSerialManager.ACTION_USB_DISCONNECTED)
                context.sendBroadcast(it)
            }
        }
    }

    init {
        setUsbFilter()
    }

    private fun setUsbFilter() {
        val filter = IntentFilter()
        filter.addAction(UsbSerialManager.ACTION_USB_PERMISSION_REQUEST)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        context.registerReceiver(mUsbHardwareReceiver, filter)
    }

    fun requestDevicePermission(key: String?) {
        // Request USB device permission
        usbSerialManager!!.getDevicePermission(key)
    }

    fun checkDevicePermission(key: String?): Boolean {
        // Check USB device permission
        return usbSerialManager!!.checkDevicePermission(key)
    }

    fun uploadHex(hexString: String, deviceKeyName: String?) {
        try {
            val tempHexFile = createTempHexFile(hexString)
            if (tempHexFile != null) {
                val file = FileInputStream(tempHexFile)
                val reader: Reader = InputStreamReader(file)
                val hexFileContents = LineReader(reader).readLines()
                val uploader = ArduinoSketchUploader(
                    context,
                    SerialPortStreamImpl::class.java,
                    null,
                    null,
                    null
                )
                uploader.UploadSketch(hexFileContents, getCustomArduino(), deviceKeyName)
            } else {
                Log.e("ArduinoUploader", "Failed to create temp hex file")
            }
        } catch (ex: ArduinoUploaderException) {
            Log.e("ArduinoUploader", "Arduino upload failed: ${ex.message}")
        } catch (ex: Exception) {
            Log.e("ArduinoUploader", "Arduino upload failed: ${ex.message}")
        }
    }

    private fun createTempHexFile(hexString: String): File? {
        return try {
            val tempDir = context.cacheDir
            val tempFile = File.createTempFile("temp", ".hex", tempDir)
            val fos = FileOutputStream(tempFile)
            fos.write(hexString.toByteArray())
            fos.close()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getCustomArduino(): Arduino {
        val board = Boards.ARDUINO_UNO
        val arduinoBoard = Arduino(board.name, board.chipType, board.uploadBaudrate, board.uploadProtocol)
        val protocol = Protocol.valueOf(arduinoBoard.protocol.name)
        val mcu = McuIdentifier.valueOf(arduinoBoard.mcu.name)
        val preOpenRst = arduinoBoard.preOpenResetBehavior ?: ""
        val postOpenRst = arduinoBoard.postOpenResetBehavior ?: ""
        val closeRst = arduinoBoard.closeResetBehavior ?: ""
        val customArduino = Arduino("Custom", mcu, arduinoBoard.baudRate, protocol)
        if (protocol == Protocol.Avr109) customArduino.sleepAfterOpen = 0 else customArduino.sleepAfterOpen = 250
        return customArduino
    }
}
