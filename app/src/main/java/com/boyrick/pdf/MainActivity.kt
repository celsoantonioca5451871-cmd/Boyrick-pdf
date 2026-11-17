package com.boyrick.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

private val Black = Color(0xFF05070D)
private val Panel = Color(0xFF0D141F)
private val Cyan = Color(0xFF00E5FF)
private val Muted = Color(0xFF7C8DA6)
private val GridLine = Color(0xFF1B2735)

sealed class Doc
class PdfDoc(val r: PdfRenderer, val fd: ParcelFileDescriptor) : Doc()
class TextDoc(val lines: List<String>) : Doc()
class SheetDoc(val sheets: List<Pair<String, List<List<String>>>>) : Doc()
class Unsupported(val msg: String) : Doc()

// ---------- Leitura de ficheiros ----------

private fun parser(b: ByteArray): XmlPullParser =
    Xml.newPullParser().apply { setInput(b.inputStream(), null) }

private fun XmlPullParser.local(): String = (name ?: "").substringAfter(':')

private fun readZip(ctx: Context, uri: Uri, want: (String) -> Boolean): Map<String, ByteArray> {
    val m = linkedMapOf<String, ByteArray>()
    ctx.contentResolver.openInputStream(uri)!!.use { ins ->
        ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (want(e.name)) m[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
    }
    return m
}

// Serve para DOCX (w:p / w:t) e PPTX (a:p / a:t)
private fun parseParas(b: ByteArray): List<String> {
    val p = parser(b)
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inT = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.local()) {
                "t" -> inT = true
                "tab" -> sb.append("    ")
                "br" -> sb.append('\n')
            }
            XmlPullParser.TEXT -> if (inT) sb.append(p.text)
            XmlPullParser.END_TAG -> when (p.local()) {
                "t" -> inT = false
                "p" -> { out.add(sb.toString()); sb.setLength(0) }
            }
        }
        ev = p.next()
    }
    return out
}

private fun parseShared(b: ByteArray): List<String> {
    val p = parser(b)
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inT = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> if (p.local() == "t") inT = true
            XmlPullParser.TEXT -> if (inT) sb.append(p.text)
            XmlPullParser.END_TAG -> when (p.local()) {
                "t" -> inT = false
                "si" -> { out.add(sb.toString()); sb.setLength(0) }
            }
        }
        ev = p.next()
    }
    return out
}

private fun colIndex(ref: String?): Int {
    val letters = (ref ?: "").takeWhile { it.isLetter() }
    if (letters.isEmpty()) return 0
    return letters.fold(0) { a, c -> a * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
}

private fun parseSheet(b: ByteArray, shared: List<String>): List<List<String>> {
    val p = parser(b)
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var type = ""
    var col = 0
    var inV = false
    val v = StringBuilder()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT && rows.size < 3000) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.local()) {
                "row" -> row = mutableListOf()
                "c" -> {
                    type = p.getAttributeValue(null, "t") ?: ""
                    col = colIndex(p.getAttributeValue(null, "r"))
                    v.setLength(0)
                }
                "v", "t" -> inV = true
            }
            XmlPullParser.TEXT -> if (inV) v.append(p.text)
            XmlPullParser.END_TAG -> when (p.local()) {
                "v", "t" -> inV = false
                "c" -> {
                    val s = if (type == "s") shared.getOrNull(v.toString().trim().toIntOrNull() ?: -1) ?: "" else v.toString()
                    while (row.size < col) row.add("")
                    row.add(s)
                }
                "row" -> rows.add(row)
            }
        }
        ev = p.next()
    }
    return rows
}

private fun readText(ctx: Context, uri: Uri): List<String> {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    return String(bytes.copyOf(minOf(bytes.size, 5_000_000)), Charsets.UTF_8).lines()
}

private fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: "ficheiro"
}

private fun load(ctx: Context, uri: Uri, name: String): Doc {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> {
            val fd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Sem acesso ao ficheiro")
            try {
                PdfDoc(PdfRenderer(fd), fd)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
        "docx" -> {
            val m = readZip(ctx, uri) { it == "word/document.xml" }
            TextDoc(m["word/document.xml"]?.let { parseParas(it) } ?: emptyList())
        }
        "pptx" -> {
            val m = readZip(ctx, uri) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            val out = mutableListOf<String>()
            var n = 1
            m.toSortedMap(compareBy<String> { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 })
                .values.forEach { bytes ->
                    out.add("— Slide $n —")
                    out.addAll(parseParas(bytes).filter { s -> s.isNotBlank() })
                    out.add("")
                    n++
                }
            TextDoc(out)
        }
        "xlsx" -> {
            val m = readZip(ctx, uri) {
                it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
            }
            val shared = m["xl/sharedStrings.xml"]?.let { parseShared(it) } ?: emptyList()
            val sheets = m.filterKeys { it != "xl/sharedStrings.xml" }
                .toSortedMap(compareBy<String> { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 })
                .entries.mapIndexed { i, e -> "Folha ${i + 1}" to parseSheet(e.value, shared) }
            if (sheets.isEmpty()) Unsupported("Folha de cálculo vazia.") else SheetDoc(sheets)
        }
        "csv" -> SheetDoc(listOf("CSV" to readText(ctx, uri).filter { it.isNotBlank() }.map { it.split(',', ';') }))
        "txt", "md", "json", "xml", "log", "html" -> TextDoc(readText(ctx, uri))
        else -> Unsupported("Formato não suportado.\nUse PDF, DOCX, XLSX, PPTX, CSV ou TXT.")
    }
}

private fun closeDoc(d: Doc?) {
    if (d is PdfDoc) {
        try { d.r.close(); d.fd.close() } catch (e: Exception) { }
    }
}

// ---------- Recentes ----------

private fun getRecents(ctx: Context): List<Pair<String, String>> =
    ctx.getSharedPreferences("boyrick", Context.MODE_PRIVATE).getString("recent", "")!!
        .split("\n").filter { it.contains("|||") }
        .map { it.substringBefore("|||") to it.substringAfter("|||") }

private fun addRecent(ctx: Context, uri: String, name: String) {
    val list = (listOf(uri to name) + getRecents(ctx).filter { it.first != uri }).take(8)
    ctx.getSharedPreferences("boyrick", Context.MODE_PRIVATE).edit()
        .putString("recent", list.joinToString("\n") { "${it.first}|||${it.second}" }).apply()
}

// ---------- Ecrãs ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Black, surface = Panel)) {
                App(initial)
            }
        }
    }
}

@Composable
fun App(initial: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(getRecents(ctx)) }

    fun open(uri: Uri) {
        scope.launch {
            busy = true
            try {
                val name = displayName(ctx, uri)
                val d = withContext(Dispatchers.IO) { load(ctx, uri, name) }
                closeDoc(doc)
                doc = d
                title = name
                addRecent(ctx, uri.toString(), name)
                recents = getRecents(ctx)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Não foi possível abrir: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            open(uri)
        }
    }

    LaunchedEffect(Unit) { initial?.let { open(it) } }
    BackHandler(enabled = doc != null) { closeDoc(doc); doc = null }

    val d = doc
    if (d == null) {
        Home(recents, busy, onPick = { picker.launch(arrayOf("*/*")) }, onRecent = { open(Uri.parse(it)) })
    } else {
        Viewer(title, d) { closeDoc(doc); doc = null }
    }
}

@Composable
fun LogoB(size: Dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier.size(size).clip(shape).background(Black)
            .border(3.dp, Brush.linearGradient(listOf(Cyan, Color(0xFF0077FF))), shape),
        contentAlignment = Alignment.Center
    ) {
        Text("B", color = Cyan, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun Tag(t: String) {
    Text(
        t, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun Home(
    recents: List<Pair<String, String>>,
    busy: Boolean,
    onPick: () -> Unit,
    onRecent: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Black)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Cyan.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(size.width / 2f, 0f), radius = size.width
                    )
                )
            }
            .statusBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        LogoB(96.dp)
        Spacer(Modifier.height(16.dp))
        Text("BOYRICK PDF", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Text("Leitor de documentos", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("PDF", "DOCX", "XLSX", "PPTX", "TXT", "CSV").forEach { Tag(it) }
        }
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Cyan, Color(0xFF0077FF))))
                .clickable(enabled = !busy) { onPick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (busy) "A ABRIR…" else "ABRIR FICHEIRO",
                color = Black, fontWeight = FontWeight.Black, letterSpacing = 2.sp
            )
        }
        Spacer(Modifier.height(26.dp))
        Text("RECENTES", color = Cyan, fontSize = 12.sp, letterSpacing = 3.sp, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(8.dp))
        if (recents.isEmpty()) {
            Text("Nenhum ficheiro aberto ainda.", color = Muted, modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recents) { r ->
                val ext = r.second.substringAfterLast('.', "?").uppercase().take(4)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .clickable { onRecent(r.first) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Cyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Text(ext, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Text(r.second, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Viewer(title: String, doc: Doc, onBack: () -> Unit) {
    var night by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Cyan, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 10.dp))
            Text(
                title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (doc is PdfDoc) {
                Text(
                    "NOITE", color = if (night) Black else Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (night) Cyan else Color.Transparent)
                        .border(1.dp, Cyan, RoundedCornerShape(50))
                        .clickable { night = !night }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        when (doc) {
            is PdfDoc -> PdfView(doc, night)
            is TextDoc -> TextView(doc)
            is SheetDoc -> SheetView(doc)
            is Unsupported -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(doc.msg, color = Muted, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
fun PdfView(doc: PdfDoc, night: Boolean) {
    val count = doc.r.pageCount
    val state = rememberLazyListState()
    val invert = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(count) { i ->
                val bmp by produceState<Bitmap?>(null, i) {
                    value = withContext(Dispatchers.IO) {
                        synchronized(doc.r) {
                            val page = doc.r.openPage(i)
                            try {
                                val w = 1080
                                val h = (w.toFloat() * page.height / page.width).toInt()
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(android.graphics.Color.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(), contentDescription = null,
                        colorFilter = if (night) invert else null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).clip(RoundedCornerShape(6.dp)).background(Panel))
                }
            }
        }
        Text(
            "${state.firstVisibleItemIndex + 1} / $count", color = Cyan, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .clip(RoundedCornerShape(50)).background(Panel)
                .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun TextView(doc: TextDoc) {
    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        items(doc.lines) { line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(8.dp))
            } else {
                Text(line, color = Color(0xFFE6EEF7), fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
    }
}

@Composable
fun Cell(t: String, w: Dp, c: Color, bold: Boolean) {
    Box(Modifier.width(w).border(0.5.dp, GridLine).padding(6.dp)) {
        Text(
            t, color = c, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun SheetView(doc: SheetDoc) {
    var tab by remember { mutableStateOf(0) }
    val rows = doc.sheets[tab].second
    Column(Modifier.fillMaxSize()) {
        if (doc.sheets.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                doc.sheets.forEachIndexed { i, s ->
                    Text(
                        s.first, color = if (i == tab) Black else Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (i == tab) Cyan else Panel)
                            .clickable { tab = i }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn {
                itemsIndexed(rows) { i, r ->
                    Row {
                        Cell("${i + 1}", 44.dp, Muted, false)
                        r.forEach { Cell(it, 120.dp, if (i == 0) Cyan else Color(0xFFE6EEF7), i == 0) }
                    }
                }
            }
        }
    }
}
