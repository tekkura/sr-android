package jp.oist.abcvlib.util

import android.content.Context
import jp.oist.abcvlib.core.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpConnection(private val context: Context, private val callback: HttpCallback) {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    interface HttpCallback {
        fun onFileReceived(filename: String, fileData: ByteArray)
        fun onSuccess(response: String)
        fun onError(error: String)
    }

    fun sendData(data: ByteArray, dataType: HttpDataType, extraInfo: Any?) {
        executorService.execute {
            val urlString = "http://${BuildConfig.IP}:${BuildConfig.PORT}"
            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.setDoOutput(true)
                urlConnection.requestMethod = "POST"
                urlConnection.setRequestProperty("Content-Type", "application/octet-stream")
                urlConnection.setRequestProperty("Content-Length", data.size.toString())
                urlConnection.setRequestProperty("Data-Type", dataType.type)

                // Add additional headers based on data type
                if (dataType == HttpDataType.FILE && extraInfo is HttpExtraInfo.FileInfo) {
                    urlConnection.setRequestProperty("File-Name", extraInfo.fileName)
                    urlConnection.setRequestProperty("File-Size", extraInfo.fileSize.toString())
                    urlConnection.setRequestProperty("File-Type", extraInfo.fileType)
                } else if (dataType == HttpDataType.FLATBUFFER && extraInfo is HttpExtraInfo.FlatbufferInfo) {
                    urlConnection.setRequestProperty("Flatbuffer-Name", extraInfo.flatbufferName)
                    urlConnection.setRequestProperty(
                        "Flatbuffer-Size",
                        extraInfo.flatbufferSize.toString()
                    )
                }

                urlConnection.getOutputStream().use { os ->
                    os.write(data, 0, data.size)
                }
                // Get the response
                val responseCode = urlConnection.getResponseCode()
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    urlConnection.getInputStream().use { inputStream ->
                        val response = inputStream.bufferedReader().readText()
                        callback.onSuccess(response)
                    }
                } else {
                    urlConnection.errorStream?.use { errorStream ->
                        val errorResponse = errorStream.bufferedReader().readText()
                        callback.onError("Error: $responseCode $errorResponse")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback.onError("Exception: ${e.message}")
            } finally {
                urlConnection?.disconnect()
            }
        }
    }

    fun getData(filename: String) {
        executorService.execute {
            val urlString = "http://${BuildConfig.IP}:${BuildConfig.PORT}/file?filename=$filename"
            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.requestMethod = "GET"
                val responseCode = urlConnection.getResponseCode()
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Get the file size from response headers
                    val contentDisposition = urlConnection.getHeaderField("Content-Disposition")
                    val isFile: Boolean = contentDisposition?.startsWith("attachment;") == true
                    if (isFile) {
                        // Handle large files by saving to disk
                        val file = File(context.getExternalFilesDir(null), filename)
                        urlConnection.getInputStream().use { inputStream ->
                            FileOutputStream(file).use { fos ->
                                inputStream.copyTo(fos)
                                callback.onSuccess("File saved to: " + file.absolutePath)
                            }
                        }
                    } else {
                        // Handle small files by reading into memory
                        urlConnection.getInputStream().use { inputStream ->
                            val fileData = inputStream.readBytes()
                            callback.onFileReceived(filename, fileData)
                        }
                    }
                } else {
                    urlConnection.errorStream?.use { errorStream ->
                        val errorResponse = errorStream.bufferedReader().readText()
                        callback.onError("Error: $responseCode $errorResponse")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback.onError("Exception: ${e.message}")
            } finally {
                urlConnection?.disconnect()
            }
        }
    }
}
