package io.github.egegonul.liftlog

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/*
 * DATA SAFETY
 * - Everything lives in one JSON file in the app's private storage (filesDir).
 *   Android keeps this across app updates as long as the applicationId and
 *   signing key stay the same.
 * - Writes go through AtomicFile, so a crash mid-save can't corrupt the file.
 * - The file carries a "schema" number. When a future feature changes the data
 *   shape, bump SCHEMA and add a step in migrate() so old data is upgraded,
 *   never discarded.
 * - If the file ever fails to parse, it's copied aside before anything else
 *   happens, so it can't be silently overwritten.
 */
const val SCHEMA = 1
const val LB = 2.20462

data class Entry(val date: String, val weightKg: Double, val reps: List<Int>)

data class Exercise(
    val id: String,
    val name: String,
    val group: String,
    val lo: Int,
    val hi: Int,
    val order: Long,
    val entries: List<Entry> = emptyList(),
) {
    val sorted: List<Entry> get() = entries.sortedBy { it.date }
    val last: Entry? get() = sorted.lastOrNull()
}

data class WeighIn(val date: String, val kg: Double)

data class AppData(
    val unit: String = "kg",
    val weights: List<WeighIn> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
)

fun newId(): String = UUID.randomUUID().toString()

object Codec {
    fun toJson(d: AppData): String {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("unit", d.unit)
        o.put("weights", JSONArray().apply {
            d.weights.forEach { put(JSONObject().put("d", it.date).put("kg", it.kg)) }
        })
        o.put("exercises", JSONArray().apply {
            d.exercises.forEach { e ->
                val entries = JSONArray()
                e.entries.forEach { en ->
                    entries.put(JSONObject().put("d", en.date).put("w", en.weightKg).put("reps", JSONArray(en.reps)))
                }
                put(
                    JSONObject().put("id", e.id).put("name", e.name).put("group", e.group)
                        .put("lo", e.lo).put("hi", e.hi).put("order", e.order).put("entries", entries)
                )
            }
        })
        return o.toString(2)
    }

    fun fromJson(s: String): AppData {
        val o = migrate(JSONObject(s))
        val wArr = o.optJSONArray("weights") ?: JSONArray()
        val weights = (0 until wArr.length()).map {
            val w = wArr.getJSONObject(it)
            WeighIn(w.getString("d"), w.getDouble("kg"))
        }
        val xArr = o.optJSONArray("exercises") ?: JSONArray()
        val exercises = (0 until xArr.length()).map { i ->
            val x = xArr.getJSONObject(i)
            val eArr = x.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until eArr.length()).map { j ->
                val e = eArr.getJSONObject(j)
                val r = e.getJSONArray("reps")
                Entry(e.getString("d"), e.getDouble("w"), (0 until r.length()).map { r.getInt(it) })
            }
            Exercise(
                id = x.getString("id"),
                name = x.getString("name"),
                group = x.optString("group", "Other"),
                lo = x.optInt("lo", 8),
                hi = x.optInt("hi", 12),
                order = x.optLong("order", i.toLong()),
                entries = entries,
            )
        }
        return AppData(o.optString("unit", "kg"), weights, exercises)
    }

    /** Upgrade older data files step by step. Add a new `if` per schema bump. */
    private fun migrate(o: JSONObject): JSONObject {
        val v = o.optInt("schema", 1)
        if (v > SCHEMA) throw IllegalStateException("This backup is from a newer version of the app")
        // Example for the future:
        // if (v < 2) { /* transform o */ o.put("schema", 2) }
        return o
    }
}

class Store(context: Context) {
    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "liftlog.json"))

    fun load(): AppData {
        if (!file.baseFile.exists()) return AppData()
        return try {
            Codec.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the unreadable file so it is never overwritten.
            runCatching { file.baseFile.copyTo(File(dir, "liftlog.unreadable-${System.currentTimeMillis()}.json")) }
            AppData()
        }
    }

    fun save(d: AppData) {
        val out = file.startWrite()
        try {
            out.write(Codec.toJson(d).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Copy of current data taken before an import replaces or merges anything. */
    fun snapshot() {
        if (file.baseFile.exists()) {
            runCatching { file.baseFile.copyTo(File(dir, "liftlog.before-import.json"), overwrite = true) }
        }
    }
}

/** Imports the CSV exported by the Lift Log web app (merges, never deletes). */
object CsvImport {
    private fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                } else sb.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun merge(csv: String, base: AppData): AppData {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return base
        val header = parseLine(lines[0])
        val isLb = (header.getOrNull(4) ?: "").endsWith("lb")
        fun kg(v: Double) = if (isLb) v / LB else v

        val weights = base.weights.associateBy { it.date }.toMutableMap()
        val exs = LinkedHashMap<String, Exercise>()
        base.exercises.forEach { exs[it.name.lowercase()] = it }
        var order = (base.exercises.maxOfOrNull { it.order } ?: 0L) + 1

        for (line in lines.drop(1)) {
            val f = parseLine(line)
            if (f.size < 5) continue
            val v = f[4].toDoubleOrNull() ?: continue
            if (runCatching { LocalDate.parse(f[1]) }.isFailure) continue
            when (f[0]) {
                "bodyweight" -> weights[f[1]] = WeighIn(f[1], kg(v))
                "lift" -> {
                    val reps = (f.getOrNull(5) ?: "").split(" ").mapNotNull { it.toIntOrNull() }
                    if (reps.isEmpty() || f[2].isBlank()) continue
                    val key = f[2].lowercase()
                    val ex = exs[key] ?: Exercise(newId(), f[2], f[3].ifBlank { "Other" }, 8, 12, order++)
                    val entry = Entry(f[1], kg(v), reps)
                    val dup = ex.entries.any { it.date == entry.date && it.reps == entry.reps && Math.abs(it.weightKg - entry.weightKg) < 0.05 }
                    exs[key] = if (dup) ex else ex.copy(entries = ex.entries + entry)
                }
            }
        }
        return base.copy(weights = weights.values.toList(), exercises = exs.values.toList())
    }
}

// ---------- units & formatting ----------
fun toKg(v: Double, unit: String) = if (unit == "lb") v / LB else v
fun fromKg(kg: Double, unit: String) = if (unit == "lb") kg * LB else kg
fun fmtNum(v: Double): String {
    val r = Math.round(v * 10) / 10.0
    return if (r % 1.0 == 0.0) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
}
fun fmtW(kg: Double, unit: String) = fmtNum(fromKg(kg, unit))
fun e1rm(w: Double, reps: Int) = w * (1 + reps / 30.0)
fun prettyDate(s: String): String = runCatching {
    val d = LocalDate.parse(s)
    d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "MMM d" else "MMM d, yy"))
}.getOrDefault(s)

// ---------- program ----------
val GROUPS = listOf("Push", "Pull", "Legs", "Upper", "Lower", "Other")
fun groupOf(e: Exercise) = if (e.group in GROUPS) e.group else "Other"

private data class P(val g: String, val n: String, val lo: Int, val hi: Int)
private val PLAN = listOf(
    P("Push", "Bench press", 6, 10), P("Push", "Barbell shoulder press", 6, 10), P("Push", "Incline DB press", 8, 12),
    P("Push", "Lateral raises", 12, 20), P("Push", "Triceps pushdown", 10, 15), P("Push", "Machine press", 8, 12),
    P("Push", "DB shoulder press", 8, 12), P("Push", "Cable fly", 10, 15), P("Push", "Overhead triceps extension", 10, 15),
    P("Pull", "Lat pulldown", 8, 12), P("Pull", "Cable row", 8, 12), P("Pull", "Rear-delt fly", 15, 20),
    P("Pull", "Barbell curl", 8, 12), P("Pull", "Hammer curl", 10, 15), P("Pull", "Pull-ups", 6, 10),
    P("Pull", "Chest-supported row", 8, 12), P("Pull", "Facepull", 15, 20), P("Pull", "Incline DB curl", 10, 15),
    P("Legs", "Squat", 6, 10), P("Legs", "Romanian deadlift", 6, 10), P("Legs", "Leg extension", 10, 15),
    P("Legs", "Seated leg curl", 10, 15), P("Legs", "Calf raise", 10, 15), P("Legs", "Leg press", 8, 12),
    P("Legs", "Hip thrust", 8, 12), P("Legs", "Lying leg curl", 10, 15),
)
fun planExercises(): List<Exercise> = PLAN.mapIndexed { i, (g, n, lo, hi) -> Exercise(newId(), n, g, lo, hi, i.toLong()) }

// ---------- double progression ----------
enum class Kind { NEW, REPS, UP }
data class Status(val kind: Kind, val label: String, val text: String, val nextKg: Double?)

fun status(ex: Exercise, unit: String): Status {
    val l = ex.last ?: return Status(
        Kind.NEW, "New",
        "First session: pick a weight you can do for ${ex.lo}–${ex.hi} reps with 1–2 reps left in the tank.", null
    )
    val step = if (unit == "lb") 5.0 else 2.5
    return if (l.reps.isNotEmpty() && l.reps.all { it >= ex.hi }) {
        Status(
            Kind.UP, "Add weight",
            "You hit ${ex.hi} reps on every set last time. Add ${fmtNum(step)} $unit and build back up.",
            l.weightKg + toKg(step, unit)
        )
    } else {
        Status(
            Kind.REPS, "Add reps",
            "Same weight. Beat last time by at least one rep (${l.reps.joinToString(", ")}).", l.weightKg
        )
    }
}
