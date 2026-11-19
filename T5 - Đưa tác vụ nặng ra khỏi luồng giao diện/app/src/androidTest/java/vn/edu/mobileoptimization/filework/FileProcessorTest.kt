package vn.edu.mobileoptimization.filework

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class FileProcessorTest {
    @Test fun parsesUtf8QuotedFieldsAndCountsInvalidRows() {
        val csv = "\uFEFFid,ten,danh_muc,gia,so_luong\r\n" +
            "1,\"Bàn, gỗ\",Gia dụng,20000,3\r\n" +
            "2,\"Sách \"\"hay\"\"\",Sách,15000,0\r\n" +
            "3,\"Tên\nnhiều dòng\",Sách,12000,2\r\n" +
            "4,Hỏng,Sách,không phải số,1\r\n" +
            "5,Tràn,Sách,9223372036854775807,2\r\n"
        val bytes = csv.toByteArray(Charsets.UTF_8)
        val sameBytes = FileProcessor.read(ByteArrayInputStream(bytes)) { }
        val result = FileProcessor.process(sameBytes) { }
        assertEquals(3L, result.valid)
        assertEquals(2L, result.invalid)
        assertEquals(84_000L, result.inventoryValue)
        assertEquals(2, result.categories)
        assertEquals(result.sha256, FileProcessor.process(bytes) { }.sha256)
    }

    @Test fun rejectsBadHeaderAndUnclosedQuote() {
        assertThrows(IllegalArgumentException::class.java) {
            FileProcessor.process("other,columns\n".toByteArray()) { }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileProcessor.process("id,ten,danh_muc,gia,so_luong\n1,\"broken".toByteArray()) { }
        }
    }

    @Test fun cancellationIsCheckedWhileProcessingAndReading() {
        val csv = ("id,ten,danh_muc,gia,so_luong\n" + (1..3000).joinToString("") { "$it,Tên,Sách,100,1\n" }).toByteArray()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FileProcessor.process(csv) { if (++checks == 4) throw CancellationException("test") }
        }
        assertTrue(checks >= 4)
        assertThrows(CancellationException::class.java) {
            FileProcessor.read(ByteArrayInputStream(csv)) { throw CancellationException("test") }
        }
    }
}
