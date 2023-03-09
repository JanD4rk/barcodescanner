package com.scan.barcodescanner

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.RectF
import android.opengl.Visibility
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.camera.camera2.internal.compat.workaround.TargetAspectRatio
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.scan.barcodescanner.databinding.ActivityMainBinding
import java.util.concurrent.Executors

private const val CAMERA_PERMISSION_REQUEST_CODE = 1

@ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraReticleAnimator: CameraReticleAnimator
    private var isLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraReticleAnimator = CameraReticleAnimator(binding.overlay)
        if (hasCameraPermission()) bindCameraUseCases()
        else requestPermission()
    }

    // checking to see whether user has already granted permission
    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        // opening up dialog to ask for camera permission
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // user granted permissions - we can set up our scanner
            bindCameraUseCases()
        } else {
            // user did not grant permissions - we can't use the camera
            Toast.makeText(
                this,
                "Camera permission required",
                Toast.LENGTH_LONG
            ).show()
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // setting up the preview use case
            val previewUseCase = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraView.surfaceProvider)

                }


            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
            ).build()

            val scanner = BarcodeScanning.getClient(options)

            val analysisUseCase =
                ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()

            analysisUseCase.setAnalyzer(Executors.newSingleThreadExecutor()) {
                processImageProxy(scanner, it)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
              cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    previewUseCase,
                    analysisUseCase
                )

//                binding.overlay.setCameraInfo(previewUseCase.resolutionInfo?.resolution)

            } catch (illegalStateException: IllegalStateException) {
                Log.e(TAG, illegalStateException.message.orEmpty())
            } catch (illegalArgumentException: IllegalArgumentException) {
                Log.e(TAG, illegalArgumentException.message.orEmpty())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(
        barcodeScanner: BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        imageProxy.image?.let { image ->
            val inputImage =
                InputImage.fromMediaImage(
                    image,
                    imageProxy.imageInfo.rotationDegrees
                )
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodeList ->
                    if (isLocked) return@addOnSuccessListener //<--return here
                    val barcode = barcodeList.getOrNull(0)

                    addOverlay(binding.overlay, barcode)
                    // `rawValue` is the decoded value of the barcode
                    barcode?.rawValue?.let { value ->
                        binding.bottomText.text =
                            getString(R.string.barcode_value, value)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.image?.close()
                    imageProxy.close()
                }
        }
    }

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
    }


    fun addOverlay(graphicOverlay: GraphicOverlay, result:Barcode?) {
        graphicOverlay.clear()
        if (result == null) {
            cameraReticleAnimator.start()
            graphicOverlay.add(BarcodeReticleGraphic(graphicOverlay, cameraReticleAnimator))
        } else {
            isLocked=true
            cameraReticleAnimator.cancel()
            val loadingAnimator = createLoadingAnimator(graphicOverlay)
            loadingAnimator.start()
            graphicOverlay.add(BarcodeLoadingGraphic(graphicOverlay, loadingAnimator))
        }
        graphicOverlay.invalidate()
    }

    private fun createLoadingAnimator(graphicOverlay: GraphicOverlay): ValueAnimator {
        val endProgress = 1f
        return ValueAnimator.ofFloat(0f, endProgress).apply {
            repeatCount= ValueAnimator.INFINITE
            duration = 2000
            addUpdateListener {
                if ((animatedValue as Float).compareTo(endProgress) >= 0) {
                    graphicOverlay.clear()
                } else {
                    graphicOverlay.invalidate()
                }
            }
        }
    }


}