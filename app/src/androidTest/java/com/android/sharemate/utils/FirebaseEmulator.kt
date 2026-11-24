// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.utils

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutionException
import org.json.JSONObject

/**
 * Gives instrumented tests access to the local Firebase emulators.
 *
 * The Firestore emulator enforces `firestore.rules`, so tests must act as a signed-in user and can
 * only reset data through the emulator's administrative REST endpoints.
 */
object FirebaseEmulator {
  const val HOST = "10.0.2.2"
  const val FIRESTORE_PORT = 8080
  const val AUTH_PORT = 9099
  const val STORAGE_PORT = 9199
  const val FUNCTIONS_PORT = 5001
  private const val TEST_PASSWORD = "emulator-only-password"
  private const val FIELDS = "fields"
  private const val STRING_VALUE = "stringValue"
  private const val INTEGER_VALUE = "integerValue"
  private const val AUTHORIZATION = "Authorization"
  private const val ADMIN_BEARER = "Bearer owner"
  private const val HTTP_NOT_FOUND = 404

  val firestore: FirebaseFirestore by lazy {
    FirebaseFirestore.getInstance().apply { useEmulator(HOST, FIRESTORE_PORT) }
  }

  val auth: FirebaseAuth by lazy {
    FirebaseAuth.getInstance().apply { useEmulator(HOST, AUTH_PORT) }
  }

  private val projectId: String
    get() = checkNotNull(FirebaseApp.getInstance().options.projectId)

  /**
   * The Storage bucket the Functions emulator uses, which is the project's default bucket. It is
   * not the one of the app's `google-services` configuration, so files that a callable must delete
   * are created here.
   */
  val functionsBucket: String
    get() = "$projectId.appspot.com"

  /**
   * Signs in as the emulator account named [alias], creating it on first use.
   *
   * @return The user identifier of the signed-in account.
   */
  fun signInAs(alias: String): String = signIn(auth, alias)

  /**
   * A second client, independent of [firestore], signed in as the account [alias].
   *
   * [signInAs] switches the user of the shared client, which would also switch the user of any
   * listener it has open. A second client lets another user act while such a listener stays open.
   * Close it when done.
   */
  class SecondClient internal constructor(private val app: FirebaseApp) : AutoCloseable {
    val firestore: FirebaseFirestore =
        FirebaseFirestore.getInstance(app).apply { useEmulator(HOST, FIRESTORE_PORT) }

    override fun close() {
      Tasks.await(firestore.terminate())
      app.delete()
    }
  }

  fun secondClientAs(alias: String): SecondClient {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val app =
        FirebaseApp.initializeApp(
            context, FirebaseApp.getInstance().options, "second-client-${UUID.randomUUID()}")
    signIn(FirebaseAuth.getInstance(app).apply { useEmulator(HOST, AUTH_PORT) }, alias)
    return SecondClient(app)
  }

  private fun signIn(auth: FirebaseAuth, alias: String): String {
    val email = "$alias@example.com"
    val result =
        try {
          Tasks.await(auth.createUserWithEmailAndPassword(email, TEST_PASSWORD))
        } catch (e: ExecutionException) {
          if (e.cause !is FirebaseAuthUserCollisionException) throw e
          Tasks.await(auth.signInWithEmailAndPassword(email, TEST_PASSWORD))
        }
    awaitUserSwitch()
    return checkNotNull(result.user).uid
  }

  fun signOut() {
    auth.signOut()
    awaitUserSwitch()
  }

  /**
   * Lets Firestore see the new user before the test writes anything.
   *
   * Firebase Auth tells Firestore about a user change on the main thread. A write issued before
   * that is filed under the previous user and never sent, so its confirmation never arrives (#285).
   * Draining the main thread delivers the change first; Firestore then handles it before any later
   * write.
   */
  private fun awaitUserSwitch() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
  }

  /** Creates a separately authenticated emulator client for concurrency tests. */
  fun newSignedInApp(alias: String): SignedInApp {
    val app =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext<Context>(),
            FirebaseApp.getInstance().options,
            "pseudo-claim-$alias-${System.nanoTime()}")
    val auth = FirebaseAuth.getInstance(app).apply { useEmulator(HOST, AUTH_PORT) }
    val firestore = FirebaseFirestore.getInstance(app).apply { useEmulator(HOST, FIRESTORE_PORT) }
    val user =
        Tasks.await(auth.createUserWithEmailAndPassword("$alias@example.com", TEST_PASSWORD)).user!!
    return SignedInApp(app, user.uid, firestore)
  }

  /** Signs out and deletes every account and document stored in the emulators. */
  fun clear() {
    auth
    firestore
    signOut()
    delete("http://$HOST:$AUTH_PORT/emulator/v1/projects/$projectId/accounts")
    delete(
        "http://$HOST:$FIRESTORE_PORT/emulator/v1/projects/$projectId/databases/(default)/documents")
  }

  /**
   * Creates the document [collection]/[documentId] with [fields] without going through
   * `firestore.rules`, to model data written before a rule existed. A field value must be a
   * [String], an [Int] or a [Long].
   */
  fun seedDocument(collection: String, documentId: String, fields: Map<String, Any>) {
    val typedFields = JSONObject()
    fields.forEach { (name, value) -> typedFields.put(name, typedValue(value)) }
    val body = JSONObject().put(FIELDS, typedFields).toString()
    val url =
        "http://$HOST:$FIRESTORE_PORT/v1/projects/$projectId/databases/(default)/documents/" +
            "$collection?documentId=$documentId"
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      check(connection.responseCode in 200..299) {
        "Could not seed $collection/$documentId: HTTP ${connection.responseCode}"
      }
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Reads the document at [path] without going through `firestore.rules`.
   *
   * @return The document's `fields` in Firestore's typed JSON, empty for a document without fields,
   *   or null when the document does not exist.
   */
  fun documentFields(path: String): JSONObject? {
    val url =
        "http://$HOST:$FIRESTORE_PORT/v1/projects/$projectId/databases/(default)/documents/$path"
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
      connection.setRequestProperty(AUTHORIZATION, ADMIN_BEARER)
      if (connection.responseCode == HTTP_NOT_FOUND) return null
      check(connection.responseCode in 200..299) {
        "Could not read $path: HTTP ${connection.responseCode}"
      }
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      JSONObject(body).optJSONObject(FIELDS) ?: JSONObject()
    } finally {
      connection.disconnect()
    }
  }

  /** Creates the Storage object [name] in [bucket] without going through `storage.rules`. */
  fun seedStorageObject(bucket: String, name: String, bytes: ByteArray, contentType: String) {
    val connection = URL(storageUrl(bucket, "?name=${encode(name)}")).openConnection()
    (connection as HttpURLConnection).apply {
      try {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty(AUTHORIZATION, ADMIN_BEARER)
        setRequestProperty("Content-Type", contentType)
        outputStream.use { it.write(bytes) }
        check(responseCode in 200..299) { "Could not seed $name: HTTP $responseCode" }
      } finally {
        disconnect()
      }
    }
  }

  /** The names of the Storage objects of [bucket] that start with [prefix]. */
  fun storageObjectNames(bucket: String, prefix: String): List<String> {
    val connection = URL(storageUrl(bucket, "?prefix=${encode(prefix)}")).openConnection()
    (connection as HttpURLConnection).apply {
      try {
        setRequestProperty(AUTHORIZATION, ADMIN_BEARER)
        check(responseCode in 200..299) { "Could not list $prefix: HTTP $responseCode" }
        val body = inputStream.bufferedReader().use { it.readText() }
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return List(items.length()) { items.getJSONObject(it).getString("name") }
      } finally {
        disconnect()
      }
    }
  }

  /** Deletes the Storage objects of [bucket] that start with [prefix]. */
  fun deleteStorageObjects(bucket: String, prefix: String) {
    storageObjectNames(bucket, prefix).forEach { name ->
      delete(storageUrl(bucket, "/${encode(name)}"))
    }
  }

  private fun storageUrl(bucket: String, suffix: String) =
      "http://$HOST:$STORAGE_PORT/v0/b/$bucket/o$suffix"

  private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

  private fun typedValue(value: Any): JSONObject =
      when (value) {
        is String -> JSONObject().put(STRING_VALUE, value)
        is Int,
        is Long -> JSONObject().put(INTEGER_VALUE, value.toString())
        else -> throw IllegalArgumentException("Unsupported seeded value: $value")
      }

  private fun delete(url: String) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "DELETE"
      connection.setRequestProperty("Authorization", "Bearer owner")
      check(connection.responseCode in 200..299) {
        "Could not clear emulator data at $url: HTTP ${connection.responseCode}"
      }
    } finally {
      connection.disconnect()
    }
  }

  /** A secondary Firebase app and user used to verify cross-client claim races. */
  data class SignedInApp(
      /** The separately initialized Firebase app. */
      val app: FirebaseApp,
      /** The UID authenticated in [app]. */
      val uid: String,
      /** Firestore client authenticated as [uid]. */
      val firestore: FirebaseFirestore,
  )
}
