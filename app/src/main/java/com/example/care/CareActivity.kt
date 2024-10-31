package com.example.care

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import android.net.Uri
import java.text.SimpleDateFormat
import androidx.compose.ui.text.style.TextDecoration
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import android.content.Intent
import android.widget.MediaController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.KeyboardType
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import java.util.*

class CareActivity : ComponentActivity() {
    private var powerButtonPressCount = 0
    private var lastPressTime = 0L
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Acquire a partial wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "care:WakeLock")
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)

        // Register the video picker launcher
        videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { videoUri ->
                    uploadVideoToFirebaseStorage(videoUri) { videoUrl ->
                        if (videoUrl != null) {
                            trackLocationAndReport(videoUrl)
                        } else {
                            Toast.makeText(this, "Video upload failed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "care_screen") {
                    composable("care_screen") {
                        CareScreen(
                            firstName = intent.getStringExtra("firstName"),
                            lastName = intent.getStringExtra("lastName"),
                            onReportIncident = { handlePowerButtonPress() },
                            navController = navController
                        )
                    }
                    composable("my_reports") { MyReportsScreen(navController) }
                    composable("feedback") { FeedbackScreen(navController) }
                    composable("my_secret_journal") {
                        MySecretJournalScreen() }
                    composable("edit_account") { EditAccountScreen(navController = navController) }
                }

            }
        }
    }

    private fun handlePowerButtonPress() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPressTime < 2000) {
            powerButtonPressCount++
            if (powerButtonPressCount == 6) {
                openVideoPicker()
            }
        } else {
            powerButtonPressCount = 1
        }
        lastPressTime = currentTime
    }

    // This is the updated openVideoPicker function
    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"  // Set the type to video
            addCategory(Intent.CATEGORY_OPENABLE)  // Ensure the file can be opened
        }
        videoPickerLauncher.launch(intent)
    }

    private fun trackLocationAndReport(videoUrl: String) {
        // Get the current user ID (assuming you're using Firebase Auth)
        val currentUser = FirebaseAuth.getInstance().currentUser
        val userId = currentUser?.uid // This gets the unique ID of the logged-in user

        if (userId == null) {
            Toast.makeText(this, "User is not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latitude = it.latitude
                    val longitude = it.longitude
                    val policeStationName = "Thohoyandou Police Station"
                    val policeEmail = "thohoyandou.sc@saps.gov.za"

                    // Now pass userId to storeIncident
                    storeIncident(latitude, longitude, policeStationName, policeEmail, videoUrl, userId)
                } ?: run {
                    Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Failed to get location", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
        }
    }


    private fun storeIncident(
        latitude: Double,
        longitude: Double,
        policeStationName: String,
        policeEmail: String,
        videoUrl: String,
        userId: String
    ) {
        val db = FirebaseFirestore.getInstance()

        // First, fetch the user data to get nextOfKinPhone1 and nextOfKinPhone2
        db.collection("users").document(userId).get()
            .addOnSuccessListener { userDocument ->
                val userData = userDocument.data
                val nextOfKinPhone1 = userData?.get("nextOfKinPhone1") as? String ?: ""
                val nextOfKinPhone2 = userData?.get("nextOfKinPhone2") as? String ?: ""

                Log.d("IncidentReport", "nextOfKinPhone1: $nextOfKinPhone1, nextOfKinPhone2: $nextOfKinPhone2")

                // Prepare incident data
                val incidentData = hashMapOf(
                    "userId" to userId, // Add userId to the incident data
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "policeStation" to policeStationName,
                    "policeEmail" to policeEmail,
                    "videoUrl" to videoUrl,
                    "nextOfKinPhone1" to nextOfKinPhone1,
                    "nextOfKinPhone2" to nextOfKinPhone2,
                    "timestamp" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )


                // Store incident data in Firestore
                db.collection("incidents").add(incidentData)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Incident reported successfully.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e("IncidentReport", "Failed to store incident data: ${e.message}")
                        Toast.makeText(this, "Failed to store incident data.", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { exception ->
                Log.e("IncidentReport", "Failed to retrieve user data: ${exception.message}")
                Toast.makeText(this, "Failed to retrieve user data: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }



    private fun uploadVideoToFirebaseStorage(videoUri: Uri, onComplete: (String?) -> Unit) {
        val storageRef = FirebaseStorage.getInstance().reference.child("videos/${UUID.randomUUID()}.mp4")
        val uploadTask = storageRef.putFile(videoUri)

        uploadTask.addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                onComplete(downloadUri.toString())
            }.addOnFailureListener { exception ->
                Log.e("VideoUpload", "Failed to get download URL: ${exception.message}")
                onComplete(null)
            }
        }.addOnFailureListener { exception ->
            Log.e("VideoUpload", "Video upload failed: ${exception.message}")
            onComplete(null)
        }
    }

}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareScreen(firstName: String?, lastName: String?, onReportIncident: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    var locationPermissionGranted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // State to manage the visibility of the support dialog
    var showSupportDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Mutable state to hold the list of incidents
    var incidentList by remember { mutableStateOf(listOf<Map<String, Any>>()) }

    // Firestore instance
    val db = FirebaseFirestore.getInstance()

    // Fetch incidents from Firestore
    LaunchedEffect(Unit) {
        db.collection("incidents")
            .get()
            .addOnSuccessListener { querySnapshot ->
                incidentList = querySnapshot.documents.mapNotNull { it.data }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to retrieve incidents.", Toast.LENGTH_SHORT).show()
            }
    }

    val requestLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        locationPermissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Location permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // Check if permission is already granted
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionGranted = true
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Care") },
                actions = {
                    TextButton(onClick = { navController.navigate("my_reports") }) {
                        Text("My Reports", color = Color.White)
                    }
                    TextButton(onClick = { navController.navigate("feedback") }) {
                        Text("Feedback", color = Color.White)
                    }
                    SettingsDropdownMenu(navController)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF8166A7))
            )
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_user),
                        contentDescription = "User Icon",
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "$firstName $lastName", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Report the incident to the nearest police station by clicking the button six times to report.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (locationPermissionGranted) {
                            scope.launch {
                                trackLocation(context)
                                onReportIncident()
                            }
                        } else {
                            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8166A7))
                ) {
                    Row {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_upload),
                            contentDescription = "Upload Icon",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Report Incident", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Reported Incident History Section
                Text(
                    text = "Reported Incident History",
                    style = MaterialTheme.typography.titleLarge.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start).padding(9.dp)
                )

                // Display the list of incidents
                LazyColumn {
                    items(incidentList) { incident ->
                        IncidentCard(incident)
                    }
                }
            }
        },
        bottomBar = {
            BottomAppBar(
                content = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showSupportDialog = true }) {
                            Text("Support", color = Color.White)
                        }
                        // Show the about dialog when needed
                        TextButton(onClick = { showAboutDialog = true }) {
                            Text("About", color = Color.White)
                        }
                        TextButton(onClick = { showHelpDialog = true }) {
                            Text("Help", color = Color.White)
                        }
                    }
                },
                containerColor = Color(0xFF8166A7)
            )
        }
    )

    // Show the support dialog when needed
    if (showSupportDialog) {
        SupportDialog(onDismiss = { showSupportDialog = false })
    }

    // Show the about dialog when needed
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // Show the help dialog when needed
    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current  // Get the context to be used for starting activities

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Support Information") },
        text = {
            Column {
                Text("For support, please check the following resources:")

                Spacer(modifier = Modifier.height(8.dp))

                // 1. Mental Health Support
                Text(
                    text = "1. Mental Health Support",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        // Open the mental health support website
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sadag.org/"))
                        context.startActivity(intent)
                    },
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Gender-Based Community Support
                Text(
                    text = "2. Gender-Based Community Support",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        // Open the community forum link
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://womenforchange.co.za/"))
                        context.startActivity(intent)
                    },
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Educational Context on Mental Health and Gender
                Text(
                    text = "3. Educational Context on Mental Health & Gender",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        // Open the educational context link
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tandfonline.com/doi/full/10.1080/00131911.2024.2306947"))
                        context.startActivity(intent)
                    },
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 4. Legal Support - Human Rights Commission
                Text(
                    text = "4. Legal Support - Human Rights Commission",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        // Open the human rights commission link
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sahrc.org.za/"))
                        context.startActivity(intent)
                    },
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 5. Vacancies (Links to Available Jobs)
                Text(
                    text = "5. Vacancies (Available Jobs)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        // Open the vacancies link
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://za.jobrapido.com/?r=auto&w=openings&utm_agid=6793640388&utm_kwid=38748714923&msclkid=e61c5d58521812585a843a4932299032&utm_source=bing&utm_medium=cpc&utm_campaign=ZA_JOB_SEARCH_EXACT&utm_term=openings%20vacancies&utm_content=33"))
                        context.startActivity(intent)
                    },
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "About the App") },
        text = {
            Column {
                Text("This app is designed to help users report incidents and access important resources. ")
                Text("Version: 1.2.0")
                Text("Developed by: care teamwork group")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Help") },
        text = {
            Column {
                Text("To report an incident, click the 'Report Incident' button.")
                Text("For more information, visit our website or contact support.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun IncidentCard(incident: Map<String, Any>, showNextOfKin: Boolean = false) {
    // Set the background color of the card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White) // Explicitly set the card background to white
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp) // Padding inside the card
        ) {
            // Display incident details
            Text(
                text = "Police Station: ${incident["policeStation"] ?: "Unknown"}",
                fontWeight = FontWeight.Bold,
                color = Color.Black // Ensure text is visible
            )
            Text(
                text = "Location: ${incident["latitude"]}, ${incident["longitude"]}",
                color = Color.Black
            )
            Text(
                text = "Timestamp: ${incident["timestamp"] ?: "Unknown"}",
                color = Color.Black
            )

            // Conditionally display next of kin phone numbers if showNextOfKin is true
            if (showNextOfKin) {
                Text(
                    text = "Next of Kin Phone 1: ${incident["nextOfKinPhone1"] ?: "Not available"}",
                    color = Color.Black
                )
                Text(
                    text = "Next of Kin Phone 2: ${incident["nextOfKinPhone2"] ?: "Not available"}",
                    color = Color.Black
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Display VideoView inside the Card
            val videoUrl = incident["videoUrl"] as? String ?: ""  // Video URI should be stored in incident data

            if (videoUrl.isNotEmpty()) {
                // Wrap the video in a Surface to isolate it and control its rendering
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp) // Initial height, can be adjusted
                        .background(Color.Black), // Ensure the video section has a black background
                    color = Color.Transparent // Ensure the surface itself doesn't override the background
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            // Create the VideoView instance
                            val videoView = VideoView(context)

                            // Create MediaController for play/pause and fullscreen
                            val mediaController = MediaController(context).apply {
                                setAnchorView(videoView) // Anchor the controller to the VideoView
                            }

                            videoView.apply {
                                setVideoURI(Uri.parse(videoUrl))
                                setMediaController(mediaController)

                                // Ensure the video doesn't auto-play; user clicks 'play'
                                setOnPreparedListener { mediaPlayer ->
                                    mediaPlayer.isLooping = true // Video will loop if required
                                    mediaPlayer.setVolume(1f, 1f) // Enable volume
                                    // Don't call mediaPlayer.start(), wait for user to press play
                                }


                                setOnClickListener {
                                    mediaController.show(0) // Show the controls when the video is clicked
                                }
                            }
                        }
                    )
                }
            } else {
                Text(
                    text = "No video available for this incident",
                    color = Color.Black
                )
            }
        }
    }
}







private fun trackLocation(context: Context) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                // Use the location object as needed
                val latitude = it.latitude
                val longitude = it.longitude
                // For example, log or save the location
                Toast.makeText(context, "Location: $latitude, $longitude", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(context, "Location not available", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(context, "Failed to get location", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Location permission not granted", Toast.LENGTH_SHORT).show()
    }
}



@Composable
fun SettingsDropdownMenu(navController: NavController) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_settings), // Replace with your actual settings icon resource
                contentDescription = "Settings"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                modifier = Modifier.fillMaxWidth(), // Optional: to make it full width
                onClick = {
                    expanded = false
                    navController.navigate("edit_account") // Navigate to edit account
                },
                text = { Text("Edit Account") } // Providing text as a composable
            )
            DropdownMenuItem(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    expanded = false
                    // Handle theme change logic here

                },
                text = { Text("Theme") }
            )
            DropdownMenuItem(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    expanded = false
                    navController.navigate("my_secret_journal") // Navigate to the journal screen
                },
                text = { Text("My Secret Journal") }
            )

            DropdownMenuItem(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    expanded = false
                    // Handle logout logic here
                    logoutAndRedirect(context)
                },
                text = { Text("Logout") }
            )

        }
    }
}


@Composable
fun EditAccountScreen(navController: NavController) {
    // Define state variables for the user information
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var nextOfKinPhone1 by remember { mutableStateOf("") } // Next of kin phone 1
    var nextOfKinPhone2 by remember { mutableStateOf("") } // Next of kin phone 2

    // Get the context from the composable scope
    val context = LocalContext.current

    val db = FirebaseFirestore.getInstance()
    val userId = FirebaseAuth.getInstance().currentUser?.uid

    // Load user data from Firestore when the screen is displayed
    LaunchedEffect(Unit) {
        userId?.let { id ->
            db.collection("users").document(id)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        firstName = document.getString("firstName") ?: ""
                        lastName = document.getString("lastName") ?: ""
                        phone = document.getString("phone") ?: ""
                        nextOfKinPhone1 = document.getString("nextOfKinPhone1") ?: ""
                        nextOfKinPhone2 = document.getString("nextOfKinPhone2") ?: ""
                    }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Edit Account",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Text field for First Name
        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("First Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Text field for Last Name
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Last Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Text field for Phone
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Text field for Next of Kin Phone 1 (Optional)
        OutlinedTextField(
            value = nextOfKinPhone1,
            onValueChange = { nextOfKinPhone1 = it },
            label = { Text("Next of Kin Phone Number 1 (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Text field for Next of Kin Phone 2 (Optional)
        OutlinedTextField(
            value = nextOfKinPhone2,
            onValueChange = { nextOfKinPhone2 = it },
            label = { Text("Next of Kin Phone Number 2 (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Save button
        Button(
            onClick = {
                // Save updated data to Firestore
                val userUpdates = mapOf(
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "phone" to phone,
                    "nextOfKinPhone1" to nextOfKinPhone1,
                    "nextOfKinPhone2" to nextOfKinPhone2
                )
                userId?.let { id ->
                    db.collection("users").document(id)
                        .update(userUpdates)
                        .addOnSuccessListener {
                            // Show success message
                            Toast.makeText(context, "Account updated successfully", Toast.LENGTH_SHORT).show()
                            navController.popBackStack() // Navigate back after saving
                        }
                        .addOnFailureListener {
                            // Show failure message
                            Toast.makeText(context, "Failed to update account", Toast.LENGTH_SHORT).show()
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Changes")
        }
    }
}


@Composable
fun MySecretJournalScreen() {
    var storyText by remember { mutableStateOf("") }
    var savedStories by remember { mutableStateOf<List<String>>(emptyList()) }
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    // Ensure that the user is logged in
    if (currentUser == null) {
        Text(text = "Please log in to view your journal.")
        return
    }

    val userId = currentUser.uid

    // Load saved stories for the current user when the screen starts
    LaunchedEffect(Unit) {
        db.collection("secret_journal")
            .whereEqualTo("userId", userId) // Query only the stories that belong to the current user
            .get()
            .addOnSuccessListener { result ->
                val stories = result.documents.mapNotNull { it.getString("story") }
                savedStories = stories
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "My Secret Journal",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Text area for writing a story
        BasicTextField(
            value = storyText,
            onValueChange = { storyText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(8.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface)
                .padding(8.dp),
            textStyle = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Save button
        Button(
            onClick = {
                // Save the story to Firestore along with the userId
                val storyData = hashMapOf(
                    "story" to storyText,
                    "userId" to userId // Store the userId for privacy
                )
                db.collection("secret_journal")
                    .add(storyData)
                    .addOnSuccessListener {
                        // Clear the text field after saving
                        storyText = ""
                        // Refresh the saved stories
                        db.collection("secret_journal")
                            .whereEqualTo("userId", userId)
                            .get()
                            .addOnSuccessListener { result ->
                                val stories = result.documents.mapNotNull { it.getString("story") }
                                savedStories = stories
                            }
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Story")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Display saved stories
        Text(
            text = "Saved Stories:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (savedStories.isEmpty()) {
            Text(text = "No stories saved yet.")
        } else {
            savedStories.forEachIndexed { index, story ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = story,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )

                    // Edit button
                    Text(
                        text = "\u270E", // Unicode pencil icon for edit
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                // Handle edit action
                                storyText = story // Load the selected story into the text field for editing
                            }
                    )

                    // Delete button
                    Text(
                        text = "\u2716", // Unicode X icon for delete
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                // Handle delete action
                                db.collection("secret_journal")
                                    .whereEqualTo("story", story)
                                    .whereEqualTo("userId", userId) // Ensure only the current user's story is deleted
                                    .get()
                                    .addOnSuccessListener { result ->
                                        result.documents.forEach { doc ->
                                            db.collection("secret_journal")
                                                .document(doc.id)
                                                .delete()
                                                .addOnSuccessListener {
                                                    // Refresh the saved stories after deletion
                                                    savedStories = savedStories.toMutableList().also {
                                                        it.removeAt(index)
                                                    }
                                                }
                                        }
                                    }
                            }
                    )
                }
                HorizontalDivider()
            }
        }
    }
}







private fun logoutAndRedirect(context: Context) {
    // Redirect to MainActivity
    val intent = Intent(context, MainActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // Clear task stack
    context.startActivity(intent)
}

@Composable
fun MyReportsScreen(navController: NavController) {
    // State to hold reports and loading state
    var reports by remember { mutableStateOf(emptyList<Map<String, Any>>()) }
    var isLoading by remember { mutableStateOf(true) }

    // Firestore instance
    val db: FirebaseFirestore = Firebase.firestore

    // Fetch reports from Firestore
    LaunchedEffect(Unit) {
        // Get the currently logged-in user
        val currentUser = FirebaseAuth.getInstance().currentUser
        val userId = currentUser?.uid // Get the user ID

        if (userId != null) {
            db.collection("incidents")
                .whereEqualTo("userId", userId) // Filter reports by the logged-in user
                .get()
                .addOnSuccessListener { querySnapshot ->
                    reports = querySnapshot.documents.mapNotNull { document ->
                        val data = document.data
                        data?.let { it.toMutableMap().apply { this["id"] = document.id } }
                    }
                    isLoading = false
                }
                .addOnFailureListener { exception ->
                    println("Error getting documents: $exception")
                    isLoading = false
                }
        } else {
            isLoading = false // No user is logged in
        }
    }

    // Define the onDelete function inline
    val onDelete: (String) -> Unit = { reportId ->
        db.collection("incidents").document(reportId).delete()
            .addOnSuccessListener {
                reports = reports.filter { report -> report["id"] != reportId }
            }
            .addOnFailureListener { exception ->
                println("Error deleting document: $exception")
            }
    }

    // Display loading spinner while data is loading
    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "My Reports",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            // List of reports
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(reports) { report ->
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Use IncidentCard to display the report details, including nextOfKinPhone1 and nextOfKinPhone2
                        IncidentCard(incident = report, showNextOfKin = true) // Enable display of next of kin fields

                        // Delete Icon
                        IconButton(
                            onClick = {
                                val reportId = report["id"] as? String ?: return@IconButton
                                onDelete(reportId)
                            },
                            modifier = Modifier.align(Alignment.TopEnd) // Align the icon at the top-right corner
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Incident",
                                tint = Color.Red // Set the color for the delete icon
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp)) // Space between cards
                }
            }


            // Back button
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Back")
            }
        }
    }
}






@Composable
fun FeedbackScreen(navController: NavController) {
    // Your implementation for FeedbackScreen
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), // Add padding for better spacing
        verticalArrangement = Arrangement.Center, // Center content vertically
        horizontalAlignment = Alignment.CenterHorizontally // Center content horizontally
    ) {
        // Display the feedback message
        Text(
            text = "No feedback received at the moment, check later.",
            style = MaterialTheme.typography.bodyLarge, // You can style the text as needed
            textAlign = TextAlign.Center, // Center the text
            color = Color.Black // Ensure text is visible
        )

        // Add a Back button
        Spacer(modifier = Modifier.height(16.dp)) // Add some space before the button
        Button(onClick = { navController.popBackStack() }) {
            Text("Back")
        }
    }
}




@Preview(showBackground = true)
@Composable
fun CareScreenPreview() {
    CareScreen(firstName = "John", lastName = "Doe", onReportIncident = {}, navController = rememberNavController())
}