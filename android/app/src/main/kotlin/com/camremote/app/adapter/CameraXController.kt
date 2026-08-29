package com.camremote.app.adapter

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.CaptureResult
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Captures a still with the rear sensor using CameraX, with no preview and no user interaction.
 *
 * This is the one adapter that genuinely cannot be unit-tested: there is no rear camera on a desktop
 * JVM, and faking CameraX would only test the fake. It is covered by the instrumented test and by
 * running the app on a handset, and it is written to hold no decisions of its own — everything about
 * *what* to capture and *where* was settled in `:core` before this is called.
 *
 * `ImageCapture` is bound without a `Preview` use case on purpose. The obvious alternative, firing
 * `ACTION_IMAGE_CAPTURE` at the camera app, would need a human to press the shutter, which defeats
 * the point of a remotely controlled agent.
 */
class CameraXController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraController {

    /**
     * Asks Camera2 whether any sensor faces backwards.
     *
     * Camera2 rather than CameraX because this has to answer synchronously and cheaply, without
     * waiting for a camera provider that may take a moment to appear.
     */
    override fun hasRearCamera(): Boolean {
        val manager = context.getSystemService<CameraManager>() ?: return false
        return runCatching {
            manager.cameraIdList.any { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrDefault(false)
    }

    /** Binds an ImageCapture to the rear camera, takes one photograph, and unbinds again. */
    override suspend fun captureRearStill(request: CaptureRequest): CaptureResult =
        // Binding use cases is a main-thread operation in CameraX.
        withContext(Dispatchers.Main) {
            val provider = ProcessCameraProvider.getInstance(context).await()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(request.jpegQuality)
                .build()

            val file = File(request.destinationPath)
            try {
                // Unbind first: the agent may have been interrupted mid-capture previously, and a
                // stale binding would fail the new one with a confusing "camera in use".
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture,
                )
                imageCapture.awaitCapture(file)
            } finally {
                provider.unbindAll()
            }

            measure(file)
        }

    /** Bridges CameraX's callback into a coroutine, surfacing a capture error as an exception. */
    private suspend fun ImageCapture.awaitCapture(file: File) =
        suspendCancellableCoroutine { continuation ->
            takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    /** The file is on disk; let the caller continue. */
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(Unit)
                    }

                    /** Re-thrown in the caller's coroutine, where the command can classify it. */
                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    /**
     * Awaits a ListenableFuture without pulling in coroutines-guava for one call site.
     *
     * The listener runs on the main executor, which is where the caller already is.
     */
    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    try {
                        continuation.resume(get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { cancel(false) }
        }

    /**
     * Reads the saved JPEG's dimensions without decoding the pixels.
     *
     * `OutputFileResults` does not report them, and a client that has only been handed a path
     * benefits from knowing what it is about to download.
     */
    private fun measure(file: File): CaptureResult {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return CaptureResult(widthPx = options.outWidth, heightPx = options.outHeight)
    }
}
