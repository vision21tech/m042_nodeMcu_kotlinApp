package com.example.a0122

import ArduinoUploader.ArduinoSketchUploader
import ArduinoUploader.ArduinoUploaderException
import ArduinoUploader.Config.Arduino
import ArduinoUploader.Config.McuIdentifier
import ArduinoUploader.Config.Protocol
import ArduinoUploader.IArduinoUploaderLogger
import CSharpStyle.IProgress
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.ui.AppBarConfiguration
import com.example.a0122.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.InetSocketAddress
import java.text.DateFormat
import java.util.Base64
import java.util.Date

import com.example.a0122.Esptool.EFJ

import java.util.concurrent.TimeUnit


private val REQUEST_STORAGE_PERMISSION = 100

// TODO : 파일 다운로드 코드
class JavaScriptInterface(private val context: Context) {
    @JavascriptInterface
    @Throws(IOException::class)
    fun getBase64FromBlobData(base64Data: String) {
        convertBase64StringToFileAndStoreIt(base64Data)
    }

    @Throws(IOException::class)
    private fun convertBase64StringToFileAndStoreIt(base64PDf: String) {
        val notificationId = 1
        val currentDateTime: String = DateFormat.getDateTimeInstance().format(Date())
        val newTime = currentDateTime.replaceFirst(", ".toRegex(), "_").replace(" ".toRegex(), "_")
            .replace(":".toRegex(), "-")
        Log.d("fileMimeType ====> ", fileMimeType!!)
        val mimeTypeMap = MimeTypeMap.getSingleton()
        // 파일명
        val fileName = "M042블록코딩아두이노"
        val extension = mimeTypeMap.getExtensionFromMimeType(fileMimeType)
        val dwldsPath = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).toString() + "/" + fileName + newTime
        )
        // 파일 저장 시간
        val regex = "^data:" + fileMimeType + ";base64,"
        val pdfAsBytes: ByteArray = android.util.Base64.decode(base64PDf.replaceFirst(regex.toRegex(), ""), android.util.Base64.DEFAULT)
        try {
            val os = FileOutputStream(dwldsPath)
            os.write(pdfAsBytes)
            os.flush()
            os.close()
        } catch (e: java.lang.Exception) {
            Toast.makeText(context, "다운로드가 실패했습니다.", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
        if (dwldsPath.exists()) {
            val intent = Intent()
            intent.setAction(Intent.ACTION_VIEW)
            val apkURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider",
                dwldsPath
            )
            intent.setDataAndType(
                apkURI,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pendingIntent =
                PendingIntent.getActivity(context, 1, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val CHANNEL_ID = "MYCHANNEL"
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationChannel =
                NotificationChannel(CHANNEL_ID, "name", NotificationManager.IMPORTANCE_LOW)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentText("$dwldsPath 이름으로 저장되었습니다.")
                .setContentTitle("파일 다운로드")
                .setContentIntent(pendingIntent)
                .setChannelId(CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build()
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(notificationChannel)
                notificationManager.notify(notificationId, notification)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dwldsPath.absolutePath),
                null
            ) { path, uri ->
                Log.i("ExternalStorage", "Scanned $path:")
                Log.i("ExternalStorage", "-> uri=$uri")
            }
        }
        Toast.makeText(context, "FILE DOWNLOADED!", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private var fileMimeType = ".sba"
        fun getBase64StringFromBlobUrl(blobUrl: String, mimeType: String): String {
            if (blobUrl.startsWith("blob")) {
                fileMimeType = mimeType
                return "javascript: var xhr = new XMLHttpRequest();" +
                        "xhr.open('GET', '" + blobUrl + "', true);" +
                        "xhr.setRequestHeader('Content-type','" + mimeType + ";charset=UTF-8');" +
                        "xhr.responseType = 'blob';" +
                        "xhr.onload = function(e) {" +
                        "    if (this.status == 200) {" +
                        "        var blobFile = this.response;" +
                        "        var reader = new FileReader();" +
                        "        reader.readAsDataURL(blobFile);" +
                        "        reader.onloadend = function() {" +
                        "            base64data = reader.result;" +
                        "            Android.getBase64FromBlobData(base64data);" +
                        "        }" +
                        "    }" +
                        "};" +
                        "xhr.send();"
            }
            return "javascript: console.log('It is not a Blob URL');"
        }
    }
}
class MainActivity : AppCompatActivity() {
    var hexdata = ""
    var bytedata: ByteArray = ByteArray(0)
    var mFileChooserCallbacks: ValueCallback<Array<Uri>>? = null
    // 바꿀일 거의 없음.
    // ip 주소
    inner class MyWebSocketServer(private val context: Context) : WebSocketServer(InetSocketAddress("127.0.0.1", 20111)) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            // 연결이 성공했을 때 동작하는 코드
            println("WebSocketServer: New connection opened")
            println("New connection: ${conn?.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            // 연결이 닫혔을 때 동작하는 코드
            println("WebSocketServer: Connection closed")
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            //println("WebSocketServer: Received message: $message")
            // println(message is String)
            // println(message)
            // println(JSONObject(message))

            val data = JSONObject(message)
            //println(data["method"])
            if (data["method"] == "status") {
                val res1 = JSONObject()
                val resArjs = JSONObject()
                res1.put("jsonrpc", "2.0")
                res1.put("method", "status")
                resArjs.put("result", "ok")
                res1.put("params", resArjs)
                //println(res1)
                val res2 = JSONObject()
                res2.put("id", data["id"])
                res2.put("jsonrpc", "2.0")
                res2.put("result", JSONObject.NULL)
                // val res2 = {"id":int(data['id']),"jsonrpc":"2.0","result":None}
                sendMessageToClients(res1.toString())
                sendMessageToClients(res2.toString())
            }
            if (data["method"] == "discover") {
                val params = JSONObject().apply {
                    put("peripheralId", "COM5")
                    put("name", "Arduino UNO")
                }

                val res = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "didDiscoverPeripheral")
                    put("params", params)
                }
                sendMessageToClients(res.toString())
            }
            if (data["method"] == "connect") {
                val res = JSONObject().apply {
                    put("id", data["id"])
                    put("jsonrpc", "2.0")
                }
                sendMessageToClients(res.toString())
                try {
                    usbSerialManager = UsbSerialManager(this@MainActivity)
                    //Log.d("usbSerialManager", usbSerialManager.toString())
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                    //Log.d("usbManager", usbManager.toString())
                    val usbDevices = usbManager.deviceList
                    //Log.d("usbDevices", usbDevices.toString())
                    usbStatus = if (usbDevices.isEmpty()) {
                        UsbConnectState.DISCONNECTED
                    } else {
                        UsbConnectState.CONNECT
                    }
                    //Log.d("usbStatus", usbStatus.toString())
                    val devicePlugged = usbStatus == UsbConnectState.CONNECT
                    //Log.d("devicePlugged", devicePlugged.toString())
                    if (!devicePlugged) {
                        Toast.makeText(
                            this@MainActivity,
                            "No Arduino device is plugged. Plug an Arduino device, via USB, and try again",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val (keySelect) = usbSerialManager!!.usbDeviceList.iterator().next()
                        //Log.d("keySelect", keySelect.toString())
                        val hasPem = checkDevicePermission(keySelect)
                        //Log.d("hasPem", hasPem.toString())
                        if (!hasPem) {
                            requestDevicePermission(keySelect)
                            Toast.makeText(
                                this@MainActivity,
                                "Let's allow the Arduino device USB, before installation",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            deviceKeyName = keySelect
                        }
                    }
                } catch (e: Exception) {
                    Log.e("시리얼", "$e")
                }
            }
            if (data["method"] == "read") {
                val res = JSONObject().apply {
                    put("id", data["id"])
                    put("jsonrpc", "2.0")
                    put("result", JSONObject.NULL)
                }
                sendMessageToClients(res.toString())
            }
            if (data["method"] == "updateBaudrate") {
                val res = JSONObject().apply {
                    put("id", data["id"])
                    put("jsonrpc", "2.0")
                }
                sendMessageToClients(res.toString())
            }
            /*if (data["method"] == "write") {
                if (usbStatus == UsbConnectState.DISCONNECTED) {
                    val res = JSONObject().apply {
                        put("id", data["id"])
                        put("jsonrpc", "2.0")
                    }
                    sendMessageToClients(res.toString())
                }
            }*/
            if (data["method"] == "upload") {
                //println("Uploading!!!!----:$data")
                val param = data.getJSONObject("params")
                val config = param.getJSONObject("config")
                val fqbn = config["fqbn"].toString()
                if(fqbn.contains("esp8266"))
                    param.put("board_type", "nodemcu")

                //println("param : $param")

                val dd = mUsbNotifyReceiver
                sendMessageToClients(dd.toString())
                //println("전송전--------------")
                fun sendPostRequest(url: String, request: String, callback: (Boolean) -> Unit) {
                    val client = OkHttpClient()
                    val body = RequestBody.create("application/json".toMediaTypeOrNull(), request)
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .build()
                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            // 요청 실패 시 처리할 작업
                            e.printStackTrace()
                            //println("서버 응답: 실패$e")
                            Toast.makeText(
                                this@MainActivity,
                                "아두이노 업로드에 실패했습니다. 다시 시도해주세요.",
                                Toast.LENGTH_LONG
                            ).show()
                            callback(false)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            // 요청 성공 시 처리할 작업
                            val responseBody = response.body
                            if (responseBody != null) {
                                val responseData = responseBody.string() // ResponseBody를 문자열로 변환
                                val jsonData = JSONObject(responseData)
                                val data = jsonData.optString("data")
                                /*println("------------------------------")
                                println("서버 응답: $responseData")
                                println("data 값: $data")
                                println("----------------------------")*/
                                val decodeByte: ByteArray = Base64.getDecoder().decode(data)
                                bytedata = decodeByte;
                                val decodeString = String(decodeByte)
                                hexdata = decodeString
                                /*try {
                                    usbSerialManager = UsbSerialManager(this@MainActivity)
                                    Log.d("usbSerialManager", usbSerialManager.toString())
                                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                                    Log.d("usbManager", usbManager.toString())
                                    val usbDevices = usbManager.deviceList
                                    Log.d("usbDevices", usbDevices.toString())
                                    usbStatus = if (usbDevices.isEmpty()) {
                                        UsbConnectState.DISCONNECTED
                                    } else {
                                        UsbConnectState.CONNECT
                                    }
                                    Log.d("usbStatus", usbStatus.toString())
                                    val devicePlugged = usbStatus == UsbConnectState.CONNECT
                                    Log.d("devicePlugged", devicePlugged.toString())
                                    if (!devicePlugged) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "아두이노 디바이스를 연결해주세요.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val (keySelect) = usbSerialManager!!.usbDeviceList.iterator().next()
                                        Log.d("keySelect", keySelect.toString())
                                        val hasPem = checkDevicePermission(keySelect)
                                        Log.d("hasPem", hasPem.toString())
                                        if (!hasPem) {
                                            requestDevicePermission(keySelect)
                                            Toast.makeText(
                                                this@MainActivity,
                                                "아두이노 디바이스 USB를 허용해주세요.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            deviceKeyName = keySelect
                                            uploadHex()
                                        }
                                    }


                                } catch (e: Exception) {
                                    Log.e("시리얼", "$e")
                                }*/
                                callback(true)
                            } else {
                                println("서버 응답: 빈 응답")
                                callback(false)
                            }
                        }
                    })
                }
                // TODO: 컴파일서버 주소
                // 아두이노 백본 주소
                // ip 주소
                val responseComp = sendPostRequest("http://vision21tech.iptime.org:5000/api/build", data["params"].toString()){
                        success ->
                    if (!success) {
                        println("서버 응답: 실패")
                    } else {
                        println("if 문 서버 응답: 성공")
                        var uploadSuccess = false
                        try {
                            usbSerialManager = UsbSerialManager(this@MainActivity)
                            Log.d("usbSerialManager", usbSerialManager.toString())
                            val usbManager = getSystemService(USB_SERVICE) as UsbManager
                            Log.d("usbManager", usbManager.toString())
                            val usbDevices = usbManager.deviceList
                            Log.d("usbDevices", usbDevices.toString())
                            usbStatus = if (usbDevices.isEmpty()) {
                                UsbConnectState.DISCONNECTED
                            } else {
                                UsbConnectState.CONNECT
                            }
                            Log.d("usbStatus", usbStatus.toString())
                            val devicePlugged = usbStatus == UsbConnectState.CONNECT
                            Log.d("devicePlugged", devicePlugged.toString())
                            if (!devicePlugged) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No Arduino device is plugged. Plug an Arduino device, via USB, and try again",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val (keySelect) = usbSerialManager!!.usbDeviceList.iterator().next()
                                Log.d("keySelect", keySelect.toString())
                                val hasPem = checkDevicePermission(keySelect)
                                Log.d("hasPem", hasPem.toString())
//                                Toast.makeText(
//                                    this@MainActivity,
//                                    "Let's allow the Arduino device USB, before installation",
//                                    Toast.LENGTH_LONG
//                                ).show()
                                if (!hasPem) {
                                    requestDevicePermission(keySelect)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Let's allow the Arduino device USB, before installation",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    deviceKeyName = keySelect
                                    uploadHex(param)
                                    uploadSuccess = true
                                }
                            }


                        } catch (e: Exception) {
                            Log.e("시리얼", "$e")
                        }
                        if(uploadSuccess){
                            val message3 = "\r\n\u001b[업로드가 완료 되었습니다.\r\n\r\n"

                            val params3 = JSONObject().apply {
                                put("message", message3)
                            }
                            val res3 = JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("method", "uploadStdout")
                                put("params", params3)
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(3000) // 1초 딜레이
                                sendMessageToClients(res3.toString())
                            }

                            val res4 = JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("method", "uploadSuccess")
                            }
                            sendMessageToClients(res4.toString())
                        }

                    }

                }
                val message2 =
                    "\u001b[아두이노 업로드 진행중에는 케이블을 뽑지 말아주세요.\n\n\u001b[업로드 중입니다. 잠시만 기다려주세요.\n\n"

                val params2 = JSONObject().apply {
                    put("message", message2)
                }

                val res2 = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "uploadStdout")
                    put("params", params2)
                }
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000) // 1초 딜레이
                    sendMessageToClients(res2.toString())
                }

                val res4 = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "uploadSuccess")
                }
                sendMessageToClients(res4.toString())
            }
        }

        override fun onError(conn: WebSocket?, ex: java.lang.Exception?) {
            // 에러가 발생했을 때 동작하는 코드
            println("WebSocketServer: Error occurred")
            ex?.printStackTrace()
            if (server.address != null) {
                server.stop()
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    delay(3000) // 1초 딜레이
                    server.start()
                }
            }
        }

        override fun onStart() {
            println("WebSocket server started successfully")
        }

        fun sendMessageToClients(message: String) {
            connections.forEach { client ->
                client.send(message)
            }
        }

    }
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private val server = MyWebSocketServer(this)
    private lateinit var jsInterface: JavaScriptInterface
    override fun onDestroy() {
        super.onDestroy()
        // 웹소켓 서버 종료
        server.stop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jsInterface = JavaScriptInterface(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkStorage()
        setContentView(R.layout.activity_main)
        WebView.setWebContentsDebuggingEnabled(true)
        CoroutineScope(Dispatchers.IO).launch {
            /*launch {
                delay(1000)
                webSocketClient.connectToWebSocket()
            }*/
            launch {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1000) // 1초 딜레이
                        server.start()

                }
            }
        }
        // webSocketClient.connectToWebSocket()
        println(server.address)
        webView = findViewById(R.id.webview)
        webView.apply {
            webViewClient = WebViewClient()
            // 하이퍼링크 클릭시 새창 띄우기 방지
            webChromeClient = object : WebChromeClient()// 크롬환경에 맞는 세팅을 해줌. 특히, 알람등을 받기위해서는 꼭 선언해주어야함 (alert같은 경우)
            {
                // 파일 선택 다이얼로그 표시
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 파일 선택 다이얼로그 열기
                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    }

                    // 파일 선택 결과를 처리하기 위해 콜백 유지
                    filePathCallback?.let {
                        mFilePathCallback = it
                    }

                    return true
                }
            }
            settings.defaultTextEncodingName = "utf-8" // 인코딩 방식
            addJavascriptInterface(jsInterface, "Android")
            settings.javaScriptEnabled = true // 자바스크립트 허용
            settings.javaScriptCanOpenWindowsAutomatically = false
            // 팝업창을 띄울 경우가 있는데, 해당 속성을 추가해야 window.open() 이 제대로 작동 , 자바스크립트 새창도 띄우기 허용여부
            settings.setSupportMultipleWindows(true) // 새창 띄우기 허용 여부 (멀티뷰)
            settings.loadsImagesAutomatically = true // 웹뷰가 앱에 등록되어 있는 이미지 리소스를 자동으로 로드하도록 설정하는 속성
            settings.domStorageEnabled = true // 로컬 스토리지 사용 여부를 설정하는 속성으로 팝업창등을 '하루동안 보지 않기' 기능 사용에 필요
            settings.allowContentAccess = true // 웹뷰 내에서 파일 액세스 활성화 여부
            settings.allowFileAccess = true // 웹뷰 내에서 파일 액세스 활성화 여부
            settings.allowFileAccessFromFileURLs = true // 웹뷰 내에서 파일 액세스 활성화 여부
            settings.allowUniversalAccessFromFileURLs = true // 웹뷰 내에서 파일 액세스 활성화 여부
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                // 다운로드 클릭시 다운로드를 받을 수 있도록 하는 속성
                val js = JavaScriptInterface.getBase64StringFromBlobUrl(url, mimetype)
                evaluateJavascript(js, null)
            }
        }

        //ip 주소
        // 웹뷰 로드 코드
        webView.loadUrl("http://vision21tech.iptime.org:8601")
        webView.canGoBack()
    }
    private fun checkStorage() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }
    // 웹뷰에서 뒤로가기 가능하게 해주는 함수
    override fun onBackPressed() {
        // 뒤로가기 버튼 누를 시 웹페이지 내에서 뒤 페이지로 가게 해줌 그게 아닐시 앱 뒤로가기 실행
        super.onBackPressed()
        val webView = findViewById<WebView>(R.id.webview)
        if(webView.canGoBack())
        {
            webView.goBack()
        }
        else
        {
            finish()
        }
    }
    var FILE_CHOOSER_REQUEST_CODE = 100
    var mFilePathCallback: ValueCallback<Array<Uri>>? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            // 파일 선택 결과를 웹뷰에 전달
            mFilePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            mFilePathCallback = null
        }
    }

    // 업로드 함수
    fun uploadHex(param: JSONObject) {
        try {
            if (param["board_type"].equals("nodemcu")) {
                val manager = getSystemService(USB_SERVICE) as UsbManager
                val esptool = EFJ(manager)
                esptool.flashStart(bytedata)
                Log.d("flow", "flash 완료")
            }
            else{
                val board = Boards.ARDUINO_UNO

                val arduinoBoard =
                    Arduino(board.name, board.chipType, board.uploadBaudrate, board.uploadProtocol)

                val protocol = Protocol.valueOf(arduinoBoard.protocol.name)

                val mcu = McuIdentifier.valueOf(arduinoBoard.mcu.name)
                val preOpenRst = arduinoBoard.preOpenResetBehavior
                var preOpenStr = preOpenRst
                if (preOpenRst == null) preOpenStr = "" else if (preOpenStr.equals(
                        "none",
                        ignoreCase = true
                    )
                ) preOpenStr = ""
                val postOpenRst = arduinoBoard.postOpenResetBehavior
                var postOpenStr = postOpenRst
                if (postOpenRst == null) postOpenStr = "" else if (postOpenStr.equals(
                        "none",
                        ignoreCase = true
                    )
                ) postOpenStr = ""
                val closeRst = arduinoBoard.closeResetBehavior
                var closeStr = closeRst
                if (closeRst == null) closeStr = "" else if (closeStr.equals(
                        "none",
                        ignoreCase = true
                    )
                ) closeStr = ""
                val customArduino = Arduino("Custom", mcu, arduinoBoard.baudRate, protocol)
                if (!TextUtils.isEmpty(preOpenStr)) customArduino.preOpenResetBehavior = preOpenStr
                if (!TextUtils.isEmpty(postOpenStr)) customArduino.postOpenResetBehavior =
                    postOpenStr
                if (!TextUtils.isEmpty(closeStr)) customArduino.closeResetBehavior = closeStr
                if (protocol == Protocol.Avr109) customArduino.sleepAfterOpen =
                    0 else customArduino.sleepAfterOpen =
                    250
                val logger: IArduinoUploaderLogger = object : IArduinoUploaderLogger {
                    override fun Error(message: String, exception: java.lang.Exception) {
                        println("Error:$message")
                        println("실패------------------------------1")
                    }

                    override fun Warn(message: String) {
                        println("Warn:$message")
                        println("워닝------------------------------1")
                    }

                    override fun Info(message: String) {
                        println("Info:$message")
                        println("인포------------------------------1")
                    }

                    override fun Debug(message: String) {
                        println("Debug:$message")
                        println("디버그------------------------------1")
                    }

                    override fun Trace(message: String) {
                        println("Trace:$message")
                        println("트레이스------------------------------1")
                    }
                }
                val progress: IProgress<*> = IProgress<Double> { value ->
                    val result = String.format("Upload progress: %1$,3.2f%%", value * 100)
                    Log.d("progress", result)

                }
                try {
                    val hexString = hexdata
                    Log.d("hexString", hexString)
                    var tempHexFile = createTempHexFile(hexString)
                    Log.d("tempHexFile", tempHexFile.toString())
                    // var tempHexFile = "/storage/emulated/0/Download/sketch_jan19a_ino.hex"
                    if (tempHexFile != null) {
                        val file = FileInputStream(tempHexFile)
                        Log.d("file", file.toString())
                        val reader: Reader = InputStreamReader(file)
                        val hexFileContents = LineReader(reader).readLines()
                        Log.d("hexFileContents", hexFileContents.toString())
                        val uploader = ArduinoSketchUploader(
                            this,
                            SerialPortStreamImpl::class.java,
                            null,
                            logger,
                            progress as IProgress<Double>?
                        )
                        uploader.UploadSketch(hexFileContents, customArduino, deviceKeyName)
                        Toast.makeText(
                            this,
                            "Arduino Installation successful !! IT'S PARTY TIME ;-)\n아두이노 업로드가 완료되었습니다!\n-----------------------------\n****************************",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "There's no hex data loaded. Use this app with ArduinoGPT or MaslowGPT, and it will work ;-)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (ex: ArduinoUploaderException) {
                    Toast.makeText(
                        this,
                        "The Arduino Installation failed... Try again soldier, never give up ;-)",
                        Toast.LENGTH_LONG
                    ).show()
                    ex.printStackTrace()
                } catch (ex: java.lang.Exception) {
                    Toast.makeText(
                        this,
                        "The Arduino Installation failed... Try again soldier, never give up ;-)",
                        Toast.LENGTH_LONG
                    ).show()
                    ex.printStackTrace()
                }
            }
        } catch (e: Exception) {
            Log.e("시리얼", "$e")
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 사용자가 권한을 허용한 경우
                // TODO: 권한이 허용되었을 때 수행할 동작 추가
            } else {
                // 사용자가 권한을 거부한 경우
                // TODO: 권한이 거부되었을 때 수행할 동작 추가
            }
        }
    }

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


    private val mUsbNotifyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbSerialManager.ACTION_USB_PERMISSION_GRANTED -> Toast.makeText(
                    context,
                    "USB permission granted",
                    Toast.LENGTH_SHORT
                ).show()

                UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED -> Toast.makeText(
                    context,
                    "USB Permission denied",
                    Toast.LENGTH_SHORT
                ).show()

                UsbSerialManager.ACTION_NO_USB -> Toast.makeText(
                    context,
                    "No USB connected",
                    Toast.LENGTH_SHORT
                ).show()

                UsbSerialManager.ACTION_USB_DISCONNECTED -> {
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show()
                    usbStatus = UsbConnectState.DISCONNECTED
                    usbConnectChange(UsbConnectState.DISCONNECTED)
                }

                UsbSerialManager.ACTION_USB_CONNECT -> {
                    Toast.makeText(context, "USB connected", Toast.LENGTH_SHORT).show()
                    usbStatus = UsbConnectState.CONNECT
                    usbConnectChange(UsbConnectState.CONNECT)
                }

                UsbSerialManager.ACTION_USB_NOT_SUPPORTED -> Toast.makeText(
                    context,
                    "USB device not supported",
                    Toast.LENGTH_SHORT
                ).show()

                UsbSerialManager.ACTION_USB_READY -> Toast.makeText(
                    context,
                    "Usb device ready",
                    Toast.LENGTH_SHORT
                ).show()

                UsbSerialManager.ACTION_USB_DEVICE_NOT_WORKING -> Toast.makeText(
                    context,
                    "USB device not working",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    fun usbPermissionGranted(usbKey: String) {
        Toast.makeText(this, "UsbPermissionGranted:$usbKey", Toast.LENGTH_SHORT).show()
        //portSelect.setText(usbKey);
        deviceKeyName = usbKey
        //if (fab != null) fab.show();
    }

    private var deviceKeyName: String? = null
    private val mUsbHardwareReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbSerialManager.ACTION_USB_PERMISSION_REQUEST) {
                val granted = intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                if (granted) // User accepted our USB connection. Try to open the device as a serial port
                {
                    val grantedDevice =
                        intent.extras!!.getParcelable<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    usbPermissionGranted(grantedDevice!!.deviceName)
                    val it = Intent(UsbSerialManager.ACTION_USB_PERMISSION_GRANTED)
                    context.sendBroadcast(it)
                } else  // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    val it = Intent(UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED)
                    context.sendBroadcast(it)
                }
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val it = Intent(UsbSerialManager.ACTION_USB_CONNECT)
                context.sendBroadcast(it)
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // Usb device was disconnected. send an intent to the Main Activity
                val it = Intent(UsbSerialManager.ACTION_USB_DISCONNECTED)
                context.sendBroadcast(it)
            }
        }
    }

    private fun setUsbFilter() {
        val filter = IntentFilter()
        filter.addAction(UsbSerialManager.ACTION_USB_PERMISSION_REQUEST)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(mUsbHardwareReceiver, filter)
    }

    private fun createTempHexFile(hexString: String): File? {
        return try {
            Log.d("createTempHexFile", "createTempHexFile!!!")
            // Create a temporary file to store the HEX string
            val tempDir = cacheDir
            Log.d("tempDir", tempDir.toString())

            val tempFile = File.createTempFile("temp", ".hex", tempDir)
            Log.d("tempFile", tempFile.toString())
            // Write the HEX string to the temporary file
            val fos = FileOutputStream(tempFile)
            Log.d("fos", fos.toString())
            fos.write(hexString.toByteArray())
            fos.close()
            return tempFile
        } catch (e: java.lang.Exception) {
            Log.d("createTempHexFile FAIL", "createTempHexFile FAIL!!!!")
            e.printStackTrace()
            null
        }
    }

    fun requestDevicePermission(key: String?) {
        usbSerialManager!!.getDevicePermission(key)
    }

    fun checkDevicePermission(key: String?): Boolean {
        return usbSerialManager!!.checkDevicePermission(key)
    }


}