package app.iptvplayer.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Is there a network with internet (UI-016)? Emits the current answer, then every change. Needs the
 * ACCESS_NETWORK_STATE permission in the app's manifest. "Connected" is what the system says; a captive portal
 * still counts as online (the stream will fail with its own error).
 */
fun onlineFlow(ctx: Context): Flow<Boolean> = callbackFlow {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val up = HashSet<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(up) { up += network; trySend(true) }
        }

        override fun onLost(network: Network) {
            synchronized(up) { up -= network; trySend(up.isNotEmpty()) }
        }
    }
    @Suppress("DEPRECATION") // activeNetworkInfo: the one call that works the same on API 21–35
    trySend(cm.activeNetworkInfo?.isConnected == true)
    val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    cm.registerNetworkCallback(request, callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
