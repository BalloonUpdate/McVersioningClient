package mcversioning.data

import mcversioning.util.File2

/**
 * 代表一个文件下载任务
 */
data class DownloadTask(
    /**
     * 预期的文件长度
     */
    val lengthExpected: Long,

    /**
     * 文件的下载URL们，多个URL之间为备用关系
     */
    val urls: List<String>,

    /**
     * 需要写出到的本地文件
     */
    val file: File2,
)