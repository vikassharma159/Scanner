package com.android.scanner

import android.graphics.*
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.android.scanner.ocr.Tesseract
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor


class CameraActivity : AppCompatActivity(), Executor, ImageCapture.OnImageSavedCallback, ImageAnalysis.Analyzer {

    private lateinit var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private lateinit var imageCapture : ImageCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    fun bindPreview(cameraProvider : ProcessCameraProvider) {
        val preview : Preview = Preview.Builder()
            .build()

        val cameraSelector : CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(previewView.getSurfaceProvider())
        imageCapture = ImageCapture.Builder()
            .setTargetRotation(previewView.display.rotation)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        imageAnalysis.setAnalyzer(this, this)

        cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageAnalysis, imageCapture, preview)
    }

    fun onCapture(view: View) {
        val file = File(filesDir, "CaptureImage.jpg")
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(outputFileOptions, ContextCompat.getMainExecutor(this), this)
    }

    override fun execute(command: Runnable) {
        command.run()
    }

    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
        Toast.makeText(this@CameraActivity, "Image Saved Successfully", Toast.LENGTH_SHORT).show()
        val uri = outputFileResults.savedUri ?: return
        val bitmap = if (Build.VERSION.SDK_INT < 28) {
               MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
            } else {
                val source = ImageDecoder.createSource(this.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            }
        val text = Tesseract.getInstance(this@CameraActivity).getOCRResult(bitmap)
        if (text != null) {
            Log.d("OCRText", text)
        }
    }

    override fun onError(exception: ImageCaptureException) {
        Toast.makeText(this@CameraActivity, "Error in Saving", Toast.LENGTH_SHORT).show()
    }

    override fun analyze(image: ImageProxy) {
//        toBitmap(image.image?)
        var bitmap = image.image?.let {
            toBitmap(it)
        }
        bitmap?.let { runInternal(it) }
        val text = Tesseract.getInstance(this@CameraActivity).getOCRResult(bitmap)
        if (text != null) {
            Log.d("OCRText", text)
        }
        image.close()
    }

    private fun runInternal(rawBitmap: Bitmap): Bitmap? {
        val whiteHorizontalBitmapHeight = rawBitmap.height / 3

        //making horizontal white bitmap which is 30% of 1280
        val whiteHorizontalBitmap = Bitmap
            .createBitmap(rawBitmap.width, whiteHorizontalBitmapHeight, Bitmap.Config.ARGB_8888)
        whiteHorizontalBitmap.eraseColor(Color.WHITE)

        //making horizontal white bitmap which is 5% of 720
        val whiteVerticalBitmap = Bitmap
            .createBitmap(rawBitmap.width / 20, rawBitmap.height, Bitmap.Config.ARGB_8888)
        whiteVerticalBitmap.eraseColor(Color.WHITE)

        //creating canvas for rawBitmap
        val canvas = Canvas(rawBitmap)
        //draw 30% top as white
        canvas.drawBitmap(whiteHorizontalBitmap, Matrix(), null)
        //draw 30% bottom as white
        canvas.drawBitmap(
            whiteHorizontalBitmap, (canvas.width - whiteHorizontalBitmap.width).toFloat(), (
                    canvas.height - whiteHorizontalBitmap.height).toFloat(), null
        )
        //draw left top as white
        canvas.drawBitmap(whiteVerticalBitmap, Matrix(), null)
        //draw right top as white
        canvas.drawBitmap(
            whiteVerticalBitmap,
            (canvas.width - whiteVerticalBitmap.width).toFloat(),
            0f,
            null
        )
        //getting bitmap which has whole document
        val finalBitmapForUI = Bitmap.createBitmap(
            rawBitmap, whiteVerticalBitmap.width,
            whiteHorizontalBitmap.height,
            whiteHorizontalBitmap.width - whiteVerticalBitmap.width * 2,
            canvas.height - whiteHorizontalBitmap.height * 2
        )

        //drawing 3/4 top portion of the document image leaving just MRZ strip
        canvas.drawBitmap(
            whiteHorizontalBitmap, (canvas.width - whiteHorizontalBitmap.width).toFloat(),
            whiteHorizontalBitmap.height - whiteHorizontalBitmap.height * (1f / 4f), null
        )
        whiteHorizontalBitmap.recycle()
        whiteVerticalBitmap.recycle()
        return finalBitmapForUI
    }

    private fun toBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        //U and V are swapped
        yBuffer[nv21, 0, ySize]
        vBuffer[nv21, ySize, vSize]
        uBuffer[nv21, ySize + vSize, uSize]
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        val workingBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        return workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

}
