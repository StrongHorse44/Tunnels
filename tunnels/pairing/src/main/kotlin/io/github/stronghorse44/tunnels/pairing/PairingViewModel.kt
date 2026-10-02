package io.github.stronghorse44.tunnels.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant

/** Where the exchange stands, on either phone. */
sealed interface Step {
    data object Home : Step

    /** Verifier: the challenge on screen; [scanning] once the user moved on to reading the answer. */
    data class Challenge(val challenge: PairingProtocol.Challenge, val scanning: Boolean = false, val received: Int = 0, val total: Int = 0) : Step

    data class Result(val verdict: Verdict) : Step

    /** Checked phone: reading the verifier's challenge. */
    data object ScanChallenge : Step

    data object Working : Step

    /** Checked phone: the answer on screen, cycling through its parts. */
    data class Answer(val challenge: PairingProtocol.Challenge, val parts: List<String>, val kind: PairingProtocol.Kind) : Step

    data class Error(val message: String) : Step
}

data class PairingState(val step: Step = Step.Home, val pins: List<Pin> = emptyList())

class PairingViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private var collector: Parts.Collector? = null

    init {
        viewModelScope.launch { _state.update { it.copy(pins = loadPins()) } }
    }

    fun home() {
        collector = null
        _state.update { it.copy(step = Step.Home) }
    }

    // Verifier

    fun startVerifying() {
        viewModelScope.launch {
            val challenge = PairingProtocol.Challenge(random(PairingProtocol.CHALLENGE_BYTES), verifierId())
            collector = Parts.Collector(challenge.tag)
            _state.update { it.copy(step = Step.Challenge(challenge)) }
        }
    }

    fun scanAnswer() {
        _state.update { s -> (s.step as? Step.Challenge)?.let { s.copy(step = it.copy(scanning = true)) } ?: s }
    }

    fun onAnswerScanned(text: String) {
        val step = _state.value.step as? Step.Challenge ?: return
        val c = collector ?: return
        val payload = c.add(text)
        if (payload == null) {
            if (c.received > step.received || c.total != step.total) _state.update { it.copy(step = step.copy(received = c.received, total = c.total)) }
            return
        }
        collector = null
        viewModelScope.launch {
            val response = PairingProtocol.Response.decode(payload)
            if (response == null) {
                _state.update { it.copy(step = Step.Error("That code could not be read as an answer. Start again on both phones.")) }
                return@launch
            }
            val pins = _state.value.pins
            val verdict = withContext(Dispatchers.Default) { PairingVerifier.verify(response, step.challenge, pins, Instant.now()) }
            val updated = verdict.pin?.takeIf { !verdict.failed }?.let { pin -> pins.filter { it.id != pin.id } + pin } ?: pins
            if (updated !== pins) savePins(updated)
            _state.update { it.copy(step = Step.Result(verdict), pins = updated) }
        }
    }

    fun forget(pin: Pin) {
        val updated = _state.value.pins.filter { it.id != pin.id }
        _state.update { it.copy(pins = updated) }
        viewModelScope.launch { savePins(updated) }
    }

    // Checked phone

    fun startBeingChecked() {
        _state.update { it.copy(step = Step.ScanChallenge) }
    }

    fun onChallengeScanned(text: String) {
        if (_state.value.step != Step.ScanChallenge) return
        val challenge = PairingProtocol.Challenge.decode(text) ?: return
        answer(challenge, fresh = false)
    }

    /** Answers again with a new identity key: for a verifier that lost or forgot this phone's pairing. */
    fun pairAgain() {
        val step = _state.value.step as? Step.Answer ?: return
        answer(step.challenge, fresh = true)
    }

    private fun answer(challenge: PairingProtocol.Challenge, fresh: Boolean) {
        _state.update { it.copy(step = Step.Working) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (fresh) AuditeeKeys.forget(challenge.verifierId)
                    val response = AuditeeKeys.respond(challenge)
                    Step.Answer(challenge, Parts.split(response.encode(), challenge.tag), response.kind)
                }
            }
            _state.update {
                it.copy(
                    step = result.getOrElse { e ->
                        Step.Error("This phone's keystore could not attest a key (${e.javaClass.simpleName}). Hardware attestation is needed for a check.")
                    },
                )
            }
        }
    }

    // Storage: the encrypted store, like every other setting.

    private suspend fun loadPins(): List<Pin> = withContext(Dispatchers.IO) {
        runCatching { Pin.decode(TunnelsStore.get(app).setting(Pin.KEY)) }.getOrDefault(emptyList())
    }

    private suspend fun savePins(pins: List<Pin>) = withContext(Dispatchers.IO) {
        runCatching { TunnelsStore.get(app).putSetting(Pin.KEY, Pin.encode(pins).ifEmpty { null }) }
    }

    /** This phone's verifier id: random, made once, so answers made for another verifier are refused. */
    private suspend fun verifierId(): ByteArray = withContext(Dispatchers.IO) {
        val store = TunnelsStore.get(app)
        store.setting(VERIFIER_ID_KEY)?.let(::unhex)?.takeIf { it.size == PairingProtocol.VERIFIER_ID_BYTES }
            ?: random(PairingProtocol.VERIFIER_ID_BYTES).also { store.putSetting(VERIFIER_ID_KEY, it.joinToString("") { b -> "%02x".format(b) }) }
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun unhex(s: String): ByteArray? = runCatching { s.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()

    companion object {
        const val VERIFIER_ID_KEY = "pairing.verifierId"
    }
}
