package pl.victor.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Zbiera z systemu to, czego [MediaModeAdvisor] potrzebuje do rozstrzygnięcia,
 * dlaczego tryb multimediów okularów nie wstaje.
 *
 * Wyłącznie publiczne API: lista sparowanych urządzeń, ich profile
 * (`getUuids`) i stan profilu A2DP przez proxy `BluetoothProfile.A2DP`.
 * Niczego nie łączymy - `BluetoothA2dp.connect` jest ukryte i na Androidzie 13
 * zarezerwowane dla aplikacji systemowych. Łączenie profilu należy do
 * człowieka (przełącznik w ustawieniach Bluetooth) i do systemu.
 */
internal object MediaModeProbe {

    private const val TAG = "MediaModeProbe"

    /** Ile czekać na proxy profilu A2DP - zwykle odpowiada w kilkadziesiąt ms. */
    private const val PROXY_TIMEOUT_MS = 3_000L

    /** Ile czekać na odświeżenie listy profili po `fetchUuidsWithSdp`. */
    private const val SDP_WAIT_MS = 3_000L

    @SuppressLint("MissingPermission")
    suspend fun diagnose(
        context: Context,
        audioName: String?,
        bleName: String?,
        bleAddress: String?
    ): MediaModeDiagnosis {
        val app = context.applicationContext
        if (!hasConnectPermission(app)) return MediaModeDiagnosis.NO_PERMISSION
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return MediaModeDiagnosis.PROBE_FAILED

        val bonded: List<BluetoothDevice> = runCatching { adapter.bondedDevices?.toList() }
            .onFailure { Log.w(TAG, "Brak dostępu do sparowanych urządzeń", it) }
            .getOrNull()
            .orEmpty()
        val candidates = bonded.map { it.toCandidate() }
        val chosen = MediaModeAdvisor.pickGlassesDevice(candidates, audioName, bleName, bleAddress)
        val device = chosen?.let { c -> bonded.firstOrNull { it.address.equals(c.address, ignoreCase = true) } }
        if (chosen == null || device == null) {
            return MediaModeDiagnosis.NOT_FOUND
        }

        // PROFILE Z PAMIĘCI PODRĘCZNEJ BYWAJĄ PUSTE.
        //
        // Android trzyma listę profili z ostatniego zapytania SDP i po
        // wyczyszczeniu pamięci Bluetooth potrafi jej nie mieć. Wtedy pytamy
        // okulary raz (publiczne `fetchUuidsWithSdp`) i czytamy jeszcze raz -
        // bez tego "nie wiadomo" byłoby odpowiedzią częściej, niż musi.
        var uuids = chosen.uuids
        if (uuids == null) {
            runCatching { device.fetchUuidsWithSdp() }
                .onFailure { Log.w(TAG, "fetchUuidsWithSdp nie powiodło się", it) }
            delay(SDP_WAIT_MS)
            uuids = runCatching { device.uuids?.map { it.uuid.toString() } }.getOrNull()
        }

        return MediaModeDiagnosis(
            permission = true,
            deviceName = chosen.name,
            bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(true),
            classic = chosen.classic,
            declaresA2dpSink = MediaModeAdvisor.declaresA2dpSink(uuids),
            declaresHandsFree = MediaModeAdvisor.declaresHandsFree(uuids),
            uuidCount = uuids?.size,
            a2dpState = a2dpState(app, adapter, device)
        )
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toCandidate(): BondedCandidate {
        val type = runCatching { type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        return BondedCandidate(
            name = runCatching { name }.getOrNull(),
            address = address,
            classic = type != BluetoothDevice.DEVICE_TYPE_LE,
            uuids = runCatching { uuids?.map { it.uuid.toString() } }.getOrNull()
        )
    }

    /**
     * Stan profilu A2DP dla okularów, wprost z usługi profilu.
     *
     * Lista wyjść audio (`hasA2dpOutput`) mówi tylko "jest albo nie ma". Proxy
     * mówi więcej: "łączy" po prośbie to znak, że okulary odpowiedziały, a
     * system jeszcze negocjuje; trwałe "rozłączony" przy zadeklarowanym
     * profilu to wyłączony przełącznik multimediów.
     */
    @SuppressLint("MissingPermission")
    private suspend fun a2dpState(
        context: Context,
        adapter: BluetoothAdapter,
        device: BluetoothDevice
    ): A2dpLinkState = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val state = runCatching { proxy.getConnectionState(device) }.getOrNull()
                    // Proxy zamykamy zawsze, także gdy wynik już nikogo nie
                    // obchodzi (przekroczony limit) - inaczej wisi do końca procesu.
                    runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
                    if (cont.isActive) cont.resume(map(state))
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }
            val asked = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
                .getOrDefault(false)
            if (!asked && cont.isActive) cont.resume(A2dpLinkState.UNKNOWN)
        }
    } ?: A2dpLinkState.UNKNOWN

    private fun map(state: Int?): A2dpLinkState = when (state) {
        BluetoothProfile.STATE_CONNECTED -> A2dpLinkState.CONNECTED
        BluetoothProfile.STATE_CONNECTING -> A2dpLinkState.CONNECTING
        BluetoothProfile.STATE_DISCONNECTING -> A2dpLinkState.DISCONNECTING
        BluetoothProfile.STATE_DISCONNECTED -> A2dpLinkState.DISCONNECTED
        else -> A2dpLinkState.UNKNOWN
    }

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
