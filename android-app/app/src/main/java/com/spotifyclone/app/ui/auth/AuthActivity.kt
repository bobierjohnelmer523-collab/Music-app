package com.spotifyclone.app.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.databinding.ActivityAuthBinding
import com.spotifyclone.app.model.LoginRequest
import com.spotifyclone.app.model.RegisterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var isRegisterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ApiClient.init(this)

        setupListeners()
    }

    private fun setupListeners() {
        binding.textToggleAuth.setOnClickListener {
            isRegisterMode = !isRegisterMode
            if (isRegisterMode) {
                binding.layoutEmail.visibility = View.VISIBLE
                binding.btnSubmitAuth.text = "Sign Up"
                binding.textToggleAuth.text = "Already have an account? Log in"
            } else {
                binding.layoutEmail.visibility = View.GONE
                binding.btnSubmitAuth.text = "Log In"
                binding.textToggleAuth.text = "Don't have an account? Sign up"
            }
        }

        binding.btnSubmitAuth.setOnClickListener {
            val username = binding.editUsername.text.toString().trim()
            val password = binding.editPassword.text.toString().trim()
            val email = binding.editEmail.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all required fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isRegisterMode && email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            executeAuth(username, email, password)
        }
    }

    private fun executeAuth(username: String, email: String, pass: String) {
        binding.authProgress.visibility = View.VISIBLE
        binding.btnSubmitAuth.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = ApiClient.getApi()
                val response = if (isRegisterMode) {
                    api.register(RegisterRequest(username, email, pass))
                } else {
                    api.login(LoginRequest(username, pass))
                }

                withContext(Dispatchers.Main) {
                    binding.authProgress.visibility = View.GONE
                    binding.btnSubmitAuth.isEnabled = true

                    if (response.isSuccessful && response.body() != null) {
                        val auth = response.body()!!
                        ApiClient.saveAuthData(auth.token, auth.username, auth.email)
                        Toast.makeText(this@AuthActivity, "Welcome, ${auth.username}!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        val err = response.errorBody()?.string() ?: "Authentication failed"
                        Toast.makeText(this@AuthActivity, err, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.authProgress.visibility = View.GONE
                    binding.btnSubmitAuth.isEnabled = true
                    Toast.makeText(this@AuthActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
