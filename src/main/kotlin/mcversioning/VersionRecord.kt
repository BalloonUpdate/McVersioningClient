package mcversioning

import org.json.JSONObject

/**
 * 表示一个版本记录
 */
class VersionRecord
{
    val oldFiles: MutableSet<String> = mutableSetOf()
    val newFiles: MutableSet<String> = mutableSetOf()
    val oldFolders: MutableSet<String> = mutableSetOf()
    val newFolders: MutableSet<String> = mutableSetOf()
    val newFilesLengthes: MutableMap<String, Long> = mutableMapOf()

    constructor()

    constructor(jsonObject: JSONObject)
    {
        oldFiles.addAll(jsonObject.getJSONArray("old_files").map { it as String })
        newFiles.addAll(jsonObject.getJSONArray("new_files").map { it as String })
        oldFolders.addAll(jsonObject.getJSONArray("old_folders").map { it as String })
        newFolders.addAll(jsonObject.getJSONArray("new_folders").map { it as String })
        newFilesLengthes.putAll(jsonObject.getJSONObject("new_files_lengthes").toMap() as Map<String, Long>)
    }

    fun apply(diff: VersionRecord)
    {
        for (oldFile in diff.oldFiles)
            if (!newFiles.remove(oldFile))
                oldFiles.add(oldFile)

        for (newFile in diff.newFiles)
            if (!oldFiles.remove(newFile))
                newFiles.add(newFile)

        for (oldFolder in diff.oldFolders)
            if (newFolders.remove(oldFolder))
                oldFolders.add(oldFolder)

        for (newFolder in diff.newFolders)
            if (oldFolders.remove(newFolder))
                newFolders.add(newFolder)

        newFilesLengthes.putAll(diff.newFilesLengthes)
    }
}