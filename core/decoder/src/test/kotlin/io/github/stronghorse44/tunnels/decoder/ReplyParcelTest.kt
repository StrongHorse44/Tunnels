package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies as a sealed decoder could write them; the host reads ints only and never acts on an exception header. */
class ReplyParcelTest {
    private class Ints(vararg values: Int) : IntReader {
        private val v = values
        var read = 0
            private set

        override fun available(): Int = (v.size - read) * 4

        override fun readInt(): Int = v[read++]
    }

    private val reply = intArrayOf(0, 400, 300, 1600, 0, 1, 0)

    /** Parcel's exception codes and reply headers a service can write instead of 0. */
    private val headers = listOf(
        -1, // EX_SECURITY
        -2, // EX_BAD_PARCELABLE
        -3, // EX_ILLEGAL_ARGUMENT
        -8, // EX_SERVICE_SPECIFIC
        -9, // EX_PARCELABLE: the host would unparcel a boot-classpath Parcelable
        -127, // EX_HAS_NOTED_APPOPS_REPLY_HEADER
        -128, // EX_HAS_STRICTMODE_REPLY_HEADER: the host would deserialize violation records
        1, Int.MIN_VALUE, Int.MAX_VALUE,
    )

    @Test fun aWellFormedDecodeReplyIsRead() {
        val r = Ints(0, 1, 32, *reply)
        assertEquals(WireReply(0, 400, 300, 1600, 0, 1, 0), ReplyParcel.decodeReply(r))
        assertEquals("nothing past the 7 ints is read", 10, r.read)
    }

    @Test fun anyExceptionCodeOrReplyHeaderIsMalformedAndNothingAfterItIsRead() {
        for (h in headers) {
            val r = Ints(h, 1, 32, *reply)
            assertNull("header $h", ReplyParcel.decodeReply(r))
            assertEquals("header $h: only the code is read", 1, r.read)
            assertNull("header $h", ReplyParcel.int(Ints(h, 7)))
            assertFalse("header $h", ReplyParcel.none(Ints(h)))
        }
    }

    @Test fun theParcelableMustBePresentAndExactlyItsSize() {
        assertNull(ReplyParcel.decodeReply(Ints(0, 0, 32, *reply)))
        assertNull(ReplyParcel.decodeReply(Ints(0, 2, 32, *reply)))
        for (size in listOf(28, 36, 0, -32, 4)) assertNull("size $size", ReplyParcel.decodeReply(Ints(0, 1, size, *reply, 0)))
    }

    @Test fun aTruncatedReplyIsMalformed() {
        assertNull(ReplyParcel.decodeReply(Ints()))
        assertNull(ReplyParcel.decodeReply(Ints(0)))
        assertNull(ReplyParcel.decodeReply(Ints(0, 1, 32, 0, 400, 300, 1600, 0, 1)))
        assertNull(ReplyParcel.int(Ints(0)))
        assertNull(ReplyParcel.int(Ints()))
        assertFalse(ReplyParcel.none(Ints()))
    }

    @Test fun leftoverDataIsIgnored() {
        val r = Ints(0, 1, 32, *reply, -9, -128, 99)
        assertEquals(WireReply(0, 400, 300, 1600, 0, 1, 0), ReplyParcel.decodeReply(r))
        assertEquals(10, r.read)
        assertEquals(42, ReplyParcel.int(Ints(0, 42, -128)))
        assertTrue(ReplyParcel.none(Ints(0, -9)))
    }

    @Test fun intAndVoidReplies() {
        assertEquals(1, ReplyParcel.int(Ints(0, 1)))
        assertEquals(-5, ReplyParcel.int(Ints(0, -5)))
        assertTrue(ReplyParcel.none(Ints(0)))
        assertEquals(32, ReplyParcel.DECODE_REPLY_SIZE)
    }
}
