package com.camremote.app

/**
 * The prefix every logcat tag this app writes starts with.
 *
 * Filtering by package still surfaces everything the OS and every library attribute to this
 * process — CameraX, the camera HAL, Ktor's own internals — which drowns out what the app itself
 * did. `adb logcat | grep cam-remote-app` catches only lines this project wrote, whatever their
 * individual tag.
 */
internal const val LOG_TAG_PREFIX = "cam-remote-app"
