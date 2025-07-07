package com.example.remoteaccess

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.remoteaccess.ui.theme.RemoteAccessTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.absoluteValue

private const val REQUEST_MEDIA_PROJECTION = 1001

class MainActivity : ComponentActivity() {

    private var projectionManager: MediaProjectionManager? = null
    private var currentRemoteId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val firebase =
            FirebaseDatabase.getInstance("your-firebase-app-url") //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL
        firebase.setPersistenceEnabled(false)
        FirebaseApp.initializeApp(this)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            RemoteAccessTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteAccessApp()
                }
            }
        }
    }

    private fun startScreenCapture(remoteId: String) {
        currentRemoteId = remoteId
        val intent = projectionManager?.createScreenCaptureIntent()
        intent?.let { startActivityForResult(it, REQUEST_MEDIA_PROJECTION) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null && currentRemoteId != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("remoteId", currentRemoteId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Screen sharing started!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen sharing cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun RemoteAccessApp() {
        val context = LocalContext.current
        var userMode by remember { mutableStateOf<String?>(null) }
        var clientId by remember { mutableStateOf("") }
        var serverPassword by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        var isPasswordCorrect by remember { mutableStateOf(false) }
        var enteredRemoteId by remember { mutableStateOf("") }
        var screenImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

        var clientScreenWidth by remember { mutableStateOf(1080) }
        var clientScreenHeight by remember { mutableStateOf(1920) }

        val db = FirebaseDatabase.getInstance("your-firebase-app-url").reference //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Remote Access", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    userMode = "Client"
                    clientId = (10000..99999).random().toString()

                    val metrics = context.resources.displayMetrics
                    val clientWidth = metrics.widthPixels
                    val clientHeight = metrics.heightPixels

                    db.child("clients").child(clientId).setValue(
                        mapOf(
                            "status" to "waiting",
                            "command" to "idle",
                            "screen_width" to clientWidth,
                            "screen_height" to clientHeight
                        )
                    ).addOnSuccessListener {
                        Toast.makeText(
                            context,
                            "Client ID registered: $clientId",
                            Toast.LENGTH_SHORT
                        ).show()

                        val sharedPrefs = context.getSharedPreferences(
                            "remote_access_prefs",
                            MODE_PRIVATE
                        )
                        sharedPrefs.edit().putString("client_id", clientId).apply()

                        val intent =
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)

                        Toast.makeText(
                            context,
                            "Please enable Accessibility Service",
                            Toast.LENGTH_LONG
                        ).show()

                        Log.d("FIREBASE", "Client wrote ID: $clientId")
                        listenForCommands(clientId)
                    }

                    db.child("clients").child(clientId).onDisconnect().removeValue()
                }) {
                    Text("Client")
                }

                Button(onClick = {
                    userMode = "Server"
                }) {
                    Text("Server")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (userMode != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (userMode) {
                            "Client" -> {
                                Text("Your Remote ID", style = MaterialTheme.typography.titleMedium)
                                Text(clientId, style = MaterialTheme.typography.headlineLarge)
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            "Server" -> {
                                if (!isPasswordCorrect) {
                                    OutlinedTextField(
                                        value = serverPassword,
                                        onValueChange = { serverPassword = it },
                                        label = { Text("Enter Password") },
                                        visualTransformation = if (showPassword)
                                            VisualTransformation.None
                                        else
                                            PasswordVisualTransformation(),
                                        trailingIcon = {
                                            TextButton(onClick = {
                                                showPassword = !showPassword
                                            }) {
                                                Text(if (showPassword) "Hide" else "Show")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = {
                                            if (serverPassword == "Farma2025") {
                                                isPasswordCorrect = true
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Incorrect password",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                serverPassword = ""
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Submit")
                                    }
                                } else {
                                    var pairingStatus by remember { mutableStateOf("Waiting...") }
                                    var connectionResult by remember { mutableStateOf("") }
                                    var isConnecting by remember { mutableStateOf(false) }

                                    OutlinedTextField(
                                        value = enteredRemoteId,
                                        onValueChange = {
                                            if (it.length <= 5 && it.all(Char::isDigit)) {
                                                enteredRemoteId = it
                                                connectionResult = ""
                                                pairingStatus = "Not pairing"
                                            }
                                        },
                                        label = { Text("Enter Remote ID") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        Button(
                                            onClick = {
                                                isConnecting = true
                                                pairingStatus = "Pairing..."
                                                connectionResult = ""

                                                db.child("clients").child(enteredRemoteId).get()
                                                    .addOnSuccessListener { snapshot ->
                                                        if (snapshot.exists()) {
                                                            db.child("clients").child(enteredRemoteId)
                                                                .child("status")
                                                                .setValue("connected")

                                                            db.child("clients").child(enteredRemoteId)
                                                                .child("command")
                                                                .setValue("start_screen_sharing")

                                                            pairingStatus = "Paired ✅"
                                                            connectionResult =
                                                                "Paired with ID: $enteredRemoteId"

                                                            clientScreenWidth =
                                                                snapshot.child("screen_width")
                                                                    .getValue(Int::class.java)
                                                                    ?: 1080
                                                            clientScreenHeight =
                                                                snapshot.child("screen_height")
                                                                    .getValue(Int::class.java)
                                                                    ?: 1920

                                                            listenForScreenUpdates(
                                                                enteredRemoteId
                                                            ) { bitmap ->
                                                                screenImageBitmap = bitmap
                                                            }
                                                        } else {
                                                            pairingStatus = "Not Paired ❌"
                                                            connectionResult =
                                                                "Remote ID mismatched"
                                                        }
                                                        isConnecting = false
                                                    }
                                            },
                                            enabled = !isConnecting && enteredRemoteId.length == 5
                                        ) {
                                            Text("Connect")
                                        }

                                        Button(onClick = {
                                            db.child("clients").child(enteredRemoteId).removeValue()
                                            pairingStatus = "Stopped"
                                            connectionResult = "Connection ended or removed"
                                            isConnecting = false
                                        }) {
                                            Text("Stop")
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "Status: $pairingStatus",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        "Message: $connectionResult",
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))

                                    screenImageBitmap?.let { bitmap ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(9f / 16f)
                                                .padding(top = 16.dp)
                                                .pointerInput(Unit) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown()
                                                        val startX = down.position.x
                                                        val startY = down.position.y

                                                        var endX = startX
                                                        var endY = startY

                                                        var change = down
                                                        do {
                                                            change = awaitPointerEvent().changes.firstOrNull()
                                                                ?: break
                                                            endX = change.position.x
                                                            endY = change.position.y
                                                        } while (change.pressed)

                                                        val displayedWidth = this.size.width
                                                        val displayedHeight = this.size.height

                                                        val scaledStartX =
                                                            (startX / displayedWidth) * clientScreenWidth
                                                        val scaledStartY =
                                                            (startY / displayedHeight) * clientScreenHeight
                                                        val scaledEndX =
                                                            (endX / displayedWidth) * clientScreenWidth
                                                        val scaledEndY =
                                                            (endY / displayedHeight) * clientScreenHeight

                                                        val input =
                                                            if ((startX - endX).absoluteValue < 10 && (startY - endY).absoluteValue < 10)
                                                            {
                                                                mapOf(
                                                                    "type" to "tap",
                                                                    "x" to scaledStartX.toInt(),
                                                                    "y" to scaledStartY.toInt()
                                                                )
                                                            } else {
                                                                mapOf(
                                                                    "type" to "swipe",
                                                                    "startX" to scaledStartX.toInt(),
                                                                    "startY" to scaledStartY.toInt(),
                                                                    "endX" to scaledEndX.toInt(),
                                                                    "endY" to scaledEndY.toInt()
                                                                )
                                                            }

                                                        db.child("clients").child(enteredRemoteId)
                                                            .child("input").setValue(input)

                                                        Toast.makeText(
                                                            context,
                                                            "Sent ${input["type"]} from (${scaledStartX.toInt()}, ${scaledStartY.toInt()}) to (${scaledEndX.toInt()}, ${scaledEndY.toInt()})",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                        ) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Remote Screen",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun listenForCommands(remoteId: String) {
        val db = FirebaseDatabase.getInstance("your-firebase-app-url").reference //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL

        db.child("clients").child(remoteId).child("command")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val command = snapshot.getValue(String::class.java)
                    if (command == "start_screen_sharing") {
                        Log.d("REMOTE_ACCESS", "Starting screen sharing as per server command")
                        startScreenCapture(remoteId)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    fun listenForScreenUpdates(remoteId: String, onBitmapReceived: (Bitmap) -> Unit) {
        val db = FirebaseDatabase.getInstance("your-firebase-app-url").reference //TODO: Replace "your-firebase-app-url" with actual Firebase Realtime DB URL

        db.child("clients").child(remoteId).child("screen")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val base64Screen = snapshot.getValue(String::class.java)
                    if (!base64Screen.isNullOrEmpty()) {
                        try {
                            val decodedBytes =
                                Base64.decode(base64Screen, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(
                                decodedBytes,
                                0,
                                decodedBytes.size
                            )
                            onBitmapReceived(bitmap)
                        } catch (e: Exception) {
                            Log.e("SERVER_SCREEN", "Error decoding screen image")
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}
