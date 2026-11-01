package app.wayfinder.data

/**
 * Compact binary form of a [TileData], used for saved tiles and packs.
 *
 * Coordinates are stored as zig-zag varint deltas at 1e-6 degrees (about 0.1 m), so a typical
 * point takes 2-4 bytes instead of 8, and decoding is a single pass over one byte array.
 */
object TileCodec {
    const val VERSION = 5
    private const val SCALE = 1e6f

    fun encode(t: TileData): ByteArray {
        val o = Out(1 shl 14)
        o.varint(VERSION)
        o.varint(if (t.zoomFiltered) 1 else 0)
        o.varint(t.routes.size)
        for (r in t.routes) {
            o.str(r.key); o.str(r.mode.name); o.str(r.osmRoute); o.str(r.ref); o.str(r.name.take(200))
            o.int32(r.color); o.int32(r.text); o.varint(r.rank.coerceAtLeast(0))
            o.coord(r.bounds.south); o.coord(r.bounds.west); o.coord(r.bounds.north); o.coord(r.bounds.east)
            o.varint(r.parts.size)
            r.parts.forEachIndexed { i, p ->
                o.int32(r.partIds[i])
                val n = p.size / 2
                o.varint(n)
                var pLat = 0; var pLon = 0
                for (k in 0 until n) {
                    val lat = Math.round(p[k * 2] * SCALE); val lon = Math.round(p[k * 2 + 1] * SCALE)
                    o.zigzag(lat - pLat); o.zigzag(lon - pLon)
                    pLat = lat; pLon = lon
                }
            }
            o.varint(r.stops.size)
            for (s in r.stops) { o.str(s.id); o.str(s.name.take(200)); o.coord(s.lat); o.coord(s.lon) }
            o.varint(r.routeIdx.size)
            r.routeIdx.forEach { o.varint(it.coerceAtLeast(0)) }
        }
        return o.toByteArray()
    }

    fun decode(b: ByteArray): TileData {
        val i = In(b)
        require(i.varint() == VERSION)
        val zoomFiltered = i.varint() == 1
        val n = i.varint()
        val routes = ArrayList<TileRoute>(n)
        repeat(n) {
            val key = i.str(); val mode = TransitMode.valueOf(i.str()); val osmRoute = i.str(); val ref = i.str(); val name = i.str()
            val color = i.int32(); val text = i.int32(); val rank = i.varint()
            val bounds = Bounds(i.coord(), i.coord(), i.coord(), i.coord())
            val pc = i.varint()
            val parts = ArrayList<FloatArray>(pc)
            val ids = IntArray(pc)
            for (p in 0 until pc) {
                ids[p] = i.int32()
                val pts = i.varint()
                val arr = FloatArray(pts * 2)
                var lat = 0; var lon = 0
                for (k in 0 until pts) {
                    lat += i.zigzag(); lon += i.zigzag()
                    arr[k * 2] = lat / SCALE; arr[k * 2 + 1] = lon / SCALE
                }
                parts += arr
            }
            val sc = i.varint()
            val stops = List(sc) { StopRef(i.str(), i.str(), i.coord(), i.coord()) }
            val routeIdx = IntArray(i.varint()) { i.varint() }
            routes += TileRoute(key, mode, osmRoute, ref, name, color, text, rank, parts, ids, stops, bounds, routeIdx)
        }
        return TileData(routes, zoomFiltered)
    }

    private class Out(cap: Int) {
        var buf = ByteArray(cap)
        var n = 0
        fun ensure(k: Int) { if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k)) }
        fun byte(v: Int) { ensure(1); buf[n++] = v.toByte() }
        fun varint(v0: Int) { var v = v0; ensure(5); while (v and 0x7F.inv() != 0) { buf[n++] = ((v and 0x7F) or 0x80).toByte(); v = v ushr 7 }; buf[n++] = v.toByte() }
        fun zigzag(v: Int) = varint((v shl 1) xor (v shr 31))
        fun int32(v: Int) { ensure(4); buf[n++] = (v ushr 24).toByte(); buf[n++] = (v ushr 16).toByte(); buf[n++] = (v ushr 8).toByte(); buf[n++] = v.toByte() }
        fun coord(d: Double) = int32(Math.round(d * 1e6).toInt())
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(b.size); ensure(b.size); System.arraycopy(b, 0, buf, n, b.size); n += b.size }
        fun toByteArray() = buf.copyOf(n)
    }

    private class In(val b: ByteArray) {
        var p = 0
        fun varint(): Int {
            var shift = 0; var r = 0
            while (true) { val x = b[p++].toInt(); r = r or ((x and 0x7F) shl shift); if (x and 0x80 == 0) return r; shift += 7 }
        }
        fun zigzag(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }
        fun int32(): Int { val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF); p += 4; return v }
        fun coord() = int32() / 1e6
        fun str(): String { val len = varint(); val s = String(b, p, len, Charsets.UTF_8); p += len; return s }
    }
}
