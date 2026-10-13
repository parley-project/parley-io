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
}
