package com.droiddeck.launcher.ui

internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("touch"),
<<<<<<< HEAD
    SECOND_SCREEN("second-screen:none"),
    DISPLAY("hud"),
    EFFECTS("look"),
=======
    GLASSES("glasses-game"),
    SECOND_SCREEN("second-screen:none"),
    DISPLAY("hud"),
    EFFECTS("look"),
    VR("steamvr"),
>>>>>>> upstream/main
    GAMES("anisotropy"),
    SESSION("suspend"),
}

<<<<<<< HEAD
internal fun sessionDrawerPages(hasSecondScreen: Boolean): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter { hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN }
=======
internal fun sessionDrawerPages(hasSecondScreen: Boolean, hasGlasses: Boolean = false): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter {
        (hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN) && (hasGlasses || it != SessionDrawerPage.GLASSES)
    }
>>>>>>> upstream/main

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
