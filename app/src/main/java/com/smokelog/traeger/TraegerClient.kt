package com.smokelog.traeger

import com.smokelog.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

data class Grill(val thingName: String, val name: String)

data class GrillStatus(
    val time: Long,
    val grillF: Double?,
    val setF: Double?,
    val probeF: Double?,
    val probeTargetF: Double?,
    val probe2F: Double?,
    val connected: Boolean,
)

sealed interface GrillUpdate {
    data class Status(val status: GrillStatus) : GrillUpdate
    data class Problem(val message: String) : GrillUpdate
}

class TraegerException(message: String) : Exception(message)

/**
 * Talks to Traeger's cloud the same way the WiFIRE app does. Traeger publishes no
 * official public API, so these endpoints are the ones used by community
 * integrations (e.g. the Home Assistant "traeger" integration, values as of
 * mid-2026) and may change without notice. The flow is:
 *
 *  1. Cognito USER_PASSWORD_AUTH  -> IdToken
 *  2. GET  /users/self            -> list of grills ("things")
 *  3. POST /mqtt-connections      -> pre-signed AWS IoT websocket URL
 *  4. MQTT subscribe prod/thing/update/{thing}, then POST command "90" to ask the
 *     grill to publish its current state. Updates are pushed as the grill changes.
 */
class TraegerClient(private val settings: Settings) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var idToken: String? = null
    private var tokenExpiresAt = 0L
    private var tokenFor: Pair<String, String>? = null

    private suspend fun token(): String = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val creds = s.traegerEmail to s.traegerPassword
        idToken?.takeIf { tokenFor == creds && System.currentTimeMillis() < tokenExpiresAt - 60_000 }?.let { return@withContext it }
        if (!s.hasTraeger) throw TraegerException("Add your Traeger account in Settings")
        val body = buildJsonObject {
            put("AuthFlow", "USER_PASSWORD_AUTH")
            put("ClientId", COGNITO_CLIENT_ID)
            putJsonObject("AuthParameters") {
                put("USERNAME", s.traegerEmail.trim().lowercase())
                put("PASSWORD", s.traegerPassword)
            }
            putJsonObject("ClientMetadata") {}
        }.toString()
        val req = Request.Builder()
            .url(COGNITO_URL)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .post(body.toRequestBody("application/x-amz-json-1.1".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!resp.isSuccessful || obj == null) {
                val msg = obj?.get("message")?.jsonPrimitive?.content ?: "HTTP ${resp.code}"
                throw TraegerException("Traeger sign-in failed: $msg")
            }
            val auth = obj["AuthenticationResult"]?.jsonObject ?: throw TraegerException("Traeger sign-in returned no token")
            val tok = auth["IdToken"]!!.jsonPrimitive.content
            val expiresIn = auth["ExpiresIn"]?.jsonPrimitive?.longOrNull ?: 3600
            idToken = tok
            tokenFor = creds
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000
            tok
        }
    }

    private suspend fun api(method: String, path: String, body: JsonObject? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(API_BASE + path).header("Authorization", "Bearer ${token()}")
            when (method) {
                "GET" -> builder.get()
                else -> builder.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            }
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) { idToken = null }
                if (!resp.isSuccessful) throw TraegerException("Traeger API $path failed: HTTP ${resp.code}")
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
            }
        }

    /** Verifies credentials and returns the grills on the account (plus the built-in demo grill). */
    suspend fun listGrills(): List<Grill> {
        val self = api("GET", "/users/self").jsonObject
        val things = self["things"] as? JsonArray ?: JsonArray(emptyList())
        return things.mapNotNull { el ->
            val o = el.jsonObject
            val thing = o["thingName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Grill(thing, o["friendlyName"]?.jsonPrimitive?.content ?: thing)
        }
    }

    private suspend fun requestStateUpdate(thing: String) {
        api("POST", "/things/$thing/commands", buildJsonObject { put("command", "90") })
    }

    private suspend fun signedMqttUrl(): Pair<String, Long> {
        val o = api("POST", "/mqtt-connections").jsonObject
        val url = o["signedUrl"]?.jsonPrimitive?.content ?: throw TraegerException("No MQTT URL returned")
        val exp = o["expirationSeconds"]?.jsonPrimitive?.longOrNull ?: 3600
        return url to exp
    }

    /**
     * Live grill updates. Reconnects on drop and before the signed URL expires.
     * Use [DEMO_THING] to get a simulated cook (useful without a grill).
     */
    fun updates(thing: String): Flow<GrillUpdate> =
        if (thing == DEMO_THING) demoUpdates() else channelFlow {
            while (isActive) {
                var client: MqttAsyncClient? = null
                try {
                    val (url, expSec) = signedMqttUrl()
                    val lost = CompletableDeferred<Throwable?>()
                    val c = MqttAsyncClient(url, "smokelog-${UUID.randomUUID()}", MemoryPersistence())
                    client = c
                    c.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) { lost.complete(cause) }
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                        override fun messageArrived(topic: String?, message: MqttMessage) {
                            parseStatus(message.payload.decodeToString())?.let { trySend(GrillUpdate.Status(it)) }
                        }
                    })
                    val opts = MqttConnectOptions().apply {
                        isCleanSession = true
                        keepAliveInterval = 30
                        connectionTimeout = 20
                        mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                        isAutomaticReconnect = false
                    }
                    withContext(Dispatchers.IO) {
                        c.connect(opts).waitForCompletion(30_000)
                        c.subscribe("prod/thing/update/$thing", 1).waitForCompletion(30_000)
                    }
                    requestStateUpdate(thing)
                    val refreshMs = max(60L, expSec - 120) * 1000
                    val cause = withTimeoutOrNull(refreshMs) { lost.await() }
                    if (cause != null) send(GrillUpdate.Problem("Connection lost: ${cause.message ?: "unknown"} – reconnecting"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    send(GrillUpdate.Problem(e.message ?: e.javaClass.simpleName))
                    delay(if (e is TraegerException && e.message?.contains("sign-in") == true) 120_000 else 15_000)
                } finally {
                    client?.let { c -> runCatching { if (c.isConnected) c.disconnectForcibly(1000); c.close() } }
                }
            }
        }

    /** Parses a `prod/thing/update/...` payload. Exposed for tests. */
    fun parseStatus(payload: String): GrillStatus? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        val st = root["status"]?.jsonObject ?: return null
        fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.doubleOrNull
        val celsius = (st["units"] as? JsonPrimitive)?.intOrNull == 0
        fun f(v: Double?) = v?.let { if (celsius) it * 9 / 5 + 32 else it }

        val probeConnected = (st["probe_con"] as? JsonPrimitive)?.intOrNull?.let { it == 1 } ?: true
        // Grills with multiple probes report them as accessories.
        val accProbes = (st["acc"] as? JsonArray)?.mapNotNull { el ->
            val a = el.jsonObject
            if (a["type"]?.jsonPrimitive?.content != "probe") return@mapNotNull null
            if ((a["con"] as? JsonPrimitive)?.intOrNull == 0) return@mapNotNull null
            val p = a["probe"]?.jsonObject
            num(p, "get_temp") to num(p, "set_temp")
        }.orEmpty()

        GrillStatus(
            time = System.currentTimeMillis(),
            grillF = f(num(st, "grill")),
            setF = f(num(st, "set")),
            probeF = f(if (probeConnected) num(st, "probe") else accProbes.firstOrNull()?.first),
            probeTargetF = f(num(st, "probe_set")?.takeIf { it > 0 } ?: accProbes.firstOrNull()?.second),
            probe2F = f(if (probeConnected) accProbes.firstOrNull()?.first else accProbes.getOrNull(1)?.first),
            connected = (st["connected"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        )
    }.getOrNull()

    /** Simulated brisket: 225°F pit, probe climbs, stalls ~160°F, finishes ~203°F. One sim-minute per second. */
    private fun demoUpdates(): Flow<GrillUpdate> = flow {
        var probe = 38.0
        var minute = 0
        while (true) {
            val stall = exp(-((probe - 162) * (probe - 162)) / 60.0)          // slows near 162
            probe += (225 - probe) * 0.006 * (1 - 0.92 * stall)
            val pit = 225 + 6 * kotlin.math.sin(minute / 9.0) + Random.nextDouble(-2.0, 2.0)
            emit(GrillUpdate.Status(GrillStatus(System.currentTimeMillis(), pit, 225.0, probe, 203.0, probe - 4, true)))
            minute++
            delay(1000)
        }
    }

    companion object {
        const val DEMO_THING = "demo"
        private const val COGNITO_URL = "https://cognito-idp.us-west-2.amazonaws.com/"
        private const val COGNITO_CLIENT_ID = "4id473dsrcq4kevlgrikukqn2a"
        private const val API_BASE = "https://mobile-iot-api.iot.traegergrills.io"
    }
}
