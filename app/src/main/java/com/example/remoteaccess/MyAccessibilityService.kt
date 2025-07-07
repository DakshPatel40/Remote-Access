package com.example.remoteaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.database.*

class MyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ACCESS_SERVICE", "Service CONNECTED ✅")
        Toast.makeText(this, "Accessibility Service Running", Toast.LENGTH_SHORT).show()


        val sharedPrefs = getSharedPreferences("remote_access_prefs", MODE_PRIVATE)
        val clientId = sharedPrefs.getString("client_id", null)

        if (clientId != null) {
            listenForInputCommands(clientId)
        } else {
            Log.e("ACCESS_SERVICE", "No client ID found!")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun listenForInputCommands(clientId: String) {
        val db = FirebaseDatabase.getInstance("your-firebase-app-url").reference //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL

        db.child("clients").child(clientId).child("input")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val type = snapshot.child("type").getValue(String::class.java) ?: return

                    if (type == "tap") {
                        val x = snapshot.child("x").getValue(Int::class.java) ?: return
                        val y = snapshot.child("y").getValue(Int::class.java) ?: return
                        Log.d("ACCESS_SERVICE", "Performing tap at ($x, $y)")
                        performTap(x, y)
                    }

                    if (type == "swipe") {
                        val startX = snapshot.child("startX").getValue(Int::class.java) ?: return
                        val startY = snapshot.child("startY").getValue(Int::class.java) ?: return
                        val endX = snapshot.child("endX").getValue(Int::class.java) ?: return
                        val endY = snapshot.child("endY").getValue(Int::class.java) ?: return
                        Log.d(
                            "ACCESS_SERVICE",
                            "Performing swipe from ($startX, $startY) to ($endX, $endY)"
                        )
                        performSwipe(startX, startY, endX, endY)
                    }

                    // ✅ Always clear input after processing
                    db.child("clients").child(clientId).child("input").removeValue()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("ACCESS_SERVICE", "Firebase error: ${error.message}")
                }
            })
    }

    private fun performTap(x: Int, y: Int) {
        Log.d("GESTURE_DEBUG", "Attempting tap at ($x, $y)")

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d("GESTURE_DEBUG", "Tap completed successfully")
        }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("GESTURE_DEBUG", "Tap was cancelled")
            }
        }, null)

    }

    private fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int) {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("GESTURE_DEBUG", "Swipe completed successfully")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("GESTURE_DEBUG", "Swipe was cancelled")
            }
        }, null)
    }

}
