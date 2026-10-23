package com.phnem.vap.network

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.concurrent.TimeUnit

/** Vetro's per-interface SSDP discovery and SOAP transport, adapted to media servers. */
object DlnaBrowser {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
    suspend fun describe(url: String): UpnpProtocol.Server = withContext(Dispatchers.IO) {
        UpnpProtocol.describe(request(Request.Builder().url(NetworkUrls.parse(url)).build()), url) ?: error("No ContentDirectory")
    }
    suspend fun discover(context: Context): List<UpnpProtocol.Server> = withContext(Dispatchers.IO) {
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("vap-dlna-search")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val names = connectivity.allNetworks.filter { network -> connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true }.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.distinct()
            val interfaces = names.mapNotNull(NetworkInterface::getByName).asSequence().filter { it.isUp && !it.isLoopback }
                .flatMap { network -> network.inetAddresses.asSequence().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { network to it } }.take(4).toList()
            val locations = coroutineScope { interfaces.map { (network, address) -> async { searchOn(network, address) } }.awaitAll().flatten().distinct().take(16) }
            val semaphore = Semaphore(4)
            coroutineScope { locations.map { location -> async { semaphore.withPermit {
                try { describe(location) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } } }.awaitAll().filterNotNull().distinctBy { it.location }.sortedBy { it.name.lowercase() } }
        } finally { if (lock?.isHeld == true) lock.release() }
    }
    private suspend fun searchOn(network: NetworkInterface, address: Inet4Address): List<String> {
        val socket = try { MulticastSocket(InetSocketAddress(address, 0)).apply { networkInterface = network; timeToLive = 2; soTimeout = 500 } }
            catch (_: Exception) { return emptyList() }
        return socket.use {
            for (target in listOf(UpnpProtocol.MEDIA_SERVER, UpnpProtocol.CONTENT_DIRECTORY)) {
                val bytes = UpnpProtocol.search(target).toByteArray(Charsets.UTF_8)
                try { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900)) } catch (_: Exception) { return@use emptyList() }
            }
            val until = SystemClock.elapsedRealtime() + 2700
            val found = linkedSetOf<String>(); val buffer = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < until && found.size < 32) {
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                UpnpProtocol.location(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))?.let(found::add)
            }
            found.toList()
        }
    }
    suspend fun list(connection: NetworkConnection, folder: String): List<NetworkEntry> = withContext(Dispatchers.IO) {
        require(connection.kind == NetworkKind.DLNA)
        val server = describe(connection.url)
        val id = UpnpProtocol.objectId(connection.id, folder)
        val result = mutableListOf<NetworkEntry>(); var offset = 0
        while (offset < 5000) {
            currentCoroutineContext().ensureActive()
            val args = listOf("ObjectID" to id, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*", "StartingIndex" to offset.toString(), "RequestedCount" to "200", "SortCriteria" to "")
            val body = UpnpProtocol.envelope(server.serviceType, "Browse", args).toRequestBody("text/xml; charset=utf-8".toMediaType())
            val values = UpnpProtocol.values(request(Request.Builder().url(server.control).header("SOAPACTION", "\"${server.serviceType}#Browse\"").post(body).build()))
            check(values["errorCode"] == null) { "UPnP Browse rejected" }
            result += UpnpProtocol.entries(values["Result"].orEmpty(), connection.id)
            val count = values["NumberReturned"]?.toIntOrNull()?.takeIf { it in 0..5000 } ?: result.size - offset
            val total = values["TotalMatches"]?.toIntOrNull()?.coerceIn(0, 1000000)
            if (count <= 0 || (total != null && offset + count >= total) || (total == null && count < 200)) break
            offset += count
        }
        result.distinctBy { it.url }.take(5000).sortedWith(compareByDescending<NetworkEntry> { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    private fun request(request: Request): String {
        // This client has no auth interceptor, cookie jar or default credentials.
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "UPnP HTTP ${response.code}" }
            val out = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input -> val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 2 * 1024 * 1024); out.write(buffer, 0, n) }
            } ?: error("Empty UPnP response")
            return out.toString("UTF-8")
        }
    }
}
