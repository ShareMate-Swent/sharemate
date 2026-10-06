package com.android.sharemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.auth.AuthGate
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.theme.SampleAppTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
  private val authViewModel: AuthViewModel by viewModels {
    viewModelFactory {
      initializer { AuthViewModel(FirebaseAuthRepository(FirebaseAuth.getInstance())) }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
            color = MaterialTheme.colorScheme.background) {
              AuthGate(authViewModel) {
                Column {
                  Greeting("Android")
                  TextButton(onClick = authViewModel::signOut) {
                    Text(stringResource(R.string.auth_sign_out))
                  }
                }
              }
            }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier.semantics { testTag = C.Tag.greeting })
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  SampleAppTheme { Greeting("Android") }
}
