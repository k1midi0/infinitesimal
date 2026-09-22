package io.github.k1midi0.infinitesimal.image

/**
 * 端点认的四种图片格式。
 *
 * 一件事同时是两份信息：它是什么，以及配图那份数据 URL 上要写的 MIME。**判断只认开头那几个
 * 字节**——官方文档写着格式由文件实际内容判断，而不是文件名或声明的 MIME 类型；扩展名是猜的，
 * 内容是事实，而猜错的代价是一次 400。
 */
enum class ImageFormat(val mimeType: String, val extension: String) {
    Jpeg("image/jpeg", "jpg"),
    Png("image/png", "png"),
    Gif("image/gif", "gif"),
    Webp("image/webp", "webp"),
}

/**
 * 从开头几个字节认出格式，认不出就是 null。
 *
 * 四种格式各自的魔数：JPEG 是 `FF D8 FF`，PNG 是固定的八个字节，GIF 有两个版本头，WebP 是
 * `RIFF` 加四个长度字节再加 `WEBP`。够用就停在这里——这不是一个完整的格式探测库。
 */
fun sniffImageFormat(bytes: ByteArray): ImageFormat? = when {
    bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> ImageFormat.Jpeg
    bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ->
        ImageFormat.Png

    bytes.startsWith(byteArrayOf(0x47, 0x49, 0x46, 0x38)) -> ImageFormat.Gif
    bytes.startsWith(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
        bytes.startsWith(byteArrayOf(0x57, 0x45, 0x42, 0x50), offset = 8) -> ImageFormat.Webp

    else -> null
}

private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
    if (size < offset + prefix.size) return false
    for (index in prefix.indices) {
        if (this[offset + index] != prefix[index]) return false
    }
    return true
}

/**
 * 附图在数据目录里的落点。
 *
 * **消息里只留文件名，字节留在这里。** 字节不进消息有两个理由，两个都是硬的：一是 token 估算
 * 按字符数走，一份几 MB 的 base64 会被算成几百万 token；二是消息会整轮落进存储，图不该睡在
 * 那里。真正拼出数据 URL 是在发请求那一刻（见 llm 层）。
 *
 * 这个对象只保留 llm 那一层要用到的部分：**按文件名取回字节**。收图、落盘、上限那些属于界面的
 * 部分还没有搬过来，它们要的东西（工作目录、平台文件读写）这一版还没有。
 *
 * 目录本身由入口在启动时设一次——与「数据目录在哪」这件事属于入口而不是属于这里一致。
 */
object AttachedImages {

    /** 存图的那个目录，在数据目录下面。 */
    const val DIRECTORY_NAME = "images"

    private var dataDirectory: String? = null

    /**
     * 数据目录。入口启动时设一次。
     *
     * 不设就用 [directory] 时会**报错**而不是回落到某个猜出来的路径：回落到哪儿都能跑，而跑
     * 起来之后图会从另一个地方读，那看起来像图丢了。
     */
    fun initialize(dataDirectory: String) {
        this.dataDirectory = dataDirectory.trimEnd('/', '\\')
    }

    fun directory(): String = dataDirectory
        ?.let { "$it/$DIRECTORY_NAME" }
        ?: error("附图目录还没有设定：入口启动时先调 AttachedImages.initialize(数据目录)")

    fun pathOf(fileName: String): String = "${directory()}/$fileName"

    /** 按文件名取回字节；已经不在了就是 null。发请求那一刻走这里。 */
    fun bytesOf(fileName: String): ByteArray? = readBytesOrNull(pathOf(fileName))
}

/**
 * 一个文件的全部字节。读不出来（不存在、是目录、没有权限）就是 null。
 *
 * 与读文本的函数分开，是因为**图片不是文本**：按 UTF-8 解一遍再编码回去，字节已经不是原来那些
 * 了，而配图要送出去的 base64 必须逐字节就是磁盘上那一份。
 */
internal expect fun readBytesOrNull(path: String): ByteArray?
