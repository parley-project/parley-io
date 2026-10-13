/**
 * <PRE>
 * 
 * Copyright 1998-2026 <A href="http://bringardner.us/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 */
package us.bringardner.parley.io;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * A byte array with a read position and a write position, for binary protocols
 * (DNS messages, SSH packets...). Numbers are big endian (network order).
 * <p>
 * <b>Reading</b> is bounds checked: a read past the readable end throws
 * {@link #malformed(String)} (a {@link WireFormatException} unless a subclass makes
 * it something else) before anything is read or allocated, so a length taken from
 * the data can't cause an ArrayIndexOutOfBoundsException or a huge allocation.
 * The readable end is the write position, unless the buffer was made over a part
 * of an array ({@link #WireBuffer(byte[], int, int)}) or a limit is set:
 * {@link #pushLimit(int)} confines reading to the next n bytes, e.g. to a record
 * whose length is in its header, and {@link #popLimit(int)} checks that they were
 * all used.
 * <p>
 * <b>Writing</b> appends at the write position, growing the array as needed;
 * {@link #setU16(int, int)} / {@link #setU32(int, long)} fill in a length once the
 * data it covers has been written.
 * <p>
 * There are no absolute-offset reads beyond {@link #readPosition(int)}: following
 * pointers into the data (DNS name compression) is the protocol's business.
 */
public class WireBuffer {

	/** The largest array a WireBuffer grows to. */
	public static final int MAX_CAPACITY = Integer.MAX_VALUE-16;

	private byte[] data;
	private int rpos;
	private int wpos;
	//  End of the readable data; -1: the write position
	private int limit = -1;

	/** An empty buffer for writing (256 bytes to start with). */
	public WireBuffer() {
		this(256);
	}

	/** An empty buffer for writing. */
	public WireBuffer(int capacity) {
		data = new byte[Math.max(16, capacity)];
	}

	/** A buffer for reading all of data (not copied). */
	public WireBuffer(byte[] data) {
		this(data, 0, data.length);
	}

	/**
	 * A buffer for reading data[off..off+len) (not copied). Reads stop at off+len;
	 * writes go after it.
	 */
	public WireBuffer(byte[] data, int off, int len) {
		if( off < 0 || len < 0 || off+len > data.length || off+len < 0 ) {
			throw new IndexOutOfBoundsException("off="+off+" len="+len+" length="+data.length);
		}
		this.data = data;
		this.rpos = off;
		this.wpos = off+len;
	}

	/**
	 * A buffer over an array with the positions given (for subclasses that read one
	 * part of an array and write another).
	 *
	 * @param readEnd where reading stops, -1 for the write position
	 */
	protected WireBuffer(byte[] data, int readPosition, int writePosition, int readEnd) {
		if( readPosition < 0 || writePosition < 0 || writePosition > data.length || readEnd > data.length ) {
			throw new IndexOutOfBoundsException("read="+readPosition+" write="+writePosition+" end="+readEnd+" length="+data.length);
		}
		this.data = data;
		this.rpos = readPosition;
		this.wpos = writePosition;
		this.limit = readEnd < 0 ? -1 : readEnd;
	}

	/**
	 * A buffer for reading the bytes from position to limit (not copied if the
	 * ByteBuffer has an accessible array). The ByteBuffer is not changed.
	 */
	public static WireBuffer wrap(ByteBuffer in) {
		if( in.hasArray() ) {
			return new WireBuffer(in.array(), in.arrayOffset()+in.position(), in.remaining());
		}
		byte[] tmp = new byte[in.remaining()];
		in.duplicate().get(tmp);
		return new WireBuffer(tmp);
	}

	// ------------------------------------------------------------------ errors

	/**
	 * Make the exception for malformed data. Override to throw a protocol's own
	 * (unchecked) exception.
	 */
	protected RuntimeException malformed(String message) {
		return new WireFormatException(message);
	}

	// ------------------------------------------------------------------ positions

	/** @return the backing array (it is replaced when a write needs more room) */
	public byte[] array() {
		return data;
	}

	public int readPosition() {
		return rpos;
	}

	/**
	 * Move the read position (anywhere up to the readable end).
	 * @throws IndexOutOfBoundsException if pos is outside the data
	 */
	public void readPosition(int pos) {
		if( pos < 0 || pos > readEnd() ) {
			throw new IndexOutOfBoundsException("read position "+pos+", readable end "+readEnd());
		}
		rpos = pos;
	}

	public int writePosition() {
		return wpos;
	}

	/** @return where reading stops: the limit if one is set, else the write position */
	public int readEnd() {
		return limit >= 0 ? limit : wpos;
	}

	/** @return bytes left to read (0 if the read position is past the end) */
	public int remaining() {
		return Math.max(0, readEnd()-rpos);
	}

	/**
	 * Confine reading to the next len bytes.
	 * @return the previous limit, to pass to {@link #popLimit(int)} or {@link #restoreLimit(int)}
	 * @throws RuntimeException ({@link #malformed}) if fewer than len bytes are left
	 */
	public int pushLimit(int len) {
		need(len);
		int old = limit;
		limit = rpos+len;
		return old;
	}

	/**
	 * End a {@link #pushLimit(int)}: every byte up to the limit must have been read.
	 * @param previous what pushLimit returned
	 * @throws RuntimeException ({@link #malformed}) if bytes are left before the limit
	 */
	public void popLimit(int previous) {
		int left = remaining();
		restoreLimit(previous);
		if( left > 0 ) {
			throw malformed(left+" unused bytes at offset "+rpos);
		}
	}

	/** End a {@link #pushLimit(int)} without checking that everything was read. */
	public void restoreLimit(int previous) {
		limit = previous;
	}

	/**
	 * @return a buffer for reading the next len bytes (sharing the array); this
	 *  buffer's read position moves past them
	 */
	public WireBuffer slice(int len) {
		need(len);
		WireBuffer ret = new WireBuffer(data, rpos, len);
		rpos += len;
		return ret;
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Check that len more bytes can be read.
	 * @throws RuntimeException ({@link #malformed}) if not
	 */
	public void need(int len) {
		if( len < 0 || len > readEnd()-rpos ) {
			throw malformed("Need "+(len & 0xffffffffL)+" bytes at offset "+rpos+", "+remaining()+" left");
		}
	}

	public int getU8() {
		need(1);
		return data[rpos++] & 0xff;
	}

	public int getU16() {
		need(2);
		int ret = ((data[rpos] & 0xff) << 8) | (data[rpos+1] & 0xff);
		rpos += 2;
		return ret;
	}

	/** @return an unsigned 32 bit number as a long (0 to 2^32-1) */
	public long getU32() {
		need(4);
		long ret = ((data[rpos] & 0xffL) << 24) | ((data[rpos+1] & 0xffL) << 16)
				| ((data[rpos+2] & 0xffL) << 8) | (data[rpos+3] & 0xffL);
		rpos += 4;
		return ret;
	}

	/** @return 32 bits as an int (a value of 2^31 or more is negative) */
	public int getInt32() {
		return (int) getU32();
	}

	/** @return 64 bits as a long */
	public long getInt64() {
		return (getU32() << 32) | getU32();
	}

	/** @return a copy of the next len bytes */
	public byte[] getBytes(int len) {
		need(len);
		byte[] ret = Arrays.copyOfRange(data, rpos, rpos+len);
		rpos += len;
		return ret;
	}

	/** Read exactly dst.length bytes into dst. */
	public void getBytes(byte[] dst) {
		need(dst.length);
		System.arraycopy(data, rpos, dst, 0, dst.length);
		rpos += dst.length;
	}

	/** @return a copy of the bytes left to read; the read position moves to the end */
	public byte[] getRemaining() {
		return getBytes(remaining());
	}

	/** @return bytes preceded by a 1 byte length (a DNS character-string) */
	public byte[] getBytes8() {
		return getBytes(getU8());
	}

	/** @return bytes preceded by a 2 byte length */
	public byte[] getBytes16() {
		return getBytes(getU16());
	}

	/**
	 * @return bytes preceded by a 4 byte length (an SSH string); the length is checked
	 *  against what is left before anything is allocated
	 */
	public byte[] getBytes32() {
		long len = getU32();
		if( len > remaining() ) {
			rpos -= 4;
			throw malformed("A length of "+len+" at offset "+rpos+", "+(remaining()-4)+" bytes left");
		}
		return getBytes((int) len);
	}

	public String getString(int len, Charset cs) {
		need(len);
		String ret = new String(data, rpos, len, cs);
		rpos += len;
		return ret;
	}

	public void skip(int len) {
		need(len);
		rpos += len;
	}

	/** @return the byte at the read position without reading it */
	public int peekU8() {
		need(1);
		return data[rpos] & 0xff;
	}

	// ------------------------------------------------------------------ writing

	/** Make room for len more bytes at the write position. */
	protected void ensure(int len) {
		if( len < 0 ) {
			throw new IllegalArgumentException("length "+len);
		}
		if( (long) wpos+len > data.length ) {
			long size = Math.max((long) data.length*2, (long) wpos+len);
			if( (long) wpos+len > MAX_CAPACITY ) {
				throw new IllegalStateException("WireBuffer too large");
			}
			data = Arrays.copyOf(data, (int) Math.min(size, MAX_CAPACITY));
		}
	}

	public WireBuffer putU8(int b) {
		ensure(1);
		data[wpos++] = (byte) b;
		return this;
	}

	/**
	 * @param v 0 to 65535; larger values are refused (they wouldn't read back)
	 */
	public WireBuffer putU16(int v) {
		checkU16(v);
		ensure(2);
		data[wpos++] = (byte) (v >> 8);
		data[wpos++] = (byte) v;
		return this;
	}

	/** Write the low 16 bits of v, whatever its value (a 16 bit field from a short or a signed int). */
	public WireBuffer put16(int v) {
		ensure(2);
		data[wpos++] = (byte) (v >> 8);
		data[wpos++] = (byte) v;
		return this;
	}

	/** Write the low 32 bits of v (an unsigned 32 bit number, or an int). */
	public WireBuffer putU32(long v) {
		ensure(4);
		data[wpos++] = (byte) (v >> 24);
		data[wpos++] = (byte) (v >> 16);
		data[wpos++] = (byte) (v >> 8);
		data[wpos++] = (byte) v;
		return this;
	}

	public WireBuffer putInt64(long v) {
		putU32(v >>> 32);
		return putU32(v);
	}

	public WireBuffer putBytes(byte[] b) {
		return putBytes(b, 0, b.length);
	}

	public WireBuffer putBytes(byte[] b, int off, int len) {
		if( off < 0 || len < 0 || off+len > b.length || off+len < 0 ) {
			throw new IndexOutOfBoundsException("off="+off+" len="+len+" length="+b.length);
		}
		ensure(len);
		System.arraycopy(b, off, data, wpos, len);
		wpos += len;
		return this;
	}

	/** Write b with a 1 byte length in front; b must be at most 255 bytes. */
	public WireBuffer putBytes8(byte[] b) {
		if( b.length > 0xff ) {
			throw new IllegalArgumentException(b.length+" bytes don't fit a 1 byte length");
		}
		putU8(b.length);
		return putBytes(b);
	}

	/** Write b with a 2 byte length in front; b must be at most 65535 bytes. */
	public WireBuffer putBytes16(byte[] b) {
		putU16(b.length);
		return putBytes(b);
	}

	/** Write b with a 4 byte length in front. */
	public WireBuffer putBytes32(byte[] b) {
		return putBytes32(b, 0, b.length);
	}

	public WireBuffer putBytes32(byte[] b, int off, int len) {
		putU32(len);
		return putBytes(b, off, len);
	}

	/**
	 * Replace two bytes already written, e.g. a length once the data it covers is written.
	 * @param pos where they are, before the write position
	 * @param v 0 to 65535
	 */
	public void setU16(int pos, int v) {
		checkU16(v);
		checkWritten(pos, 2);
		data[pos] = (byte) (v >> 8);
		data[pos+1] = (byte) v;
	}

	/** Replace four bytes already written (the low 32 bits of v). */
	public void setU32(int pos, long v) {
		checkWritten(pos, 4);
		data[pos] = (byte) (v >> 24);
		data[pos+1] = (byte) (v >> 16);
		data[pos+2] = (byte) (v >> 8);
		data[pos+3] = (byte) v;
	}

	private void checkWritten(int pos, int len) {
		if( pos < 0 || pos+len > wpos ) {
			throw new IndexOutOfBoundsException("position "+pos+", "+wpos+" bytes written");
		}
	}

	private static void checkU16(int v) {
		if( v < 0 || v > 0xffff ) {
			throw new IllegalArgumentException(v+" doesn't fit 16 bits");
		}
	}

	/**
	 * Move the write position back (to drop what was written after pos), or forward
	 * over bytes the array already holds.
	 */
	public void writePosition(int pos) {
		if( pos < 0 || pos > data.length ) {
			throw new IndexOutOfBoundsException("write position "+pos+", capacity "+data.length);
		}
		wpos = pos;
	}

	// ------------------------------------------------------------------ copies

	/** @return a copy of the bytes from the read position to the readable end */
	public byte[] toByteArray() {
		return Arrays.copyOfRange(data, rpos, Math.max(rpos, readEnd()));
	}

	/** @return a copy of bytes [from, to) of the array, which must have been written */
	public byte[] copyOf(int from, int to) {
		if( from < 0 || to < from || to > Math.max(wpos, readEnd()) ) {
			throw new IndexOutOfBoundsException("from="+from+" to="+to);
		}
		return Arrays.copyOfRange(data, from, to);
	}

	/** @return the unread bytes as a ByteBuffer (sharing the array) */
	public ByteBuffer toByteBuffer() {
		return ByteBuffer.wrap(data, rpos, remaining());
	}

	@Override
	public String toString() {
		return getClass().getSimpleName()+"[read="+rpos+", end="+readEnd()+", write="+wpos+", capacity="+data.length+"]";
	}
}
