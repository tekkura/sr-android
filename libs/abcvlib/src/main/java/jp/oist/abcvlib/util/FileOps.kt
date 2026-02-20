package jp.oist.abcvlib.util

import android.content.Context
import android.os.Debug
import android.os.Environment
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Not used anywhere else in the library at this time. Leaving for possible future use.
 */
class FileOps {

    companion object {
        private const val TAG = "FileOps"

        fun savedata(content: String, filename: String) {
            try {
                if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    val sdCardDir = Environment.getExternalStorageDirectory()
                    if (sdCardDir.exists() && sdCardDir.canWrite()) {
                        val file = File(sdCardDir.absolutePath + "/DataDir" + filename)
                        val madeDir = file.mkdir()
                        if (madeDir) {
                            // String filepath = file.getAbsolutePath() + filename;
                            BufferedWriter(
                                OutputStreamWriter(FileOutputStream(file.absolutePath, false), UTF_8)
                            ).use { bw ->
                                bw.write(content)
                            }
                        } else {
                            Logger.d(TAG, "Unable to create DataDir directory")
                        }
                    }
                }
            } catch (e: IOException) {
                ErrorHandler.eLog(TAG, "Error when saving data to file", e, true)
            }
        }

        @JvmStatic
        fun savedata(context: Context, content: ByteArray, pathName: String, filename: String) {
            try {
                var deleted = false
                var created = false
                val path = File(context.filesDir, pathName)
                val file = File(path, filename)
                if (file.exists()) {
                    deleted = file.delete()
                }
                if (!file.exists() || deleted) {
                    path.mkdirs()
                    created = file.createNewFile()
                    Logger.v(TAG, "Writing " + file.absolutePath)
                    FileOutputStream(file.absolutePath, false).use { fileOutputStream ->
                        fileOutputStream.write(content)
                    }
                }
            } catch (e: Exception) {
                ErrorHandler.eLog(TAG, "Error when saving data to file", e, true)
            }
        }

        fun savedata(context: Context, content: InputStream, pathName: String, filename: String) {
            try {
                var deleted = false
                var created = false
                val path = File(context.filesDir, pathName)
                val file = File(path, filename)
                if (file.exists()) {
                    deleted = file.delete()
                }
                if (!file.exists() || deleted) {
                    path.mkdirs()
                    created = file.createNewFile()
                    Logger.v(TAG, "Writing " + file.absolutePath)
                    FileOutputStream(file.absolutePath, false).use { fileOutputStream ->
                        content.copyTo(fileOutputStream)
                    }
                }
            } catch (e: Exception) {
                ErrorHandler.eLog(TAG, "Error when saving data to file", e, true)
            }
        }

        fun copyAssets(context: Context, path: String) {
            val assetManager = context.assets
            var files: Array<String>? = null
            try {
                files = assetManager.list(path)
            } catch (e: IOException) {
                ErrorHandler.eLog(TAG, "Failed to get asset file list", e, true)
            }
            if (files != null) for (filename in files) {
                try {
                    assetManager.open(path + filename).use { inputStream ->
                        savedata(context, inputStream, path, filename)
                    }
                } catch (e: IOException) {
                    ErrorHandler.eLog(TAG, "Failed to copy asset file = $filename", e, true)
                }
                // NOOP
            }
        }

        /**
         * This is helpful code when you have an OutOfMemoryError. Keeping as comment for easy
         * access until we're sure we won't have these any longer.
         */
        fun heapDump(context: Context) {
            try {
                var deleted = false
                var created = false
                Logger.d(TAG, "Within HeapDump")
                val file = File(context.filesDir, "dump.hprof")
                if (file.exists()) {
                    deleted = file.delete()
                }
                if (!file.exists() || deleted) {
                    created = file.createNewFile()
                }
                Debug.dumpHprofData(file.absolutePath)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun readData(fileName: String): DoubleArray {
        val output = DoubleArray(4)
        val file = getFile(fileName)
        val filePath = file.path

        if (isExternalStorageReadable()) {
            try {
                BufferedReader(FileReader(filePath)).use { bufferedReader ->
                    val line = bufferedReader.readLine()
                    val lineArray = line.split(",")
                    for (i in lineArray.indices) {
                        output[i] = lineArray[i].toDouble()
                    }
                }
            } catch (e: IOException) {
                ErrorHandler.eLog(TAG, "Error when reading data from file", e, true)
            } catch (e: NullPointerException) {
                ErrorHandler.eLog(TAG, "Error when reading data from file", e, true)
            }
        }

        return output
    }

    fun writeToFile(context: Context, fileName: String, data: DoubleArray) {
        val file = getFile(fileName)
        val androidDataString = data.joinToString(",") { it.toString() } + ","

        if (isExternalStorageWritable()) {
            try {
                FileOutputStream(file).use { stream ->
                    stream.write(androidDataString.toByteArray())
                }
            } catch (e: IOException) {
                ErrorHandler.eLog(TAG, "Error writing file", e, true)
            }
        }
    }


    /* Checks if external storage is available for read and write */
    fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    /* Checks if external storage is available to at least read */
    fun isExternalStorageReadable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state ||
                Environment.MEDIA_MOUNTED_READ_ONLY == state
    }

    fun getFile(fileName: String): File {
        // Get the directory for the user's public pictures' directory.
        val file = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ), fileName
        )
        try {
            file.createNewFile()
        } catch (e: IOException) {
            ErrorHandler.eLog(TAG, "Error when getting file", e, true)
        }
        return file
    }
}
