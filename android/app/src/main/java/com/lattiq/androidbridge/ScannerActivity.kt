package com.lattiq.androidbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen camera that scans the Mac's pairing QR, saves config, and connects. */
class ScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private val scanner = BarcodeScanning.getClient()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else fail("Camera permission denied") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes -> codes.firstOrNull()?.let { onQr(it) } }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onQr(code: Barcode) {
        val raw = code.rawValue ?: return
        if (!handled.compareAndSet(false, true)) return  // first valid QR only
        try {
            val j = JSONObject(raw)
            val ip = j.optString("ip", "")
            val port = j.optInt("port", 8765)
            val key = j.getString("key")        // required — secretbox key
            val room = j.getString("room")      // required — relay room
            val relay = j.optString("relay", "")
            val name = j.optString("name", "Mac")
            val mac = Mac(id = room, name = name, ip = ip, port = port, key = key, room = room, relay = relay)
            Prefs.addMac(this, mac)
            Links.ensure(mac)
            runOnUiThread {
                Toast.makeText(this, "Paired with $name ✓", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK); finish()
            }
        } catch (e: Exception) {
            Log.w("Scanner", "bad QR payload: $raw", e)
            handled.set(false)  // let user try another code
            runOnUiThread { Toast.makeText(this, "Not a Mac Bridge QR", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
