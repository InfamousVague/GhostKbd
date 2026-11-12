package com.infamousvague.devkeyboard

import org.json.JSONObject

/**
 * A string out of JSON that someone else wrote. Android's `optString` gives the word "null" for a JSON null (the
 * org.json the unit tests run on gives ""), so a server that answers `"nextCursor": null` or `"title": null` would
 * be read as saying "null": asked for a page called "null", or shown a tool named it. This gives "" for a null and
 * for a missing key alike, on both.
 */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
