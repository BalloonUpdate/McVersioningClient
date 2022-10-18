package mcversioning

import mcversioning.data.*
import mcversioning.exception.BaseException
import mcversioning.exception.ConfigFileNotFoundException
import mcversioning.exception.FailedToParsingException
import mcversioning.exception.UpdateDirNotFoundException
import mcversioning.gui.NewWindow
import mcversioning.localization.LangNodes
import mcversioning.localization.Localization
import mcversioning.logging.ConsoleHandler
import mcversioning.logging.FileHandler
import mcversioning.logging.LogSys
import mcversioning.util.*
import com.github.kasuminova.GUI.SetupSwing
import org.json.JSONException
import org.yaml.snakeyaml.Yaml
import java.awt.Desktop
import java.io.File
import java.io.InterruptedIOException
import java.lang.instrument.Instrumentation
import java.nio.channels.ClosedByInterruptException
import java.util.jar.JarFile

class McVersioningMain
{
    /**
     * McVersioning主逻辑
     * @param graphicsMode 是否以图形模式启动（桌面环境通常以图形模式启动，安卓环境通常不以图形模式启动）
     * @param hasStandaloneProgress 程序是否拥有独立的进程。从JavaAgent参数启动没有独立进程，双击启动有独立进程（java -jar xx.jar也属于独立启动）
     * @param externalConfigFile 可选的外部配置文件路径，如果为空则使用 progDir/config.yml
     * @param enableLogFile 是否写入日志文件
     * @param disableTheme 是否禁用主题
     */
    fun run(
        graphicsMode: Boolean,
        hasStandaloneProgress: Boolean,
        externalConfigFile: File2?,
        enableLogFile: Boolean,
        disableTheme: Boolean,
    ): Boolean {
        try {
            val workDir = getWorkDirectory()
            val progDir = getProgramDirectory(workDir)
            val options = GlobalOptions.CreateFromMap(readConfig(externalConfigFile ?: (progDir + "config.yml")))
            val updateDir = getUpdateDirectory(workDir, options)

            // 初始化日志系统
            if (enableLogFile)
                LogSys.addHandler(FileHandler(LogSys, progDir + (if (graphicsMode) "mc-versioning.log" else "mc-versioning.txt")))
            LogSys.addHandler(ConsoleHandler(LogSys, if (EnvironmentUtils.isPackaged) (if (graphicsMode) LogSys.LogLevel.DEBUG else LogSys.LogLevel.INFO) else LogSys.LogLevel.INFO))
            if (!hasStandaloneProgress)
                LogSys.openRangedTag("McVersioning")

            LogSys.info("GraphicsMode:         $graphicsMode")
            LogSys.info("Standalone:           $hasStandaloneProgress")

            // 收集并打印环境信息
            val jvmVersion = System.getProperty("java.version")
            val jvmVender = System.getProperty("java.vendor")
            val osName = System.getProperty("os.name")
            val osArch = System.getProperty("os.arch")
            val osVersion = System.getProperty("os.version")

            LogSys.info("updating directory:   ${updateDir.path}")
            LogSys.info("working directory:    ${workDir.path}")
            LogSys.info("executable directory: ${if(EnvironmentUtils.isPackaged) EnvironmentUtils.jarFile.parent.path else "dev-mode"}")
            LogSys.info("application version:  ${EnvironmentUtils.version} (${EnvironmentUtils.gitCommit})")
            LogSys.info("java virtual machine: $jvmVender $jvmVersion")
            LogSys.info("operating system: $osName $osVersion $osArch")

            Localization.init(readLangs())

            // 应用主题
            if (graphicsMode && !disableTheme && !options.disableTheme)
                SetupSwing.init()

            // 初始化UI
            val window = if (graphicsMode) NewWindow(options.windowWidth, options.windowHeight) else null

            // 将更新任务单独分进一个线程执行，方便随时打断线程
            var ex: Throwable? = null
            val workthread = WorkThread(window, options, workDir, updateDir, progDir)
            workthread.isDaemon = true
            workthread.setUncaughtExceptionHandler { _, e -> ex = e }

            if (!options.quietMode)
                window?.show()

            window?.titleTextSuffix = Localization[LangNodes.window_title_suffix, "APP_VERSION", EnvironmentUtils.version]
            window?.titleText = Localization[LangNodes.window_title]
            window?.statusBarText = Localization[LangNodes.connecting_message]
            window?.onWindowClosing?.once { win ->
                win.hide()
                if (workthread.isAlive)
                    workthread.interrupt()
            }

            workthread.start()
            workthread.join()

            window?.destroy()

            // 处理工作线程里的异常
            if (ex != null)
            {
                if (//            ex !is SecurityException &&
                    ex !is InterruptedException &&
                    ex !is InterruptedIOException &&
                    ex !is ClosedByInterruptException)
                {
                    try {
                        LogSys.error(ex!!.javaClass.name)
                        LogSys.error(ex!!.stackTraceToString())
                    } catch (e: Exception) {
                        println("------------------------")
                        println(e.javaClass.name)
                        println(e.stackTraceToString())
                    }

                    if (graphicsMode)
                    {
                        val className = if (ex!! !is BaseException) ex!!.javaClass.name + "\n" else ""
                        val errMessage = MiscUtils.stringBreak(className + (ex!!.message ?: "<No Exception Message>"), 80)
                        val title = "Error occurred ${EnvironmentUtils.version}"
                        var content = errMessage + "\n"
                        content += if (!hasStandaloneProgress) "点击\"是\"显示错误详情并崩溃Minecraft，" else "点击\"是\"显示错误详情并退出，"
                        content += if (!hasStandaloneProgress) "点击\"否\"继续启动Minecraft" else "点击\"否\"直接退出程序"
                        val choice = DialogUtils.confirm(title, content)
                        if (!hasStandaloneProgress)
                        {
                            if (choice)
                            {
                                DialogUtils.error("Callstack", ex!!.stackTraceToString())
                                throw ex!!
                            }
                        } else {
                            if (choice)
                                DialogUtils.error("Callstack", ex!!.stackTraceToString())
                            throw ex!!
                        }
                    } else {
                        if (options.noThrowing)
                            println("文件更新失败！但因为设置了no-throwing参数，游戏仍会继续运行！\n\n\n")
                        else
                            throw ex!!
                    }
                } else {
                    LogSys.info("updating thread interrupted by user")
                }
            } else {
                return workthread._diff.run { oldFiles.size + oldFolders.size + newFiles.size + newFolders.size } > 0
            }
        } catch (e: UpdateDirNotFoundException) {
            if (graphicsMode)
                DialogUtils.error("", e.message ?: "<No Exception Message>")
        } catch (e: ConfigFileNotFoundException) {
            if (graphicsMode)
                DialogUtils.error("", e.message ?: "<No Exception Message>")
        } catch (e: FailedToParsingException) {
            if (graphicsMode)
                DialogUtils.error("", e.message ?: "<No Exception Message>")
        }

        return false
    }

    /**
     * 向上搜索，直到有一个父目录包含.minecraft目录，然后返回这个父目录。最大搜索7层目录
     * @param basedir 从哪个目录开始向上搜索
     * @return 包含.minecraft目录的父目录。如果找不到则返回Null
     */
    fun searchDotMinecraft(basedir: File2): File2?
    {
        try {
            if(basedir.contains(".minecraft"))
                return basedir
            if(basedir.parent.contains(".minecraft"))
                return basedir.parent
            if(basedir.parent.parent.contains(".minecraft"))
                return basedir.parent.parent
            if(basedir.parent.parent.parent.contains(".minecraft"))
                return basedir.parent.parent.parent
            if(basedir.parent.parent.parent.parent.contains(".minecraft"))
                return basedir.parent.parent.parent.parent
            if(basedir.parent.parent.parent.parent.parent.contains(".minecraft"))
                return basedir.parent.parent.parent.parent.parent
            if(basedir.parent.parent.parent.parent.parent.parent.contains(".minecraft"))
                return basedir.parent.parent.parent.parent.parent.parent
        } catch (e: NullPointerException) {
            return null
        }
        return null
    }

    /**
     * 从外部/内部读取配置文件并将内容返回（当外部不可用时会从内部读取）
     * @param externalConfigFile 外部配置文件
     * @return 解码后的配置文件对象
     * @throws ConfigFileNotFoundException 配置文件找不到时
     * @throws FailedToParsingException 配置文件无法解码时
     */
    fun readConfig(externalConfigFile: File2): Map<String, Any>
    {
        try {
            val content: String
            if(!externalConfigFile.exists)
            {
                if(!EnvironmentUtils.isPackaged)
                    throw ConfigFileNotFoundException("config.yml")
                JarFile(EnvironmentUtils.jarFile.path).use { jar ->
                    val configFileInZip = jar.getJarEntry("config.yml") ?: throw ConfigFileNotFoundException("config.yml")
                    jar.getInputStream(configFileInZip).use { content = it.readBytes().decodeToString() }
                }
            } else {
                content = externalConfigFile.content
            }
            return Yaml().load(content)
        } catch (e: JSONException) {
            throw FailedToParsingException("配置文件config.yml", "yaml", e.message ?: "")
        }
    }

    /**
     * 从Jar文件内读取语言配置文件（仅图形模式启动时有效）
     * @return 语言配置文件对象
     * @throws ConfigFileNotFoundException 配置文件找不到时
     * @throws FailedToParsingException 配置文件无法解码时
     */
    fun readLangs(): Map<String, String>
    {
        try {
            val content: String
            if (EnvironmentUtils.isPackaged)
                JarFile(EnvironmentUtils.jarFile.path).use { jar ->
                    val langFileInZip = jar.getJarEntry("lang.yml") ?: throw ConfigFileNotFoundException("lang.yml")
                    jar.getInputStream(langFileInZip).use { content = it.readBytes().decodeToString() }
                }
            else
                content = (File2(System.getProperty("user.dir")) + "src/main/resources/lang.yml").content

            return Yaml().load(content)
        } catch (e: JSONException) {
            throw FailedToParsingException("语言配置文件lang.yml", "yaml", e.message ?: "")
        }
    }

    /**
     * 获取进程的工作目录
     */
    fun getWorkDirectory(): File2
    {
        return System.getProperty("user.dir").run {
            if(EnvironmentUtils.isPackaged)
                File2(this)
            else
                File2("$this${File.separator}debug-directory").also { it.mkdirs() }
        }
    }

    /**
     * 获取需要更新的起始目录
     * @throws UpdateDirNotFoundException 当.minecraft目录搜索不到时
     */
    fun getUpdateDirectory(workDir: File2, options: GlobalOptions): File2
    {
        return if(EnvironmentUtils.isPackaged) {
            if (options.basePath != "") EnvironmentUtils.jarFile.parent + options.basePath
            else searchDotMinecraft(workDir) ?: throw UpdateDirNotFoundException()
        } else {
            workDir // 调试状态下永远使用project/workdir作为更新目录
        }.apply { mkdirs() }
    }

    /**
     * 获取Jar文件所在的目录
     */
    fun getProgramDirectory(workDir: File2): File2
    {
        return if(EnvironmentUtils.isPackaged) EnvironmentUtils.jarFile.parent else workDir
    }

    companion object {
        /**
         * 从JavaAgent启动
         */
        @JvmStatic
        fun premain(agentArgs: String?, ins: Instrumentation?)
        {
            val useGraphicsMode = agentArgs != "windowless" && Desktop.isDesktopSupported()
            McVersioningMain().run(
                graphicsMode = useGraphicsMode,
                hasStandaloneProgress = false,
                externalConfigFile = null,
                enableLogFile = true,
                disableTheme = false
            )
            LogSys.info("finished!")
        }

        /**
         * 独立启动
         */
        @JvmStatic
        fun main(args: Array<String>)
        {
            val useGraphicsMode = !(args.isNotEmpty() && args[0] == "windowless") && Desktop.isDesktopSupported()
            McVersioningMain().run(
                graphicsMode = useGraphicsMode,
                hasStandaloneProgress = true,
                externalConfigFile = null,
                enableLogFile = true,
                disableTheme = false
            )
            LogSys.info("finished!")
        }

        /**
         * 从ModLoader启动
         * @return 是否有文件更新，如果有返回true。其它情况返回false
         */
        @JvmStatic
        fun modloader(enableLogFile: Boolean, disableTheme: Boolean): Boolean
        {
            val result = McVersioningMain().run(
                graphicsMode = Desktop.isDesktopSupported(),
                hasStandaloneProgress = false,
                externalConfigFile = null,
                enableLogFile = enableLogFile,
                disableTheme = disableTheme
            )
            LogSys.info("finished!")
            return result
        }
    }
}