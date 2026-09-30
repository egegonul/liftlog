@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.egegonul.liftlog

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

typealias Updater = ((AppData) -> AppData) -> Unit

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun kindColor(k: Kind) = when (k) {
    Kind.UP -> Color(0xFF2E9E5B)
    Kind.REPS -> Color(0xFFE0A800)
    Kind.NEW -> Color(0xFF3D7FD0)
}

fun groupColor(g: String) = when (g) {
    "Push" -> Color(0xFFC22A2A)
    "Pull" -> Color(0xFF3D7FD0)
    "Legs", "Lower" -> Color(0xFF2E9E5B)
    "Upper" -> Color(0xFFE0A800)
    else -> Color(0xFF8A9490)
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(store.load()) }
    val update: Updater = { f ->
        val n = f(data)
        data = n
        try { store.save(n) } catch (e: Exception) { toast(ctx, "Couldn't save: ${e.message}") }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<AppData?>(null) }

    BackHandler(enabled = tab == 0 && openId != null) { openId = null }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Codec.toJson(data).toByteArray(Charsets.UTF_8)) }
            }.onSuccess { toast(ctx, "Backup exported") }
                .onFailure { toast(ctx, "Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text.trimStart().startsWith("{")) {
                    pendingImport = Codec.fromJson(text)
                } else {
                    store.snapshot()
                    update { CsvImport.merge(text, it) }
                    toast(ctx, "CSV imported")
                }
            }.onFailure { toast(ctx, "Couldn't read that file: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lift Log", fontWeight = FontWeight.Bold) },
                actions = {
                    UnitToggle(data.unit) { u -> update { it.copy(unit = u) } }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export backup") },
                                onClick = { menu = false; exportLauncher.launch("liftlog-${LocalDate.now()}.json") }
                            )
                            DropdownMenuItem(
                                text = { Text("Import backup or CSV") },
                                onClick = { menu = false; importLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { if (tab == 0) openId = null; tab = 0 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Lifts") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text("Body weight") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val open = data.exercises.find { it.id == openId }
            when {
                tab == 1 -> BodyScreen(data, update)
                open != null -> ExerciseScreen(open, data.unit, onBack = { openId = null }, update = update)
                else -> LiftsScreen(data, onOpen = { openId = it }, update = update)
            }
        }
    }

    pendingImport?.let { imp ->
        ConfirmDialog(
            "Replace all data with this backup? A copy of your current data is kept on the device.",
            "Replace",
            onDismiss = { pendingImport = null }
        ) {
            store.snapshot()
            update { imp }
            pendingImport = null
            toast(ctx, "Backup restored")
        }
    }
}

@Composable
fun UnitToggle(unit: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("kg", "lb").forEach { u ->
            FilterChip(selected = unit == u, onClick = { if (unit != u) onChange(u) }, label = { Text(u) })
        }
    }
}

// ---------------- Lifts ----------------
@Composable
fun LiftsScreen(data: AppData, onOpen: (String) -> Unit, update: Updater) {
    var adding by remember { mutableStateOf(false) }
    if (data.exercises.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No exercises yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Start with your push/pull/legs plan or add exercises one at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { update { it.copy(exercises = planExercises()) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Add my PPL exercises")
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add an exercise") }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GROUPS.forEach { g ->
                val list = data.exercises.filter { groupOf(it) == g }.sortedBy { it.order }
                if (list.isNotEmpty()) {
                    item(key = "h-$g") { GroupHeader(g) }
                    items(list, key = { it.id }) { ex -> ExerciseRow(ex, data.unit) { onOpen(ex.id) } }
                }
            }
            item(key = "add") {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add an exercise") }
            }
        }
    }
    if (adding) {
        ExerciseDialog(null, onDismiss = { adding = false }, onDelete = null) { ex ->
            update { it.copy(exercises = it.exercises + ex) }
            adding = false
        }
    }
}

@Composable
fun GroupHeader(g: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Box(Modifier.size(width = 8.dp, height = 20.dp).background(groupColor(g), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(g, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ExerciseRow(ex: Exercise, unit: String, onClick: () -> Unit) {
    val st = status(ex, unit)
    val l = ex.last
    val sub = if (l != null) "${prettyDate(l.date)} · ${fmtW(l.weightKg, unit)} $unit × ${l.reps.joinToString(", ")}"
              else "${ex.lo}–${ex.hi} reps"
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ex.name, fontWeight = FontWeight.SemiBold)
                Text(
                    sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(st.kind, st.label)
        }
    }
}

@Composable
fun StatusChip(k: Kind, label: String) {
    Surface(shape = RoundedCornerShape(50), color = kindColor(k).copy(alpha = 0.2f)) {
        Text(
            label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ExerciseDialog(ex: Exercise?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Exercise) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(ex?.name ?: "") }
    var group by remember { mutableStateOf(ex?.let { groupOf(it) } ?: "Push") }
    var lo by remember { mutableStateOf(ex?.lo?.toString() ?: "8") }
    var hi by remember { mutableStateOf(ex?.hi?.toString() ?: "12") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ex == null) "Add an exercise" else "Edit exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUPS.forEach { g -> FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lo, onValueChange = { lo = it.filter(Char::isDigit).take(3) },
                        label = { Text("Reps low") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hi, onValueChange = { hi = it.filter(Char::isDigit).take(3) },
                        label = { Text("Reps high") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete exercise", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = lo.toIntOrNull()
                val h = hi.toIntOrNull()
                if (name.isBlank()) {
                    toast(ctx, "Enter a name")
                } else if (l == null || h == null || l < 1 || h < l) {
                    toast(ctx, "Rep range high must be at least the low end")
                } else {
                    val baseEx = ex ?: Exercise(newId(), "", "", 0, 0, System.currentTimeMillis())
                    onSave(baseEx.copy(name = name.trim(), group = group, lo = l, hi = h))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete this exercise and all its history?", "Delete", onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            onDelete()
        }
    }
}

@Composable
fun ConfirmDialog(text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExerciseScreen(ex: Exercise, unit: String, onBack: () -> Unit, update: Updater) {
    val ctx = LocalContext.current
    val st = status(ex, unit)
    val last = ex.last
    val sorted = ex.sorted
    var weightText by remember(ex.id, ex.entries.size, unit) { mutableStateOf(st.nextKg?.let { fmtW(it, unit) } ?: "") }
    var dateText by remember(ex.id) { mutableStateOf(LocalDate.now().toString()) }
    val reps = remember(ex.id, ex.entries.size) {
        mutableStateListOf<String>().apply { repeat(last?.reps?.size ?: 3) { add("") } }
    }
    var metric by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Entry?>(null) }

    val replace: ((Exercise) -> Exercise) -> Unit = { f ->
        update { d -> d.copy(exercises = d.exercises.map { if (it.id == ex.id) f(it) else it }) }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Lifts") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ex.name, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                }
                Text("${groupOf(ex)} · ${ex.lo}–${ex.hi} reps", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Log a session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Surface(color = kindColor(st.kind).copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
                        Text(st.text, modifier = Modifier.padding(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = weightText, onValueChange = { weightText = it },
                            label = { Text("Weight ($unit)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = dateText, onValueChange = { dateText = it },
                            label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Text("Reps per set (blank = same as last time)", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reps.forEachIndexed { i, r ->
                            OutlinedTextField(
                                value = r,
                                onValueChange = { v -> reps[i] = v.filter(Char::isDigit).take(3) },
                                placeholder = { Text(last?.reps?.getOrNull(i)?.toString() ?: "") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(72.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (reps.size > 1) reps.removeAt(reps.size - 1) }, modifier = Modifier.weight(1f)) {
                            Text("Remove set")
                        }
                        OutlinedButton(onClick = { if (reps.size < 10) reps.add("") }, modifier = Modifier.weight(1f)) {
                            Text("Add set")
                        }
                    }
                    Button(onClick = {
                        val w = weightText.trim().replace(',', '.').toDoubleOrNull()
                        val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                        val rs = reps.mapIndexedNotNull { i, s -> s.toIntOrNull() ?: last?.reps?.getOrNull(i) }.filter { it > 0 }
                        if (w == null || w < 0) {
                            toast(ctx, "Enter the weight you used")
                        } else if (date == null) {
                            toast(ctx, "Use a date like ${LocalDate.now()}")
                        } else if (rs.isEmpty()) {
                            toast(ctx, "Enter reps for at least one set")
                        } else {
                            replace { it.copy(entries = it.entries + Entry(date.toString(), toKg(w, unit), rs)) }
                            toast(ctx, "Session saved")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save session") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = metric == 0, onClick = { metric = 0 }, label = { Text("Estimated 1RM") })
                        FilterChip(selected = metric == 1, onClick = { metric = 1 }, label = { Text("Working weight") })
                    }
                    val pts = sorted.mapNotNull { e ->
                        val day = runCatching { LocalDate.parse(e.date).toEpochDay() }.getOrNull() ?: return@mapNotNull null
                        val kg = if (metric == 0) e.reps.maxOfOrNull { e1rm(e.weightKg, it) } ?: e.weightKg else e.weightKg
                        day to fromKg(kg, unit)
                    }
                    LineChart(pts, MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (sorted.isEmpty()) {
                        Text("No sessions logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    sorted.asReversed().forEach { e ->
                        HistoryRow(prettyDate(e.date), "${fmtW(e.weightKg, unit)} $unit × ${e.reps.joinToString(", ")}") {
                            toDelete = e
                        }
                    }
                }
            }
        }
    }

    if (editing) {
        ExerciseDialog(
            ex,
            onDismiss = { editing = false },
            onDelete = {
                editing = false
                onBack()
                update { d -> d.copy(exercises = d.exercises.filter { it.id != ex.id }) }
            }
        ) { n ->
            replace { n }
            editing = false
        }
    }
    toDelete?.let { e ->
        ConfirmDialog("Delete this session?", "Delete", onDismiss = { toDelete = null }) {
            replace { it.copy(entries = it.entries - e) }
            toDelete = null
        }
    }
}

@Composable
fun HistoryRow(date: String, main: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            date, modifier = Modifier.width(92.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        Text(main, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Body weight ----------------
@Composable
fun BodyScreen(data: AppData, update: Updater) {
    val ctx = LocalContext.current
    val unit = data.unit
    val ws = data.weights.sortedBy { it.date }
    val last = ws.lastOrNull()
    var wText by remember { mutableStateOf("") }
    var dText by remember { mutableStateOf(LocalDate.now().toString()) }
    var toDelete by remember { mutableStateOf<WeighIn?>(null) }

    var change = "–"
    var rate = "–"
    if (ws.size >= 2 && last != null) {
        val lastDay = LocalDate.parse(last.date).toEpochDay()
        val ref = ws.asReversed().firstOrNull { lastDay - LocalDate.parse(it.date).toEpochDay() >= 27 } ?: ws.first()
        val diff = fromKg(last.kg, unit) - fromKg(ref.kg, unit)
        change = (if (diff > 0) "+" else "") + fmtNum(diff)
        val days = lastDay - LocalDate.parse(ref.date).toEpochDay()
        if (days > 0) {
            val pm = (last.kg - ref.kg) / ref.kg * 100.0 * 30.0 / days
            rate = (if (pm > 0) "+" else "") + String.format(Locale.US, "%.2f%%", pm)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(last?.let { fmtW(it.kg, unit) } ?: "–", "Latest ($unit)", Modifier.weight(1f))
                Stat(change, "Last ~4 weeks", Modifier.weight(1f))
                Stat(rate, "Per month", Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Weekly weigh-in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = wText, onValueChange = { wText = it },
                            label = { Text("Weight ($unit)") },
                            placeholder = { Text(last?.let { fmtW(it.kg, unit) } ?: "") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = dText, onValueChange = { dText = it },
                            label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Button(onClick = {
                        val v = wText.trim().replace(',', '.').toDoubleOrNull()
                        val date = runCatching { LocalDate.parse(dText.trim()) }.getOrNull()
                        if (v == null || v <= 0) {
                            toast(ctx, "Enter your weight")
                        } else if (date == null) {
                            toast(ctx, "Use a date like ${LocalDate.now()}")
                        } else {
                            val ds = date.toString()
                            update { d -> d.copy(weights = d.weights.filter { it.date != ds } + WeighIn(ds, toKg(v, unit))) }
                            wText = ""
                            toast(ctx, "Weigh-in saved")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save weigh-in") }
                    Text(
                        "Lean-bulk target: roughly +0.25–0.5% of bodyweight per month.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Trend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val pts = ws.mapNotNull { w ->
                        runCatching { LocalDate.parse(w.date).toEpochDay() }.getOrNull()?.let { it to fromKg(w.kg, unit) }
                    }
                    LineChart(pts, MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (ws.isEmpty()) Text("No weigh-ins yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ws.asReversed().forEach { w ->
                        HistoryRow(prettyDate(w.date), "${fmtW(w.kg, unit)} $unit") { toDelete = w }
                    }
                }
            }
        }
    }

    toDelete?.let { w ->
        ConfirmDialog("Delete this weigh-in?", "Delete", onDismiss = { toDelete = null }) {
            update { d -> d.copy(weights = d.weights.filter { it != w }) }
            toDelete = null
        }
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Chart ----------------
@Composable
fun LineChart(points: List<Pair<Long, Double>>, color: Color) {
    if (points.size < 2) {
        Text(
            "Log at least two entries to see the chart.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)
        )
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textPx = with(LocalDensity.current) { 12.sp.toPx() }
    val dateFmt = DateTimeFormatter.ofPattern("MMM d")

    Canvas(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)) {
        val left = 44.dp.toPx(); val right = 8.dp.toPx(); val top = 8.dp.toPx(); val bottom = 22.dp.toPx()
        val t0 = points.minOf { it.first }
        var t1 = points.maxOf { it.first }
        if (t1 == t0) t1 = t0 + 1
        var v0 = points.minOf { it.second }
        var v1 = points.maxOf { it.second }
        val pad = ((v1 - v0) * 0.15).takeIf { it > 0 } ?: 1.0
        v0 -= pad; v1 += pad
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0).toFloat() * (size.width - left - right)
        fun y(v: Double) = top + (1f - ((v - v0) / (v1 - v0)).toFloat()) * (size.height - top - bottom)

        val paint = android.graphics.Paint().apply {
            this.color = labelColor; textSize = textPx; isAntiAlias = true
        }
        val native = drawContext.canvas.nativeCanvas
        for (i in 0..3) {
            val v = v0 + (v1 - v0) * i / 3
            val yy = y(v)
            drawLine(gridColor, Offset(left, yy), Offset(size.width - right, yy), 1.dp.toPx())
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            native.drawText(Math.round(v).toString(), left - 6.dp.toPx(), yy + textPx / 3, paint)
        }
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second))
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEach { drawCircle(color, 3.5.dp.toPx(), Offset(x(it.first), y(it.second))) }

        paint.textAlign = android.graphics.Paint.Align.LEFT
        native.drawText(LocalDate.ofEpochDay(t0).format(dateFmt), left, size.height - 4.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        native.drawText(LocalDate.ofEpochDay(t1).format(dateFmt), size.width - right, size.height - 4.dp.toPx(), paint)
    }
}
