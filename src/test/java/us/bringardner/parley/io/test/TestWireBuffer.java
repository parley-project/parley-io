package us.bringardner.parley.io.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.io.WireBuffer;
import us.bringardner.parley.io.WireFormatException;

public class TestWireBuffer {

	@Test
	public void numbersRoundTripBigEndian() {
		WireBuffer w = new WireBuffer(16);
		w.putU8(0xfe).putU16(0xbeef).putU32(0xdeadbeefL).putInt64(0x0102030405060708L).put16(-1);
		byte[] b = w.toByteArray();
		assertEquals(1+2+4+8+2, b.length);
		assertEquals((byte)0xbe, b[1]);
		WireBuffer r = new WireBuffer(b);
		assertEquals(0xfe, r.getU8());
		assertEquals(0xbeef, r.getU16());
		assertEquals(0xdeadbeefL, r.getU32());
		assertEquals(0x0102030405060708L, r.getInt64());
		assertEquals(0xffff, r.getU16());
		assertEquals(0, r.remaining());
	}

	@Test
	public void readingPastTheEndIsMalformed() {
		WireBuffer r = new WireBuffer(new byte[] {1, 2, 3});
		r.getU16();
		assertThrows(WireFormatException.class, r::getU16);
		assertEquals(2, r.readPosition(), "nothing was read");
		assertEquals(3, r.getU8());
		assertThrows(WireFormatException.class, r::getU8);
	}

	@Test
	public void aLengthLargerThanTheDataAllocatesNothing() {
		WireBuffer r = new WireBuffer(new byte[] {(byte)0xff, (byte)0xff, (byte)0xff, (byte)0xf0, 1});
		assertThrows(WireFormatException.class, r::getBytes32);
		assertEquals(0, r.readPosition());
		assertThrows(WireFormatException.class, () -> r.getBytes(-1));
	}

	@Test
	public void aPartOfAnArrayIsReadOnly() {
		byte[] a = {9, 9, 1, 2, 9};
		WireBuffer r = new WireBuffer(a, 2, 2);
		assertEquals(0x0102, r.getU16());
		assertThrows(WireFormatException.class, r::getU8);
	}

	@Test
	public void limitsConfineReading() {
		WireBuffer r = new WireBuffer(new byte[] {0, 2, 7, 8, 9});
		int len = r.getU16();
		int saved = r.pushLimit(len);
		assertEquals(2, r.remaining());
		assertEquals(7, r.getU8());
		assertEquals(8, r.getU8());
		assertThrows(WireFormatException.class, r::getU8, "the next record isn't readable");
		r.popLimit(saved);
		assertEquals(9, r.getU8());
	}

	@Test
	public void popLimitRefusesUnreadBytes() {
		WireBuffer r = new WireBuffer(new byte[] {1, 2, 3});
		int saved = r.pushLimit(2);
		r.getU8();
		assertThrows(WireFormatException.class, () -> r.popLimit(saved));
		assertEquals(2, r.remaining(), "the limit is gone after the check");
	}

	@Test
	public void pushLimitPastTheEndIsMalformed() {
		WireBuffer r = new WireBuffer(new byte[] {1, 2, 3});
		assertThrows(WireFormatException.class, () -> r.pushLimit(4));
	}

	@Test
	public void sliceSharesTheArrayAndMovesOn() {
		byte[] a = {1, 2, 3, 4};
		WireBuffer r = new WireBuffer(a);
		WireBuffer s = r.slice(2);
		assertEquals(0x0102, s.getU16());
		assertEquals(0, s.remaining());
		assertEquals(3, r.getU8());
	}

	@Test
	public void lengthPrefixedBytes() {
		WireBuffer w = new WireBuffer();
		w.putBytes8("ab".getBytes(StandardCharsets.US_ASCII)).putBytes16(new byte[] {5}).putBytes32(new byte[] {6, 7});
		WireBuffer r = new WireBuffer(w.toByteArray());
		assertEquals("ab", new String(r.getBytes8(), StandardCharsets.US_ASCII));
		assertArrayEquals(new byte[] {5}, r.getBytes16());
		assertArrayEquals(new byte[] {6, 7}, r.getBytes32());
		assertThrows(IllegalArgumentException.class, () -> w.putBytes8(new byte[256]));
	}

	@Test
	public void sixteenBitValuesMustFit() {
		WireBuffer w = new WireBuffer();
		assertThrows(IllegalArgumentException.class, () -> w.putU16(0x10000));
		assertThrows(IllegalArgumentException.class, () -> w.putU16(-1));
		assertEquals(0, w.writePosition());
	}

	@Test
	public void aLengthIsFilledInLater() {
		WireBuffer w = new WireBuffer(16);
		int at = w.writePosition();
		w.putU16(0);
		w.putBytes(new byte[300]);
		w.setU16(at, w.writePosition()-at-2);
		WireBuffer r = new WireBuffer(w.toByteArray());
		assertEquals(300, r.getU16());
		assertThrows(IndexOutOfBoundsException.class, () -> w.setU16(w.writePosition()-1, 1));
	}

	@Test
	public void writingGrowsTheArray() {
		WireBuffer w = new WireBuffer(16);
		for(int i=0; i < 1000; i++ ) {
			w.putU32(i);
		}
		assertEquals(4000, w.writePosition());
		assertTrue(w.array().length >= 4000);
	}

	@Test
	public void writtenDataCanBeReadBack() {
		WireBuffer b = new WireBuffer();
		b.putU16(42);
		assertEquals(2, b.remaining());
		assertEquals(42, b.getU16());
		assertEquals(0, b.remaining());
	}

	static final class Strict extends WireBuffer {
		Strict(byte[] b) {
			super(b);
		}
		@Override
		protected RuntimeException malformed(String message) {
			return new IllegalStateException("custom: "+message);
		}
	}

	@Test
	public void aSubclassChoosesTheException() {
		WireBuffer r = new Strict(new byte[0]);
		IllegalStateException e = assertThrows(IllegalStateException.class, r::getU8);
		assertTrue(e.getMessage().startsWith("custom: "));
	}

	static final class Sub extends WireBuffer {
		Sub(byte[] b, int r, int w, int end) {
			super(b, r, w, end);
		}
	}

	@Test
	public void readAndWritePartsOfOneArray() {
		byte[] a = {1, 2, 3, 4};
		WireBuffer b = new Sub(a, 1, 0, 3);
		assertEquals(3, b.readEnd());
		assertEquals(2, b.getU8());
		assertEquals(3, b.getU8());
		assertThrows(WireFormatException.class, b::getU8);
		b.putU8(9);
		assertEquals(9, a[0]);
		WireBuffer c = new Sub(a, 0, 2, -1);
		assertEquals(2, c.readEnd(), "-1: up to the write position");
		assertThrows(IndexOutOfBoundsException.class, () -> new Sub(a, -1, 0, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> new Sub(a, 0, 5, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> new Sub(a, 0, -1, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> new Sub(a, 0, 0, 5));
	}

	@Test
	public void badArgumentsAreRefused() {
		byte[] a = new byte[4];
		assertThrows(IndexOutOfBoundsException.class, () -> new WireBuffer(a, -1, 1));
		assertThrows(IndexOutOfBoundsException.class, () -> new WireBuffer(a, 0, -1));
		assertThrows(IndexOutOfBoundsException.class, () -> new WireBuffer(a, 2, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> new WireBuffer(a, 1, Integer.MAX_VALUE));
		WireBuffer w = new WireBuffer();
		assertThrows(IndexOutOfBoundsException.class, () -> w.putBytes(a, -1, 1));
		assertThrows(IndexOutOfBoundsException.class, () -> w.putBytes(a, 0, -1));
		assertThrows(IndexOutOfBoundsException.class, () -> w.putBytes(a, 3, 2));
		assertThrows(IndexOutOfBoundsException.class, () -> w.putBytes(a, 1, Integer.MAX_VALUE));
		assertThrows(IndexOutOfBoundsException.class, () -> w.readPosition(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> w.readPosition(1));
		assertThrows(IndexOutOfBoundsException.class, () -> w.writePosition(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> w.writePosition(100000));
		assertThrows(IndexOutOfBoundsException.class, () -> w.setU32(-1, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> w.copyOf(-1, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> w.copyOf(1, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> w.copyOf(0, 1));
	}

	@Test
	public void wrapReadsAByteBuffer() {
		java.nio.ByteBuffer heap = java.nio.ByteBuffer.wrap(new byte[] {0, 1, 2, 3});
		heap.position(1);
		WireBuffer r = WireBuffer.wrap(heap);
		assertEquals(1, r.getU8());
		assertEquals(1, heap.position(), "not changed");
		java.nio.ByteBuffer direct = java.nio.ByteBuffer.allocateDirect(2);
		direct.put((byte)7).put((byte)8).flip();
		WireBuffer d = WireBuffer.wrap(direct);
		assertEquals(0x0708, d.getU16());
		assertEquals(0, direct.position());
	}

	@Test
	public void otherReads() {
		WireBuffer w = new WireBuffer();
		w.putU32(0x80000000L).putBytes("hi".getBytes(StandardCharsets.US_ASCII)).putBytes(new byte[] {1, 2, 3, 4, 5});
		WireBuffer r = new WireBuffer(w.toByteArray());
		assertEquals(Integer.MIN_VALUE, r.getInt32());
		assertEquals("hi", r.getString(2, StandardCharsets.US_ASCII));
		assertEquals(1, r.peekU8());
		r.skip(1);
		byte[] two = new byte[2];
		r.getBytes(two);
		assertArrayEquals(new byte[] {2, 3}, two);
		assertEquals(2, r.toByteBuffer().remaining());
		assertArrayEquals(new byte[] {4, 5}, r.getRemaining());
		assertTrue(r.toString().contains("read="));
		assertThrows(WireFormatException.class, r::peekU8);
		assertThrows(WireFormatException.class, () -> r.skip(1));
		assertThrows(WireFormatException.class, () -> r.getString(1, StandardCharsets.US_ASCII));
		assertThrows(WireFormatException.class, () -> r.getBytes(new byte[1]));
		assertThrows(WireFormatException.class, () -> r.slice(1));
		assertEquals("Need 1 bytes at offset 11, 0 left", assertThrows(WireFormatException.class, r::getU8).getMessage());
	}

	@Test
	public void otherWrites() {
		WireBuffer w = new WireBuffer(16);
		w.putU32(0);
		w.setU32(0, 0xcafebabeL);
		w.putBytes32(new byte[] {9, 8, 7}, 1, 2);
		WireBuffer r = new WireBuffer(w.toByteArray());
		assertEquals(0xcafebabeL, r.getU32());
		assertArrayEquals(new byte[] {8, 7}, r.getBytes32());
		assertArrayEquals(new byte[] {(byte)0xca, (byte)0xfe}, w.copyOf(0, 2));
		w.writePosition(2);
		assertEquals(2, w.writePosition());
		assertThrows(IllegalArgumentException.class, () -> w.setU16(0, 70000));
		assertThrows(IllegalArgumentException.class, () -> w.putBytes16(new byte[70000]));
	}

	@Test
	public void restoreLimitDoesNotCheck() {
		WireBuffer r = new WireBuffer(new byte[] {1, 2, 3});
		int saved = r.pushLimit(1);
		r.restoreLimit(saved);
		assertEquals(3, r.remaining());
		assertEquals(0, new WireBuffer(new byte[] {1}).readPosition());
		WireBuffer past = new WireBuffer(new byte[] {1, 2});
		past.getU16();
		past.writePosition(0);
		assertEquals(0, past.remaining(), "never negative");
		assertEquals(0, past.toByteArray().length);
	}
}
