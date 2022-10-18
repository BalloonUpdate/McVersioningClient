package mcversioning

import mcversioning.data.GlobalOptions
import mcversioning.exception.*
import mcversioning.logging.LogSys
import mcversioning.util.File2
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class HttpClient(val options: GlobalOptions)
{
    val okClient = OkHttpClient.Builder()
        .connectTimeout(options.httpConnectTimeout.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(options.httpReadTimeout.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(options.httpWriteTimeout.toLong(), TimeUnit.MILLISECONDS)
        .build()

    /**
     * 多可用源版本的fetchJson
     */
    fun fetchJsonMutiple(
        urls: List<String>,
        description: String,
        parseAsJsonObject: Boolean
    ): Pair<JSONObject?, JSONArray?> {
        var ex: Exception? = null

        for (url in urls)
        {
            ex = try {
                return fetchJson(url, description, parseAsJsonObject)
            } catch (e: ConnectionRejectedException) { e }
            catch (e: ConnectionInterruptedException) { e }
            catch (e: ConnectionTimeoutException) { e }

            if (urls.size > 1)
                LogSys.error(ex!!.toString())
        }

        throw ex!!
    }

    /**
     * 多可用源版本的fetchText
     */
    fun fetchTextMutiple(urls: List<String>): String
    {
        var ex: Exception? = null

        for (url in urls)
        {
            ex = try {
                return fetchText(url)
            } catch (e: ConnectionRejectedException) { e }
            catch (e: ConnectionInterruptedException) { e }
            catch (e: ConnectionTimeoutException) { e }

            if (urls.size > 1)
                LogSys.error(ex!!.toString())
        }

        throw ex!!
    }

    /**
     * 多可用源版本的downloadFile
     * @param onSourceFallback 当切换到另一个源时
     */
    fun downloadFileMutiple(
        urls: List<String>,
        writeTo: File2,
        lengthExpected: Long,
        onProgress: (packageLength: Long, bytesReceived: Long, totalReceived: Long) -> Unit,
        onSourceFallback: () -> Unit,
    ) {
        var ex: Exception? = null

        for (url in urls)
        {
            ex = try {
                return downloadFile(url, writeTo, lengthExpected, onProgress)
            } catch (e: ConnectionRejectedException) { e }
            catch (e: ConnectionInterruptedException) { e }
            catch (e: ConnectionTimeoutException) { e }

            onSourceFallback()

            if (urls.size > 1)
                LogSys.error(ex!!.toString())
        }

        throw ex!!
    }

    /**
     * 从HTTP服务器上获取Json文件
     * @param url 要获取的URL
     * @param description 这个文件的描述
     * @param parseAsJsonObject 是否解析成JsonObject对象，或者是JsonArray对象
     * @return 解析好的JsonObject对象，或者是JsonArray对象
     */
    fun fetchJson(url: String, description: String, parseAsJsonObject: Boolean): Pair<JSONObject?, JSONArray?> {
        val body = fetchText(url)

        try {
            return if (parseAsJsonObject)
                Pair(JSONObject(body), null)
            else
                Pair(null, JSONArray(body))
        } catch (e: JSONException) {
            throw FailedToParsingException(description, "json", "$url ${e.message}")
        }
    }

    /**
     * 从HTTP服务器上获取文本文件
     * @param url 要获取的URL
     * @return 服务器返回文本内容
     */
    fun fetchText(url: String): String
    {
        val req = Request.Builder().url(url).build()
        LogSys.debug("http request on $url")

        var ex: Throwable? = null
        var retries = options.retryTimes
        while (--retries >= 0)
        {
            try {
                okClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        val body = r.body?.string()?.run { if (length > 300) substring(0, 300) + "\n..." else this }
                        throw HttpResponseStatusCodeException(r.code, url, body)
                    }

                    return r.body!!.string()
                }
            } catch (e: ConnectException) {
                ex = ConnectionRejectedException(url, e.message ?: "")
            } catch (e: SocketException) {
                ex = ConnectionInterruptedException(url, e.message ?: "")
            } catch (e: SocketTimeoutException) {
                ex = ConnectionTimeoutException(url, e.message ?: "")
            } catch (e: Throwable) {
                ex = e
            }

            LogSys.warn("")
            LogSys.warn(ex.toString())
            LogSys.warn("retrying $retries ...")

            Thread.sleep(1000)
        }

        throw ex!!
    }

    /**
     * 从HTTP服务器上下载二进制大文件
     * @param url 对应的URL
     * @param writeTo 写到哪个文件里
     * @param lengthExpected 文件的预期大小
     * @param onProgress 下载进度报告回调
     */
    fun downloadFile(
        url: String,
        writeTo: File2,
        lengthExpected: Long,
        onProgress: (packageLength: Long, bytesReceived: Long, totalReceived: Long) -> Unit
    ) {
        val link = url.replace("+", "%2B")

        writeTo.makeParentDirs()
        val req = Request.Builder().url(link).build()

        var ex: Throwable? = null
        var retries = options.retryTimes
        while (--retries >= 0)
        {
            try {
                okClient.newCall(req).execute().use { r ->
                    if(!r.isSuccessful)
                        throw HttpResponseStatusCodeException(r.code, link, r.body?.string())

                    r.body!!.byteStream().use { input ->
                        FileOutputStream(writeTo.path).use { output ->
                            var bytesReceived: Long = 0
                            var len: Int
                            val buffer = ByteArray(chooseBufferSize(lengthExpected))

                            while (input.read(buffer).also { len = it; bytesReceived += it } != -1)
                            {
                                output.write(buffer, 0, len)
                                onProgress(len.toLong(), bytesReceived, lengthExpected)
                            }
                        }
                    }

                    return
                }
            } catch (e: ConnectException) {
                ex = ConnectionInterruptedException(link, e.message ?: "")
            } catch (e: SocketException) {
                ex = ConnectionRejectedException(link, e.message ?: "")
            } catch (e: SocketTimeoutException) {
                ex = ConnectionTimeoutException(link, e.message ?: "")
            } catch (e: Throwable) {
                ex = e
            }

            LogSys.warn("")
            LogSys.warn(ex.toString())
            LogSys.warn("retrying $retries ...")

            Thread.sleep(1000)
        }

        throw ex!!
    }


    /**
     * 根据文件大小选择合适的缓冲区大小
     * @param size 文件大小
     * @return 缓冲区大小
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun chooseBufferSize(size: Long): Int {
        val kb = 1024
        val mb = 1024 * 1024
        val gb = 1024 * 1024 * 1024
        return when {
            size < 1 * mb   -> 16 * kb
            size < 2 * mb   -> 32 * kb
            size < 4 * mb   -> 64 * kb
            size < 8 * mb   -> 256 * kb
            size < 16 * mb  -> 512 * kb
            size < 32 * mb  -> 1 * mb
            size < 64 * mb  -> 2 * mb
            size < 128 * mb -> 4 * mb
            size < 256 * mb -> 8 * mb
            size < 512 * mb -> 16 * mb
            size < 1 * gb   -> 32 * mb
            else -> 64 * mb
        }
    }
}