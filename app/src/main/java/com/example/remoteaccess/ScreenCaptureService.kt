package com.example.remoteaccess

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var handler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)!!
            val resultData = intent.getParcelableExtra<Intent>("data")!!
            val remoteId = intent.getStringExtra("remoteId")!!

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            handler = Handler(mainLooper) // ✅ Initialize handler FIRST!

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("ScreenCaptureService", "MediaProjection has stopped")
                    stopSelf()
                }
            }, handler) // ✅ Now pass initialized handler here

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels / 2
            val height = metrics.heightPixels / 2
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RemoteDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            startImageCaptureLoop(remoteId) // ✅ Start screen capture loop

        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Startup error: ${e.message}")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startImageCaptureLoop(remoteId: String) {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    Log.d("ScreenCaptureService", "Trying to capture image...")

                    val image = imageReader?.acquireLatestImage()
                    if (image == null) {
                        Log.d("ScreenCaptureService", "No image yet. Waiting...")
                        handler.postDelayed(this, 500)
                        return
                    }

                    image.use {
                        Log.d("ScreenCaptureService", "Image captured.")

                        val width = it.width
                        val height = it.height
                        if (width == 0 || height == 0) {
                            Log.d("ScreenCaptureService", "Width/Height is 0. Skipping frame.")
                            return@use
                        }

                        val plane = it.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)


                        val compressedStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 10, compressedStream)
                        val encodedImage = Base64.encodeToString(compressedStream.toByteArray(), Base64.NO_WRAP)

                        val db = FirebaseDatabase.getInstance("your-firebase-app-url").reference //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL
                        db.child("clients").child(remoteId).child("screen").setValue(encodedImage)

                        bitmap.recycle()
                        System.gc()
                        Log.d("ScreenCaptureService", "Frame upload done.")
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Capture error inside loop: ${e.message}")
                } finally {
                    handler.postDelayed(this, 1500)
                }
            }
        })
    }


    @SuppressLint("NewApi")
    private fun createNotification(): Notification {
        val channelId = "screen_capture_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}

