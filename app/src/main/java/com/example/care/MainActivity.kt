package com.example.care

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.care.ui.theme.CareTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock


class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Firebase Auth and Firestore
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setContent {
            CareTheme {
                var showSplashScreen by remember { mutableStateOf(true) }

                // Splash screen delay
                LaunchedEffect(Unit) {
                    delay(5000)
                    showSplashScreen = false
                }

                if (showSplashScreen) {
                    SplashScreen()
                } else {
                    var showLogin by remember { mutableStateOf(true) }

                    // Switch between login and register forms
                    if (showLogin) {
                        LoginForm(
                            auth = auth,
                            firestore = firestore,  // Add this line
                            onToggle = { showLogin = false }
                        )
                    } else {
                        RegisterForm(
                            auth = auth,
                            firestore = firestore,
                            onToggle = { showLogin = true }
                        )
                    }

                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Splash image
            Image(
                painter = painterResource(id = R.drawable.ic_care_battery), // Replace with your image
                contentDescription = "Care Image",
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(16.dp)) // Space between image and text

            // "Care" text
            Text(
                text = "Care",
                style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary),
                color = Color(0xFF4CAF50) // Custom green color
            )
        }
    }
}

@Composable
fun LoginForm(auth: FirebaseAuth, firestore: FirebaseFirestore, onToggle: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPasswordResetEmailSent by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Login", style = MaterialTheme.typography.headlineSmall)

        TextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(imageVector = Icons.Default.Email, contentDescription = "Email Icon")
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                Icon(imageVector = Icons.Default.Lock, contentDescription = "Password Icon")
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage != null) {
            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
        }

        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = {
                // Validation checks for empty email and password
                if (email.isEmpty()) {
                    errorMessage = "Email is required."
                } else if (password.isEmpty()) {
                    errorMessage = "Password is required."
                } else {
                    loading = true
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            loading = false
                            if (task.isSuccessful) {
                                val user = auth.currentUser
                                user?.let {
                                    firestore.collection("users")
                                        .document(it.uid)
                                        .get()
                                        .addOnSuccessListener { document ->
                                            if (document != null && document.exists()) {
                                                val firstName = document.getString("firstName") ?: "Unknown"
                                                val lastName = document.getString("lastName") ?: "Unknown"

                                                // Navigate to CareActivity with retrieved data
                                                val intent = Intent(context, CareActivity::class.java).apply {
                                                    putExtra("firstName", firstName)
                                                    putExtra("lastName", lastName)
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                }
                                                context.startActivity(intent)
                                            } else {
                                                errorMessage = "User data not found."
                                            }
                                        }
                                        .addOnFailureListener {
                                            errorMessage = "Something went wrong."
                                        }
                                }
                            } else {
                                errorMessage = "Login failed. Please try again."
                            }
                        }
                }
            }) {
                Text(text = "Login")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onToggle) {
            Text("Don't have an account? Register")
        }

        // Forgot Password option
        TextButton(onClick = {
            if (email.isNotEmpty()) {
                auth.sendPasswordResetEmail(email)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            isPasswordResetEmailSent = true
                            errorMessage = "Password reset email sent."
                        } else {
                            errorMessage = "Error sending password reset email."
                        }
                    }
            } else {
                errorMessage = "Please enter your email to reset your password."
            }
        }) {
            Text("Forgot Password?")
        }

        if (isPasswordResetEmailSent) {
            Text(text = "Check your email to reset your password.", color = Color.Green)
        }
    }
}


@Composable
fun RegisterForm(auth: FirebaseAuth, firestore: FirebaseFirestore, onToggle: () -> Unit) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var nextOfKinPhone1 by remember { mutableStateOf("") }
    var nextOfKinPhone2 by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRegistrationSuccessful by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Register", style = MaterialTheme.typography.headlineSmall)

        // First Name field
        TextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("First Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Last Name field
        TextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Last Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Email field
        TextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Phone field
        TextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Next of Kin Phone Number 1
        TextField(
            value = nextOfKinPhone1,
            onValueChange = { nextOfKinPhone1 = it },
            label = { Text("Next of Kin Phone Number 1 (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Next of Kin Phone Number 2
        TextField(
            value = nextOfKinPhone2,
            onValueChange = { nextOfKinPhone2 = it },
            label = { Text("Next of Kin Phone Number 2 (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Password field
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Confirm Password field
        TextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Error message display
        if (errorMessage != null) {
            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
        }

        // Loading indicator or Register button
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = {
                // Regular expressions for validation
                val nameRegex = Regex("^[A-Za-z]+$")
                val phoneRegex = Regex("^0[0-9]{9}$")
                val emailRegex = Regex("^[A-Za-z0-9._%+-]+@gmail\\.com$")

                // Validate fields
                when {
                    firstName.isBlank() -> errorMessage = "First name is required"
                    !firstName.matches(nameRegex) -> errorMessage = "First name should contain only alphabets"

                    lastName.isBlank() -> errorMessage = "Last name is required"
                    !lastName.matches(nameRegex) -> errorMessage = "Last name should contain only alphabets"

                    email.isBlank() -> errorMessage = "Email is required"
                    !email.matches(emailRegex) -> errorMessage = "Email must be a valid @gmail.com address"

                    phone.isBlank() -> errorMessage = "Phone number is required"
                    !phone.matches(phoneRegex) -> errorMessage = "Phone number must be 10 digits and start with 0"

                    nextOfKinPhone1.isNotBlank() && !nextOfKinPhone1.matches(phoneRegex) -> errorMessage = "Next of kin phone 1 must be 10 digits and start with 0"
                    nextOfKinPhone2.isNotBlank() && !nextOfKinPhone2.matches(phoneRegex) -> errorMessage = "Next of kin phone 2 must be 10 digits and start with 0"

                    password.isBlank() -> errorMessage = "Password is required"
                    confirmPassword.isBlank() -> errorMessage = "Please confirm your password"
                    password != confirmPassword -> errorMessage = "Passwords do not match"

                    else -> {
                        // Clear any previous error message
                        errorMessage = null

                        // Continue with registration
                        loading = true
                        auth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener { task ->
                                loading = false
                                if (task.isSuccessful) {
                                    val user = auth.currentUser
                                    user?.let {
                                        val userData = hashMapOf(
                                            "firstName" to firstName,
                                            "lastName" to lastName,
                                            "email" to email,
                                            "phone" to phone
                                        )
                                        if (nextOfKinPhone1.isNotEmpty()) {
                                            userData["nextOfKinPhone1"] = nextOfKinPhone1
                                        }
                                        if (nextOfKinPhone2.isNotEmpty()) {
                                            userData["nextOfKinPhone2"] = nextOfKinPhone2
                                        }
                                        firestore.collection("users")
                                            .document(it.uid)
                                            .set(userData)
                                            .addOnSuccessListener {
                                                isRegistrationSuccessful = true
                                            }
                                            .addOnFailureListener {
                                                errorMessage = "Failed to save user data."
                                            }
                                    }
                                } else {
                                    errorMessage = "Registration failed. Please try again."
                                }
                            }
                    }
                }
            }) {
                Text(text = "Register")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Toggle to Login
        TextButton(onClick = onToggle) {
            Text("Already have an account? Login")
        }

        // Navigate to CareActivity on successful registration
        if (isRegistrationSuccessful) {
            LaunchedEffect(Unit) {
                val intent = Intent(context, CareActivity::class.java)
                intent.putExtra("firstName", firstName)
                intent.putExtra("lastName", lastName)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)
                isRegistrationSuccessful = false
            }
        }
    }
}







@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {  // Renamed from DefaultPreview to SplashScreenPreview
    CareTheme {
        SplashScreen()
    }
}

