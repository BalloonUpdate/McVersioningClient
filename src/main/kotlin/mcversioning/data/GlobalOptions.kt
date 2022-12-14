package mcversioning.data

import mcversioning.exception.ConfigFieldException

/**
 * 应用程序全局选项
 */
data class GlobalOptions(
    /**
     * 服务端index.json文件的URL，用来获取服务端的文件并计算差异
     */
    val server: List<String>,

    /**
     * 更新完成后是否自动关闭窗口并退出程序
     */
    val autoExit: Boolean,

    /**
     * 存储版本号的文件
     */
    val verionFile: String,

    /**
     * 更新的基本路径
     */
    val basePath: String,

    /**
     * 是否开启不抛异常模式，以避免在更新失败时，不打断Minecraft游戏的启动
     */
    val noThrowing: Boolean,

    /**
     * 安静模式，仅在有文件需要被更新时显示下载窗口
     */
    val quietMode: Boolean,

    /**
     * 全局http连接超时（单位毫秒）
     */
    val httpConnectTimeout: Int,

    /**
     * 全局http读取超时（单位毫秒）
     */
    val httpReadTimeout: Int,

    /**
     * 全局http写入超时（单位毫秒）
     */
    val httpWriteTimeout: Int,

    /**
     * 下载文件时使用的线程数，设置为0时会自动计算
     */
    val downloadThreads: Int,

    /**
     * 是否禁用主题
     */
    val disableTheme: Boolean,

    /**
     * 重试次数
     */
    val retryTimes: Int,

    /**
     * 窗口宽度
     */
    val windowWidth: Int,

    /**
     * 窗口高度
     */
    val windowHeight: Int,
) {
    companion object {
        /**
         * 从Map里创建一个GlobalOptions
         */
        @JvmStatic
        fun CreateFromMap(map: Map<String, Any>): GlobalOptions
        {
            val serverAsList = getOption<List<String>>(map, "server")
            val serverAsString = getOption<String>(map, "server")
            val server = serverAsList ?: listOf(serverAsString ?: throw ConfigFieldException("server"))

            return GlobalOptions(
                server = server,
                autoExit = getOption<Boolean>(map, "auto-exit") ?: false,
                verionFile = getOption<String>(map, "version-file") ?: "mv-version.txt",
                basePath = getOption<String>(map, "base-path") ?: "",
                noThrowing = getOption<Boolean>(map, "no-throwing") ?: false,
                quietMode = getOption<Boolean>(map, "quiet-mode") ?: false,
                httpConnectTimeout = getOption<Int>(map, "http-connect-timeout") ?: 3000,
                httpReadTimeout = getOption<Int>(map, "http-read-timeout") ?: 5000,
                httpWriteTimeout = getOption<Int>(map, "http-write-timeout") ?: 3000,
                downloadThreads = getOption<Int>(map, "download-threads") ?: 4,
                disableTheme = getOption<Boolean>(map, "disable-theme") ?: false,
                retryTimes = getOption<Int>(map, "retry-times") ?: 3,
                windowWidth = getOption<Int>(map, "window-width") ?: 450,
                windowHeight = getOption<Int>(map, "window-height") ?: 315,
            )
        }

        /**
         * 从配置文件里读取东西，并校验
         */
        inline fun <reified Type> getOption(config: Map<String, Any>, key: String): Type?
        {
            return if(key in config && config[key] != null && config[key] is Type) config[key] as Type else null
        }
    }
}