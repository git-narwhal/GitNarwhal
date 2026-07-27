package com.gitnarwhal.utils

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KProperty
import kotlin.reflect.full.memberProperties

//TODO: discover if you can write this in a better way, the fact that you have a class constant outside of the class it's horrible
// `gitnarwhal.configFile` lets tests redirect persistence to a throwaway file so the
// suite never clobbers the user's real ~/.gitnarwhal.json (open tabs, recent repos…).
private val CONFIG = System.getProperty("gitnarwhal.configFile")
    ?: (System.getProperty("user.home") + "/.gitnarwhal.json")
private val settingsJSON = if(File(CONFIG).exists()) Files.readString(Path.of(CONFIG)) else "{}";

object Settings : JSONObject(settingsJSON) {
    val FILE = CONFIG

    //TODO: you could add an optional callback to JSONSettings, so editing a property immediately call the callback that applies it.

    var autoUpdate           by JSONSetting(true)
    var ignoredUpdateVersion by JSONSetting("")
    var theme                by JSONSetting("classpath:/com/gitnarwhal/themes/claude.theme.json")

    var openTabs             by JSONSetting(JSONArray())
    var recentRepos          by JSONSetting(JSONArray())
    var recentFolders        by JSONSetting(JSONArray())

    // General
    var reopenTabs          by JSONSetting(true)
    var defaultCloneFolder  by JSONSetting("")
    var terminalPreset      by JSONSetting("auto")
    var terminalCommand     by JSONSetting("")
    var ideCommand          by JSONSetting("")

    // Diff
    var diffFontFamily      by JSONSetting("Monospaced")
    var diffFontSize        by JSONSetting(12)
    var diffIgnoreWhitespace by JSONSetting(false)
    var diffIgnorePatterns  by JSONSetting("")

    // Git
    var enableForcePush     by JSONSetting(true)
    var safeForcePush       by JSONSetting(true)
    var gitPath             by JSONSetting("")

    // Custom Actions
    var customActions       by JSONSetting(JSONArray())

    // Progress overlay "Show output" checkbox, remembered per operation
    var showOutputCommit    by JSONSetting(false)
    var showOutputPush      by JSONSetting(false)
    var showOutputPull      by JSONSetting(false)
    var showOutputFetch     by JSONSetting(false)

    // UI
    var columnWidths         by JSONSetting(JSONObject().also {
        it.put("graph", 80); it.put("date", 135); it.put("committer", 120); it.put("commit", 70)
    })
}

fun Settings.save() {
    Thread{
        synchronized(this){
            with(FileWriter(Settings.FILE)){
                Settings.write(this, 4,0)
                this.close()
            }
        }
    }.start()
}