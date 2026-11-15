package com.lukemonaghan.tvhome

/** The words of the platform features (updates, smart home, doorbell, custom cards, home screen). One place, so they are easy to move into Android's string resources later. */
object PS {
    const val SMART_HOME = "Smart home"
    const val CLIMATE = "Air conditioning"
    const val SCENES = "Scenes"
    const val CHECKING = "Checking"
    const val DOWN = "Home Assistant is not answering. Check it is on and on the same network as the TV."
    const val ROOM_QUIET = "Not answering"
    const val SET_TO = "Set to"
    const val RANGE = "Between"
    const val NOW = "Now %s"
    const val OFF = "Off"
    const val MODE = "Mode"
    const val COOLER = "Cooler"
    const val WARMER = "Warmer"
    const val TURNED_ON = "Turning %s on"
    const val TURNED_OFF = "Turning %s off"
    const val MODE_NOW = "%s: %s"
    const val NO_SETPOINT = "This one is set from Home Assistant, not here."
    const val NO_MODES = "This one has no modes to choose from."
    const val STARTING = "Starting %s"
    const val STARTED = "%s: done."
    const val FAILED = "That did not work. Home Assistant may be busy, try again."
    const val REFUSED = "That is not one of the things this TV may change."
    const val SH_HINT = "Left and right move, up and down change rows. OK presses. Back closes."

    fun mode(m: String) = when (m) {
        "heat" -> "Heat"; "cool" -> "Cool"; "heat_cool" -> "Heat and cool"; "auto" -> "Auto"
        "dry" -> "Dry"; "fan_only" -> "Fan"; else -> m
    }
    fun action(a: String) = when (a) {
        "heating" -> "Heating"; "cooling" -> "Cooling"; "drying" -> "Drying"; "fan" -> "Fan on"; "idle" -> "Resting"; else -> ""
    }

    const val UPD_CHECK = "Check for updates now"
    const val UPD_AUTO = "Tell me about new versions"
    const val UPD_CHECKING = "Checking…"
    const val UPD_NEVER = "Not checked yet"
    const val UPD_LATEST = "This is the latest version."
    const val UPD_CONFIRM = "Confirm on the screen that opens"
    const val UPD_NOTE = "Updates come from this app's releases on GitHub. With Tell me on, the TV looks once a week. It always asks before it installs one, and your settings stay as they are."
    const val UPD_OFF = "No release address is set, so updates are off. Set one under Release address."
    const val UPD_ASK = "Tell you when a new version is out?"
    const val UPD_ASK_SUB = "The TV looks once a week and puts a dot on the Settings tile. It never installs anything without asking."
    fun updErr(e: String) = when (e) {
        "net" -> "Can't reach GitHub. Check the Wi-Fi and try again."
        "bad" -> "The download did not check out, so nothing was installed. Try again later."
        "other_key" -> "This copy was built on another computer, so it can't take this update. Update it the way it was installed."
        "allow" -> "First let Home install apps: in the TV's settings, Unknown sources (under Security and restrictions). Then choose Update again."
        "cancelled" -> "Update cancelled. Nothing changed."
        else -> "Android did not install the update. Nothing changed."
    }

    const val HOME_ASK = "Make tvhome your home screen?"
    const val HOME_ASK_SUB = "So the Home button brings this up. Android shows its own screen: choose this app and press Always. The TV's own home stays installed."
    const val HOME_ROW = "Make tvhome your home screen"
    const val HOME_ROW_SUB = "Asks Android to open this when you press Home"
    const val HOME_GOOGLE_TV = "This is Google TV, which can keep its own home on the Home button. Setting up the Home button watch from a computer (tools/install.sh) makes tvhome come back."
    const val HOME_NOW = "tvhome is now your home screen."
    const val HOME_NOT_YET = "Not changed yet. You can try again in Home settings, About."

    const val RESET = "Reset home settings"
    const val RESET_SUB = "Look, scenes, place, weather and quick buttons go back to how they were at the start. People and apps stay."
    const val RESET_ASK = "Reset home settings?"
    const val RESET_DONE = "Back to the starting settings."
}
