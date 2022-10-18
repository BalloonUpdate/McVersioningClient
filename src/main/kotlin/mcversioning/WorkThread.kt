package mcversioning

import mcversioning.data.DownloadTask
import mcversioning.data.GlobalOptions
import mcversioning.gui.NewWindow
import mcversioning.localization.LangNodes
import mcversioning.localization.Localization
import mcversioning.logging.LogSys
import mcversioning.util.*
import java.io.InterruptedIOException
import javax.swing.JOptionPane

class WorkThread(
    val window: NewWindow?,
    val options: GlobalOptions,
    val workDir: File2,
    val updateDir: File2,
    val progDir: File2,
): Thread() {
    /**
     * 更新结果
     */
    var _diff = VersionRecord()

    /**
     * 更新助手工作线程
     */
    override fun run()
    {
        if (!options.quietMode)
            window?.show()

        val httpClient = HttpClient(options)
        val currentVersionFile = progDir + options.verionFile

        val currentVersion = if (currentVersionFile.exists) currentVersionFile.content else "none"
        val newestVersion = httpClient.fetchTextMutiple(options.server)

        LogSys.info("current version: $currentVersion, newest Version: $newestVersion")

        // 如果当前版本和最新版本不一致
        if (currentVersion != newestVersion)
        {
            // 更新UI
            window?.statusBarText = Localization[LangNodes.fetch_metadata]

            // 收集落后的版本
            val allVersions = httpClient.fetchTextMutiple(getMultipleUrls("/all-versions.txt"))
                .split("\n")
                .filter { it.isNotEmpty() }

            val position = allVersions.indexOf(currentVersion)

            // 是否是合法的版本号
            if (currentVersion in allVersions || currentVersion == "none")
            {
                val missingVersions = allVersions.drop(if (position == -1) 0 else position + 1)

                LogSys.info("missing versions: $missingVersions, all versions: $allVersions")

                // 获取落后的版本的元数据
                val versionRecords = missingVersions
                    .map { version -> httpClient.fetchJsonMutiple(getMultipleUrls("/v-$version.json"), "版本记录文件 $version", true).first!! }
                    .map { VersionRecord(it) }

                if (versionRecords.isNotEmpty())
                {
                    // 计算出所有文件变化
                    var versionRecord: VersionRecord? = null
                    for (record in versionRecords)
                    {
                        if (versionRecord == null)
                            versionRecord = record
                        else
                            versionRecord.apply(record)
                    }

                    val diff = versionRecord!!

                    LogSys.info("文件差异计算完成，旧文件: ${diff.oldFiles.size}, 旧目录: ${diff.oldFolders.size}, 新文件: ${diff.newFiles.size}, 新目录: ${diff.newFolders.size}")
                    diff.oldFiles.forEach { LogSys.debug("旧文件: $it") }
                    diff.oldFolders.forEach { LogSys.debug("旧目录: $it") }
                    diff.newFiles.forEach { LogSys.debug("新文件: $it") }
                    diff.newFolders.forEach { LogSys.debug("新目录: $it") }

                    // 删除旧文件和旧目录，还有创建新目录
                    diff.oldFiles.map { (updateDir + it) }.forEach { if (!EnvironmentUtils.isPackaged || it.path != EnvironmentUtils.jarFile.path) it.delete() }
                    diff.oldFolders.map { (updateDir + it) }.forEach { it.delete() }
                    diff.newFolders.map { (updateDir + it) }.forEach { it.mkdirs() }

                    // 延迟打开窗口
                    if (window != null && options.quietMode && diff.newFiles.isNotEmpty())
                        window.show()

                    // 下载文件
                    if (diff.newFiles.isNotEmpty())
                    {
                        LogSys.info("开始下载文件...")
                        downloadFileMultipleThread(diff, httpClient)
                    }

                    // 显示结算窗口
                    _diff = diff

                    // 更新版本号
                    currentVersionFile.content = newestVersion
                }
            } else {
                LogSys.warn("当前客户端版本号不在服务端的版本号列表里，可能是一个无效的版本号。因此无法确定版本前后关系，更新失败！")
            }
        }

        // 显示更新小结
        if(window != null)
        {
            if(!(options.quietMode && _diff.newFiles.isEmpty()) && !options.autoExit)
            {
                val news = _diff.newFiles
                val hasUpdate = news.isNotEmpty()
                val title = if(hasUpdate) Localization[LangNodes.finish_message_title_has_update] else Localization[LangNodes.finish_message_title_no_update]
                val content = if(hasUpdate) Localization[LangNodes.finish_message_content_has_update, "COUNT", "${news.size}"] else Localization[LangNodes.finish_message_content_no_update]
                JOptionPane.showMessageDialog(null, content, title, JOptionPane.INFORMATION_MESSAGE)
            }
        } else {
            val totalUpdated = _diff.newFiles.size + _diff.oldFiles.size
            if (totalUpdated == 0)
                LogSys.info("所有文件已是最新！")
            else
                LogSys.info("成功更新${totalUpdated}个文件!")
            LogSys.info("程序结束，继续启动Minecraft！\n\n\n")
        }
    }

    /**
     * 多线程下载文件
     */
    fun downloadFileMultipleThread(diff: VersionRecord, httpClient: HttpClient)
    {
        // 下载新文件
        var totalBytesDownloaded: Long = 0
        val totalBytes: Long = diff.newFiles.sumOf { diff.newFilesLengthes[it]!! }
        window?.statusBarText = "总进度"

        // 生成下载任务
        val tasks = diff.newFiles
            .map { path -> DownloadTask(diff.newFilesLengthes[path]!!, getMultipleUrls("/snapshot/$path"),  updateDir + path) }
            .toMutableList()

        val lock = Any()
        var committedCount = 0
        var downloadedCount = 0
        val samplers = mutableListOf<SpeedSampler>()

        // 单个线程的下载逻辑
        fun download(task: DownloadTask, taskRow: NewWindow.TaskRow?)
        {
            val file = task.file
            val urls = task.urls
            val lengthExpected = task.lengthExpected

            val sampler = SpeedSampler(3000)
            synchronized(lock) {
                samplers += sampler

                committedCount += 1
                LogSys.debug("request($committedCount/${diff.newFiles.size}): ${urls.joinToString()}, write to: ${file.path}")
            }

            var localDownloadedBytes: Long = 0

            var time = System.currentTimeMillis()

            httpClient.downloadFileMutiple(urls, file, lengthExpected, { packageLength, received, total ->
                if (taskRow == null)
                    return@downloadFileMutiple

                totalBytesDownloaded += packageLength
                localDownloadedBytes += packageLength
                val currentProgress = received / total.toFloat() * 100
                val totalProgress = totalBytesDownloaded / totalBytes.toFloat() * 100

                sampler.feed(packageLength)
                val speed = sampler.speed()

                // 每隔200ms更新一次ui
                if (System.currentTimeMillis() - time < 400)
                    return@downloadFileMutiple
                time = System.currentTimeMillis()

//                    val currProgressInString = String.format("%.1f", currentProgress)
                val totalProgressInString = String.format("%.1f", totalProgress)

                taskRow.borderText = file.name
                taskRow.progressBarValue = (currentProgress * 10).toInt()
//                    taskRow.labelText = ""
                taskRow.progressBarLabel = "${MiscUtils.convertBytes(received)} / ${MiscUtils.convertBytes(total)}   -   " +MiscUtils.convertBytes(speed) + "/s"

                val toatalSpeed: Long
                synchronized(lock) { toatalSpeed = samplers.sumOf { it.speed() } }

                window!!.statusBarProgressValue = (totalProgress * 10).toInt()
                window.statusBarProgressText = "$totalProgressInString%  -  ${downloadedCount}/${diff.newFiles.size}   -   " + MiscUtils.convertBytes(toatalSpeed) + "/s"
                window.titleText = Localization[LangNodes.window_title_downloading, "PERCENT", totalProgressInString]
            }, {
                totalBytesDownloaded -= localDownloadedBytes
            })

            synchronized(lock) {
                if (window == null)
                    LogSys.info("downloaded($downloadedCount/${diff.newFiles.size}): ${file.name}")

                downloadedCount += 1
                samplers -= sampler
            }
        }

        // 启动工作线程
        val lock2 = Any()
        val threads = options.downloadThreads
        val windowTaskRows = mutableListOf<NewWindow.TaskRow>()
        val workers = mutableListOf<Thread>()
        var ex: Throwable? = null
        val mainThread = currentThread()
        for (i in 0 until threads)
        {
            workers += Thread {
                val taskRow = window?.createTaskRow()?.also { windowTaskRows.add(it) }
                while (synchronized(lock2) { tasks.isNotEmpty() })
                {
                    val task: DownloadTask?
                    synchronized(lock2){ task = tasks.removeFirstOrNull() }
                    if (task == null)
                        continue
                    try {
                        download(task, taskRow)
                    } catch (_: InterruptedIOException) { break }
                    catch (_: InterruptedException) { break }
                }
                window?.destroyTaskRow(taskRow!!)
            }.apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e ->
                    ex = e
                    mainThread.interrupt()
                }
            }
        }

        // 等待所有线程完成
        try {
            for (worker in workers)
                worker.start()
            for (worker in workers)
                worker.join()
        } catch (e: InterruptedException) {
            for (worker in workers)
                worker.interrupt()
            throw ex ?: e
        }
    }

    /**
     * 组合一个包含多个可用源的URL链接
     */
    fun getMultipleUrls(path: String): List<String>
    {
        return options.server.map { PathUtils.getDirPathPart(it)!! + path }
    }
}