package app.wayfinder.data

/** Decodes a Google-style encoded polyline with the given precision (5, 6 or 7). */
fun decodePolyline(encoded: String, precision: Int): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>(encoded.length / 4)
    var index = 0
    var lat = 0L
    var lon = 0L
    while (index < encoded.length) {
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

        result = 0L
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

        out += LatLon(lat / factor, lon / factor)
    }
    return out
}
