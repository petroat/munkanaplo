package hu.munkanaplo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val localAppUrl = "https://appassets.androidplatform.net/assets/index.html"

    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingFieldId: String? = null
    private var isUserStopping = false

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_IMPORT_FILE = 1002
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#f0f2f5")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#f0f2f5")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#f0f2f5"))
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#f0f2f5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(webView)
        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString MunkanaploApp/2.0"
        }

        webView.addJavascriptInterface(AndroidSpeechBridge(), "AndroidSpeech")
        webView.addJavascriptInterface(AndroidExportBridge(), "AndroidExport")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (url.host != "appassets.androidplatform.net") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("Igen") { _, _ -> result?.confirm() }
                    .setNegativeButton("Mégse") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(localAppUrl)
    }

    // ==========================================
    // 1. EXPORT / IMPORT HÍD (.XLSX / .CSV / .JSON)
    // ==========================================
    inner class AndroidExportBridge {

        @JavascriptInterface
        fun exportXlsx(payloadJson: String) {
            runOnUiThread {
                try {
                    val obj = JSONObject(payloadJson)
                    val userName = obj.optString("userName", "Felhasznalo")
                    val period = obj.optString("period", "Osszes")
                    val totalTime = obj.optString("totalTime", "0 ora 0 perc")
                    val rows = obj.optJSONArray("rows") ?: JSONArray()

                    val safeUser = userName
                        .replace(Regex("[^a-zA-Z0-9áéíóöőúüűÁÉÍÓÖŐÚÜŰ_-]"), "_")
                        .replace(Regex("_+"), "_")
                        .trim('_')
                        .ifEmpty { "Munkanaplo" }
                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val fileName = "Munkanaplo_${safeUser}_$todayStr.xlsx"

                    val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                    val outFile = File(exportDir, fileName)

                    buildXlsxFile(outFile, userName, period, totalTime, rows)
                    shareFile(
                        file = outFile,
                        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        chooserTitle = "Munkanapló táblázat megosztása / mentése"
                    )
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Exportálási hiba")
                        .setMessage("Nem sikerült létrehozni az Excel fájlt: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        @JavascriptInterface
        fun exportJsonBackup(fileName: String, jsonContent: String) {
            runOnUiThread {
                try {
                    val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                    val outFile = File(exportDir, fileName)
                    outFile.writeText(jsonContent, StandardCharsets.UTF_8)
                    shareFile(
                        file = outFile,
                        mimeType = "application/json",
                        chooserTitle = "Biztonsági mentés mentése / megosztása"
                    )
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Mentési hiba")
                        .setMessage("Nem sikerült elkészíteni a mentést: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        @JavascriptInterface
        fun openImportFilePicker() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/csv",
                            "text/comma-separated-values",
                            "application/json",
                            "application/octet-stream"
                        )
                    )
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_IMPORT_FILE)
            }
        }
    }

    private fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

                if (isZip) {
                    val parsedJson = parseXlsxBytesToJson(bytes)
                    sendImportedDataToWeb("table", parsedJson)
                } else {
                    val text = String(bytes, StandardCharsets.UTF_8).trimStart('\uFEFF')
                    if (text.startsWith("{")) {
                        sendImportedDataToWeb("json", text)
                    } else {
                        sendImportedDataToWeb("csv", text)
                    }
                }
            } catch (e: Exception) {
                AlertDialog.Builder(this)
                    .setTitle("Importálási hiba")
                    .setMessage("Nem sikerült beolvasni a fájlt: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun sendImportedDataToWeb(fileType: String, payload: String) {
        val quotedType = JSONObject.quote(fileType)
        val quotedPayload = JSONObject.quote(payload)
        val js = "if (window.onAndroidFileImported) { window.onAndroidFileImported($quotedType, $quotedPayload); }"
        webView.evaluateJavascript(js, null)
    }

    // ==========================================
    // 2. OFFLINE .XLSX GENERÁLÓ (OPENXML)
    // ==========================================
    private fun buildXlsxFile(
        outFile: File,
        userName: String,
        period: String,
        totalTime: String,
        rows: JSONArray
    ) {
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Nyilvántartás" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="3">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="13"/><color rgb="FF1A73E8"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFE8F0FE"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFDADCE0"/></left>
      <right style="thin"><color rgb="FFDADCE0"/></right>
      <top style="thin"><color rgb="FFDADCE0"/></top>
      <bottom style="thin"><color rgb="FFDADCE0"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="4">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment vertical="center"/></xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<cols>""")
        sb.append("""<col min="1" max="1" width="18" customWidth="1"/>""")
        sb.append("""<col min="2" max="2" width="14" customWidth="1"/>""")
        sb.append("""<col min="3" max="3" width="13" customWidth="1"/>""")
        sb.append("""<col min="4" max="4" width="13" customWidth="1"/>""")
        sb.append("""<col min="5" max="5" width="13" customWidth="1"/>""")
        sb.append("""<col min="6" max="6" width="42" customWidth="1"/>""")
        sb.append("""<col min="7" max="7" width="32" customWidth="1"/>""")
        sb.append("""</cols><sheetData>""")

        sb.append("""<row r="1">${inlineCell("A1", "Munkanapló – $userName", 2)}</row>""")
        sb.append("""<row r="2">${inlineCell("A2", "Szűrt időszak: $period | Összesen: $totalTime", 3)}</row>""")

        val headers = listOf(
            "Munkahely",
            "Dátum",
            "Idő kezdete",
            "Idő vége",
            "Intervallum",
            "Feladat",
            "Felmerült anyagszükséglet"
        )
        sb.append("""<row r="4">""")
        headers.forEachIndexed { idx, h ->
            val col = ('A'.code + idx).toChar()
            sb.append(inlineCell("$col" + "4", h, 1))
        }
        sb.append("""</row>""")

        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val rowNum = i + 5
            val vals = listOf(
                r.optString("munkahely", ""),
                r.optString("datum", ""),
                r.optString("idoKezdete", ""),
                r.optString("idoVege", ""),
                r.optString("intervallum", ""),
                r.optString("feladat", ""),
                r.optString("anyag", "")
            )
            sb.append("""<row r="$rowNum">""")
            vals.forEachIndexed { cIdx, v ->
                val col = ('A'.code + cIdx).toChar()
                sb.append(inlineCell("$col$rowNum", v, 0))
            }
            sb.append("""</row>""")
        }

        val sumRow = rows.length() + 6
        sb.append("""<row r="$sumRow">""")
        sb.append(inlineCell("A$sumRow", "Összesen:", 1))
        sb.append(inlineCell("B$sumRow", "${rows.length()} bejegyzés", 1))
        sb.append(inlineCell("C$sumRow", "", 1))
        sb.append(inlineCell("D$sumRow", "", 1))
        sb.append(inlineCell("E$sumRow", totalTime, 1))
        sb.append(inlineCell("F$sumRow", "", 1))
        sb.append(inlineCell("G$sumRow", "", 1))
        sb.append("""</row>""")

        sb.append("""</sheetData></worksheet>""")

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            addZipEntry(zip, "[Content_Types].xml", contentTypes)
            addZipEntry(zip, "_rels/.rels", rootRels)
            addZipEntry(zip, "xl/workbook.xml", workbookXml)
            addZipEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels)
            addZipEntry(zip, "xl/styles.xml", stylesXml)
            addZipEntry(zip, "xl/worksheets/sheet1.xml", sb.toString())
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun inlineCell(ref: String, value: String, styleIdx: Int): String {
        val escaped = escapeXml(value)
        return """<c r="$ref" s="$styleIdx" t="inlineStr"><is><t xml:space="preserve">$escaped</t></is></c>"""
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ==========================================
    // 3. OFFLINE .XLSX BEOLVASÓ (IMPORTÁLÁSHOZ)
    // ==========================================
    private fun parseXlsxBytesToJson(bytes: ByteArray): String {
        val zipEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = mutableListOf<String>()
        zipEntries["xl/sharedStrings.xml"]?.let { ssBytes ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(ssBytes))
            val siNodes = doc.getElementsByTagName("si")
            for (i in 0 until siNodes.length) {
                val siEl = siNodes.item(i) as Element
                val tNodes = siEl.getElementsByTagName("t")
                val sb = StringBuilder()
                for (j in 0 until tNodes.length) {
                    sb.append(tNodes.item(j).textContent ?: "")
                }
                sharedStrings.add(sb.toString())
            }
        }

        val sheetPaths = zipEntries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()

        val workplacesArray = JSONArray()
        val entriesArray = JSONArray()

        for (path in sheetPaths) {
            val sheetBytes = zipEntries[path] ?: continue
            val rows = parseWorksheetRows(sheetBytes, sharedStrings)
            if (rows.isEmpty()) continue

            val firstRow = rows[0]
            if (firstRow.size == 1 && firstRow[0].trim().equals("Munkahelyek", ignoreCase = true)) {
                for (rIdx in 1 until rows.size) {
                    val wp = rows[rIdx].firstOrNull()?.trim().orEmpty()
                    if (wp.isNotEmpty()) workplacesArray.put(wp)
                }
                continue
            }

            for (rIdx in 1 until rows.size) {
                val r = rows[rIdx]
                if (r.size < 6) continue
                val wp = r.getOrElse(0) { "" }.trim()
                val rawDate = r.getOrElse(1) { "" }.trim()
                val rawStart = r.getOrElse(2) { "" }.trim()
                val rawEnd = r.getOrElse(3) { "" }.trim()
                val rawDur = r.getOrElse(4) { "" }.trim()
                val task = r.getOrElse(5) { "" }.trim()
                val mat = r.getOrElse(6) { "" }.trim()

                if (wp.equals("Összesen:", ignoreCase = true) || wp.isEmpty() || task.isEmpty()) continue

                val entryObj = JSONObject().apply {
                    put("munkahely", wp)
                    put("datum", convertExcelDate(rawDate))
                    put("idoKezdete", convertExcelTime(rawStart))
                    put("idoVege", convertExcelTime(rawEnd))
                    put("intervallum", convertExcelTime(rawDur))
                    put("feladat", task)
                    put("anyag", mat)
                }
                entriesArray.put(entryObj)
            }
        }

        return JSONObject().apply {
            put("workplaces", workplacesArray)
            put("entries", entriesArray)
        }.toString()
    }

    private fun parseWorksheetRows(
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(sheetBytes))
        val rowNodes = doc.getElementsByTagName("row")
        val result = mutableListOf<List<String>>()

        for (i in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(i) as Element
            val cellNodes = rowEl.getElementsByTagName("c")
            val cellMap = mutableMapOf<Int, String>()
            var maxCol = -1

            for (j in 0 until cellNodes.length) {
                val cEl = cellNodes.item(j) as Element
                val ref = cEl.getAttribute("r")
                val colIdx = colRefToIndex(ref)
                if (colIdx > maxCol) maxCol = colIdx

                val type = cEl.getAttribute("t")
                val value = when (type) {
                    "s" -> {
                        val vText = cEl.getElementsByTagName("v").item(0)?.textContent?.trim()
                        val idx = vText?.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "inlineStr" -> {
                        val tNodes = cEl.getElementsByTagName("t")
                        val sb = StringBuilder()
                        for (k in 0 until tNodes.length) sb.append(tNodes.item(k).textContent ?: "")
                        sb.toString()
                    }
                    else -> {
                        cEl.getElementsByTagName("v").item(0)?.textContent?.trim().orEmpty()
                    }
                }
                if (colIdx >= 0) {
                    cellMap[colIdx] = value
                }
            }

            if (maxCol >= 0) {
                val rowList = (0..maxCol).map { c -> cellMap[c].orEmpty() }
                result.add(rowList)
            }
        }
        return result
    }

    private fun colRefToIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch.code - 'A'.code + 1)
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch.code - 'a'.code + 1)
            } else {
                break
            }
        }
        return col - 1
    }

    private fun convertExcelDate(raw: String): String {
        val num = raw.toDoubleOrNull()
        if (num != null && num > 20000.0) {
            val days = num.toLong()
            val date = LocalDate.of(1899, 12, 30).plusDays(days)
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        return raw
    }

    private fun convertExcelTime(raw: String): String {
        if (raw.contains(":")) {
            val parts = raw.split(":")
            val h = parts.getOrNull(0)?.padStart(2, '0') ?: "00"
            val m = parts.getOrNull(1)?.padStart(2, '0') ?: "00"
            return "$h:$m"
        }
        val num = raw.toDoubleOrNull()
        if (num != null && num >= 0.0) {
            val fraction = num - num.toLong()
            val totalMinutes = Math.round(fraction * 24.0 * 60.0).toInt()
            val h = (totalMinutes / 60) % 24
            val m = totalMinutes % 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }
        return raw
    }

    // ==========================================
    // 4. NATÍV MAGYAR BESZÉDFELISMERŐ
    // ==========================================
    inner class AndroidSpeechBridge {
        @JavascriptInterface
        fun startListening(fieldId: String) {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingFieldId = fieldId
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQ_RECORD_AUDIO
                    )
                    return@runOnUiThread
                }
                beginNativeRecognition()
            }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                isUserStopping = true
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingFieldId != null) {
                    pendingFieldId = null
                    beginNativeRecognition()
                }
            } else {
                pendingFieldId = null
                sendSpeechEvent(
                    event = "error",
                    message = "A mikrofon használata nincs engedélyezve az alkalmazásnak."
                )
            }
        }
    }

    private fun beginNativeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeechEvent(
                event = "error",
                message = "A készüléken nem érhető el a Google beszédfelismerő szolgáltatás."
            )
            return
        }

        isUserStopping = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    sendSpeechEvent(event = "ready")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        sendSpeechEvent(event = "result", text = text, isFinal = false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        sendSpeechEvent(event = "result", text = text, isFinal = true)
                    } else {
                        sendSpeechEvent(event = "end")
                    }
                }

                override fun onError(error: Int) {
                    if (isUserStopping && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                        sendSpeechEvent(event = "end")
                        return
                    }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Hangrögzítési hiba történt."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Nincs mikrofon engedély."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hálózati hiba a beszédfelismerés közben."
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nem érzékeltem érthető beszédet. Próbáld újra!"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "A beszédfelismerő éppen foglalt, próbáld újra!"
                        SpeechRecognizer.ERROR_SERVER -> "Szerverhiba a beszédfelismerésnél."
                        else -> ""
                    }
                    sendSpeechEvent(event = "error", message = msg)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            sendSpeechEvent(event = "error", message = "Nem sikerült elindítani a mikrofont: ${e.message}")
        }
    }

    private fun sendSpeechEvent(
        event: String,
        text: String = "",
        isFinal: Boolean = false,
        message: String = ""
    ) {
        val json = JSONObject().apply {
            put("type", "ANDROID_SPEECH")
            put("event", event)
            put("text", text)
            put("isFinal", isFinal)
            put("message", message)
        }.toString()

        val js = """
            (function() {
                var msg = $json;
                window.postMessage(msg, '*');
            })();
        """.trimIndent()

        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onDestroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
