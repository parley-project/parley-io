package us.bringardner.parley.io.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.io.BufferedLineInput;
import us.bringardner.parley.io.IoUtils;
import us.bringardner.parley.io.LineTooLongException;

class TestBufferedLineInput {

	private static BufferedLineInput input(String s, boolean lastLine) {
		return new BufferedLineInput(new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1)), "closed in block",
				lastLine);
	}

	private static byte[] lineAfterFirst(BufferedLineInput in) throws IOException {
		in.readLine(100);
		return in.readLine(100);
	}

	private static String str(byte[] b) {
		return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
	}

	@Test
	void readsLinesWithCrlfOrLf() throws IOException {
		BufferedLineInput in = input("one\r\ntwo\nthree\r\n", false);
		assertEquals("one", str(in.readLine(100)));
		assertEquals("two", str(in.readLine(100)));
		assertEquals("three", str(in.readLine(100)));
		assertNull(in.readLine(100));
		assertTrue(in.getLastReceived() > 0);
	}

	@Test
	void lastLineWithoutEolIsReturnedOrIgnored() throws IOException {
		assertEquals("tail", str(lineAfterFirst(input("a\ntail", true))));
		BufferedLineInput in = input("a\ntail", false);
		assertEquals("a", str(in.readLine(100)));
		assertNull(in.readLine(100));
		assertNull(input("", true).readLine(100));
	}

	@Test
	void longLineIsDiscardedAndReadingContinues() throws IOException {
		BufferedLineInput in = input("0123456789\nok\n", false);
		assertThrows(LineTooLongException.class, () -> in.readLine(5));
		assertEquals("ok", str(in.readLine(5)));
	}

	@Test
	void readFullyCopiesOrDiscards() throws IOException {
		BufferedLineInput in = input("abcdefgh\nrest\n", false);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		in.readFully(3, out);
		assertEquals("abc", out.toString("ISO-8859-1"));
		in.readFully(5, null);
		assertEquals("", str(in.readLine(10)));
		assertEquals("rest", str(in.readLine(10)));
		EOFException e = assertThrows(EOFException.class, () -> in.readFully(1, null));
		assertEquals("closed in block", e.getMessage());
	}

	@Test
	void bufferedAndDiscard() throws IOException {
		BufferedLineInput in = input("a\nb\n", false);
		assertFalse(in.hasBuffered());
		assertEquals("a", str(in.readLine(10)));
		assertTrue(in.hasBuffered());
		in.discardBuffered();
		assertFalse(in.hasBuffered());
		assertNull(in.readLine(10));
	}

	@Test
	void timeoutKeepsPartialLineWhenWaiting() throws IOException {
		InputStream flaky = new InputStream() {
			private final byte[][] chunks = { "par".getBytes(), null, "tial\n".getBytes() };
			private int i;

			@Override
			public int read() {
				return -1;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (i >= chunks.length) {
					return -1;
				}
				byte[] c = chunks[i++];
				if (c == null) {
					throw new SocketTimeoutException();
				}
				System.arraycopy(c, 0, b, off, c.length);
				return c.length;
			}
		};
		BufferedLineInput in = new BufferedLineInput(flaky, "x", false);
		assertThrows(SocketTimeoutException.class, () -> in.readLine(100));
		in.setKeepWaiting(() -> true);
		assertEquals("partial", str(in.readLine(100)));
		in.setKeepWaiting(() -> false);
	}

	@Test
	void keepWaitingFalseThrowsTheTimeout() {
		InputStream timeout = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new SocketTimeoutException();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				throw new SocketTimeoutException();
			}
		};
		BufferedLineInput in = new BufferedLineInput(timeout, "x", false);
		in.setKeepWaiting(() -> false);
		assertThrows(SocketTimeoutException.class, () -> in.readLine(10));
	}

	@Test
	void ioUtilsBufferingCopyAndNoClose() throws IOException {
		InputStream raw = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
		InputStream b = IoUtils.buffered(raw);
		assertTrue(b != raw);
		assertTrue(IoUtils.buffered(b) == b);

		boolean[] closed = new boolean[1];
		ByteArrayOutputStream sink = new ByteArrayOutputStream() {
			@Override
			public void close() throws IOException {
				closed[0] = true;
			}
		};
		try (OutputStream o = IoUtils.noClose(sink)) {
			IoUtils.copy(b, o);
			o.write(new byte[] { 9 }, 0, 1);
		}
		assertFalse(closed[0]);
		assertArrayEquals(new byte[] { 1, 2, 3, 9 }, sink.toByteArray());

		ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
		try (OutputStream o = IoUtils.buffered(sink2)) {
			o.write(5);
		}
		assertArrayEquals(new byte[] { 5 }, sink2.toByteArray());
	}
}
