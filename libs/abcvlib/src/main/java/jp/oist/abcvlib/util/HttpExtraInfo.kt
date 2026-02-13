package jp.oist.abcvlib.util

class HttpExtraInfo {
    class FileInfo(val fileName: String, val fileSize: Int, val fileType: String)

    class FlatbufferInfo(val flatbufferName: String, val flatbufferSize: Int)
}
