package dev.pokepad.save

/*
 * Poképad — Gen-III save parser → SaveTruth (READ-ONLY), a faithful Kotlin port
 * of save/gen3.py (proven on real cartridge dumps). Parses RSE/FRLG .sav bytes
 * into the trainer + party: every fact from the file, never written.
 *
 * Two 14-section slots; active = higher save index. Section 0 = trainer,
 * section 1 = team. Each party mon is 100 bytes with a 48-byte data block that
 * is XOR-encrypted (key = PID^OTID) and whose four 12-byte substructures
 * {Growth, Attacks, EVs, Misc} are ordered by PID % 24. Nature/shininess are
 * derived from PID/OTID.
 */

private const val SECTION_SIZE = 0x1000
private const val SLOT_SECTIONS = 14
private const val SLOT_SIZE = SLOT_SECTIONS * SECTION_SIZE
private const val SIGNATURE = 0x08012025

private val ORDERS = listOf(
    "GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME",
    "AEGM", "AEMG", "AMGE", "AMEG", "EGAM", "EGMA", "EAGM", "EAMG",
    "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG")
private val NATURES = listOf(
    "hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile",
    "relaxed", "impish", "lax", "timid", "hasty", "serious", "jolly",
    "naive", "modest", "mild", "quiet", "bashful", "rash", "calm",
    "gentle", "sassy", "careful", "quirky")

/** Gen-III English character map (printable subset). */
private val CHARMAP: Map<Int, Char> = buildMap {
    put(0x00, ' ')
    "0123456789".forEachIndexed { i, c -> put(0xA1 + i, c) }
    for (i in 0 until 26) { put(0xBB + i, ('A' + i)); put(0xD5 + i, ('a' + i)) }
    putAll(mapOf(0xAB to '!', 0xAC to '?', 0xAD to '.', 0xAE to '-', 0xBA to '/'))
}

/** one parsed party member — the real facts from the save */
data class SaveMon(
    val species: String?, val internalIndex: Int, val nickname: String,
    val level: Int, val nature: String, val ability: String?, val shiny: Boolean,
    val ivs: Map<String, Int>, val evs: Map<String, Int>,
    val moves: List<String>, val otName: String, val otId: Int)

data class SaveTrainer(val name: String, val game: String, val trainerId: Int,
                       val playHours: Int, val playMinutes: Int)

data class SaveTruth(val trainer: SaveTrainer, val party: List<SaveMon>)

class Gen3Save(
    /** internal species index → national name (data/gen3_i2n.tsv) */
    private val i2n: Map<Int, String>,
    /** move index → move name (data/gen3_moveidx.tsv) */
    private val moveName: Map<Int, String>,
    /** national name → (ability0, ability1) for ability-slot resolution */
    private val abilities: (String) -> List<String>,
) {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toInt() and 0xFF).toLong() or ((b[o + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[o + 2].toInt() and 0xFF).toLong() shl 16) or ((b[o + 3].toInt() and 0xFF).toLong() shl 24)

    private fun name(b: ByteArray, from: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until len) { val x = b[from + i].toInt() and 0xFF; if (x == 0xFF) break; CHARMAP[x]?.let { sb.append(it) } }
        return sb.toString()
    }

    /** read one slot → (saveIndex, sectionId→bytes) or null */
    private fun readSlot(data: ByteArray, base: Int): Pair<Long, Map<Int, ByteArray>>? {
        val sections = HashMap<Int, ByteArray>(); var saveIndex = -1L
        for (i in 0 until SLOT_SECTIONS) {
            val off = base + i * SECTION_SIZE
            if (off + SECTION_SIZE > data.size) return null
            val sec = data.copyOfRange(off, off + SECTION_SIZE)
            if (u32(sec, 0x0FF8).toInt() != SIGNATURE) continue
            sections[u16(sec, 0x0FF4)] = sec
            saveIndex = u32(sec, 0x0FFC)
        }
        return if (sections.isEmpty()) null else saveIndex to sections
    }

    private fun pickActive(data: ByteArray): Map<Int, ByteArray> {
        val a = readSlot(data, 0); val b = readSlot(data, SLOT_SIZE)
        val cands = listOfNotNull(a, b)
        if (cands.isEmpty()) throw IllegalArgumentException("no valid Gen-III save slot found")
        fun idx(x: Pair<Long, Map<Int, ByteArray>>) = if (x.first == 0xFFFFFFFFL || x.first < 0) -1L else x.first
        return cands.maxByOrNull { idx(it) }!!.second
    }

    private fun decodeMon(mon: ByteArray): SaveMon? {
        val pid = u32(mon, 0x00); val otid = u32(mon, 0x04)
        if (pid == 0L && otid == 0L) return null
        val nickname = name(mon, 0x08, 10)
        val otName = name(mon, 0x14, 7)
        // decrypt the 48-byte data block
        val key = pid xor otid
        val enc = mon.copyOfRange(0x20, 0x50)
        for (i in 0 until 48 step 4) {
            val w = (u32(enc, i) xor key) and 0xFFFFFFFFL
            enc[i] = (w and 0xFF).toByte(); enc[i + 1] = ((w shr 8) and 0xFF).toByte()
            enc[i + 2] = ((w shr 16) and 0xFF).toByte(); enc[i + 3] = ((w shr 24) and 0xFF).toByte()
        }
        val order = ORDERS[(pid % 24).toInt()]
        val blocks = HashMap<Char, ByteArray>()
        for (p in 0 until 4) blocks[order[p]] = enc.copyOfRange(p * 12, (p + 1) * 12)
        val g = blocks['G']!!; val a = blocks['A']!!; val e = blocks['E']!!; val m = blocks['M']!!

        val internal = u16(g, 0)
        val spName = i2n[internal]
        val movesIdx = listOf(u16(a, 0), u16(a, 2), u16(a, 4), u16(a, 6)).filter { it != 0 }
        val evs = mapOf("hp" to (e[0].toInt() and 0xFF), "atk" to (e[1].toInt() and 0xFF),
            "def" to (e[2].toInt() and 0xFF), "spe" to (e[3].toInt() and 0xFF),
            "spa" to (e[4].toInt() and 0xFF), "spd" to (e[5].toInt() and 0xFF))
        val ivWord = u32(m, 4)
        val ivs = mapOf("hp" to (ivWord and 31).toInt(), "atk" to ((ivWord shr 5) and 31).toInt(),
            "def" to ((ivWord shr 10) and 31).toInt(), "spe" to ((ivWord shr 15) and 31).toInt(),
            "spa" to ((ivWord shr 20) and 31).toInt(), "spd" to ((ivWord shr 25) and 31).toInt())
        val abilityBit = ((ivWord shr 31) and 1).toInt()

        val nature = NATURES[(pid % 25).toInt()]
        val tidLow = (otid and 0xFFFF); val tidHigh = (otid shr 16)
        val pidLow = (pid and 0xFFFF); val pidHigh = (pid shr 16)
        val shiny = (tidLow xor tidHigh xor pidLow xor pidHigh) < 8

        val ability = spName?.let { val abs = abilities(it); if (abs.isEmpty()) null else abs[abilityBit.coerceAtMost(abs.size - 1)] }
        val level = if (mon.size >= 0x55) (mon[0x54].toInt() and 0xFF) else 50
        val moves = movesIdx.mapNotNull { moveName[it] }

        return SaveMon(spName, internal, nickname.ifEmpty { spName ?: "?" }, level, nature, ability, shiny,
            ivs, evs, moves, otName, tidLow.toInt())
    }

    fun parse(data: ByteArray): SaveTruth {
        if (data.size < SLOT_SIZE) throw IllegalArgumentException("save too small (${data.size} bytes)")
        val sections = pickActive(data)
        val s0 = sections[0] ?: throw IllegalArgumentException("missing trainer section")
        val s1 = sections[1] ?: throw IllegalArgumentException("missing team section")

        val game = when (u32(s0, 0x00AC).toInt()) { 0 -> "RS"; 1 -> "FRLG"; else -> "Emerald" }
        val trainer = SaveTrainer(name(s0, 0x00, 7), game, u16(s0, 0x0A), u16(s0, 0x0E), s0[0x10].toInt() and 0xFF)

        val (sizeOff, teamOff) = if (game == "FRLG") 0x0034 to 0x0038 else 0x0234 to 0x0238
        val teamSize = minOf(s1[sizeOff].toInt() and 0xFF, 6)
        val party = ArrayList<SaveMon>()
        for (i in 0 until teamSize) {
            val mon = s1.copyOfRange(teamOff + i * 100, teamOff + (i + 1) * 100)
            decodeMon(mon)?.let { party.add(it) }
        }
        return SaveTruth(trainer, party)
    }
}
