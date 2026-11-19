package vn.edu.mobileoptimization.filework

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class FileResult(
    val size: Int,
    val valid: Long,
    val invalid: Long,
    val inventoryValue: Long,
    val categories: Int,
    val sha256: String,
)

/** Both demo modes call exactly these same reading and processing functions. */
object FileProcessor {
    const val MAX_BYTES = 64 * 1024 * 1024

    fun read(input: InputStream, checkCancelled: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_BYTES) {
                throw IllegalArgumentException("File vượt giới hạn 64 MiB của demo.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun process(bytes: ByteArray, checkCancelled: () -> Unit): FileResult {
        val hash = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < bytes.size) {
            checkCancelled()
            val length = minOf(64 * 1024, bytes.size - offset)
            hash.update(bytes, offset, length)
            offset += length
        }
        val hex = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = CsvReader(PushbackReader(BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), decoder), 64 * 1024), 1), checkCancelled)
        val header = reader.next()?.mapIndexed { index, value ->
            if (index == 0) value.removePrefix("\uFEFF").trim() else value.trim()
        }
        require(header == listOf("id", "ten", "danh_muc", "gia", "so_luong")) {
            "Tiêu đề CSV phải là: id,ten,danh_muc,gia,so_luong"
        }
        var valid = 0L
        var invalid = 0L
        var inventoryValue = 0L
        val categories = HashSet<String>()
        while (true) {
            val row = reader.next() ?: break
            if (row.size == 1 && row[0].isBlank()) continue
            if ((valid + invalid) % 512L == 0L) checkCancelled()
            if (row.size != 5) { invalid++; continue }
            val id = row[0].trim().toLongOrNull()
            val name = row[1].trim()
            val category = row[2].trim()
            val price = row[3].trim().toLongOrNull()
            val quantity = row[4].trim().toLongOrNull()
            if (id == null || id <= 0 || name.isBlank() || category.isBlank() ||
                price == null || price <= 0 || quantity == null || quantity < 0) {
                invalid++
                continue
            }
            val newTotal = try {
                Math.addExact(inventoryValue, Math.multiplyExact(price, quantity))
            } catch (_: ArithmeticException) {
                invalid++
                continue
            }
            inventoryValue = newTotal
            categories.add(category)
            valid++
        }
        checkCancelled()
        return FileResult(bytes.size, valid, invalid, inventoryValue, categories.size, hex)
    }
}

/** CSV with comma, quotes/escaped quotes and quoted line breaks; fields are bounded. */
private class CsvReader(private val input: PushbackReader, private val checkCancelled: () -> Unit) {
    fun next(): List<String>? {
        val fields = ArrayList<String>(5)
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var atStart = true
        var seen = false
        var count = 0
        fun addField() {
            require(fields.size < 100) { "Một dòng CSV có quá nhiều cột." }
            fields.add(field.toString())
            field.setLength(0)
            atStart = true
            closedQuote = false
        }
        while (true) {
            if (++count % 8192 == 0) checkCancelled()
            val value = input.read()
            if (value < 0) {
                require(!quoted) { "CSV có dấu ngoặc kép chưa đóng." }
                if (!seen && fields.isEmpty() && field.isEmpty()) return null
                addField()
                return fields
            }
            seen = true
            val ch = value.toChar()
            if (quoted) {
                if (ch == '"') {
                    val next = input.read()
                    if (next == '"'.code) field.append('"')
                    else {
                        quoted = false
                        closedQuote = true
                        if (next >= 0) input.unread(next)
                    }
                } else field.append(ch)
            } else {
                when (ch) {
                    '"' -> {
                        require(atStart && !closedQuote) { "CSV có dấu ngoặc kép không hợp lệ." }
                        quoted = true
                        atStart = false
                    }
                    ',' -> addField()
                    '\n' -> { addField(); return fields }
                    '\r' -> {
                        val next = input.read()
                        if (next >= 0 && next != '\n'.code) input.unread(next)
                        addField(); return fields
                    }
                    else -> {
                        require(!closedQuote) { "CSV có dữ liệu sau dấu ngoặc kép kết thúc." }
                        field.append(ch)
                        atStart = false
                    }
                }
            }
            require(field.length <= 4096) { "Một trường CSV dài hơn 4096 ký tự." }
        }
    }
}
