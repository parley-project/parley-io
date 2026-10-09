package us.bringardner.parley.io;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Reads a connection as bytes: lines (up to CRLF or LF) and exact-length blocks (an IMAP literal, an
 * SMTP BDAT chunk). A line interrupted by a socket timeout is kept, so reading can resume (used while
 * IMAP IDLE waits for "DONE"). A subclass adds the protocol's own framing (SMTP DATA, say) on top of
 * the buffer, using {@link #fill()}, {@link #buf}, {@link #pos} and {@link #len}.
 */
public class BufferedLineInput {

	private final InputStream in;
	private final String blockEofMessage;
	private final boolean lastLineWithoutEol;

	protected final byte[] buf = new byte[64 * 1024];
	/** The next unread byte of {@link #buf}. */
	protected int pos;
	/** The end of the bytes in {@link #buf}. */
	protected int len;

	private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
	private boolean discarding;
	private volatile BooleanSupplier keepWaiting;
	private volatile long lastReceived = System.currentTimeMillis();

	/**
	 * @param in the connection
	 * @param blockEofMessage the message of the EOFException when the connection closes in a block
	 * @param lastLineWithoutEol true to return a final line that has no line end; false to ignore it
	 */
	public BufferedLineInput(InputStream in, String blockEofMessage, boolean lastLineWithoutEol) {
		this.in = in;
		this.blockEofMessage = blockEofMessage;
		this.lastLineWithoutEol = lastLineWithoutEol;
	}

	/**
	 * Asked after a socket read timeout: true to keep waiting, false to let the
	 * {@link SocketTimeoutException} through (reading can resume later).
	 */
	public void setKeepWaiting(BooleanSupplier keepWaiting) {
		this.keepWaiting = keepWaiting;
	}

	/** The time (ms) bytes were last received. */
	public long getLastReceived() {
		return lastReceived;
	}

	/** Make sure there are unread bytes in the buffer; false at end of stream. */
	protected final boolean fill() throws IOException {
		if (pos < len) {
			return true;
		}
		int n;
		while (true) {
			try {
				n = in.read(buf, 0, buf.length);
				break;
			} catch (SocketTimeoutException e) {
				BooleanSupplier k = keepWaiting;
				if (k == null || !k.getAsBoolean()) {
					throw e;
				}
			}
		}
		if (n <= 0) {
			return false;
		}
		lastReceived = System.currentTimeMillis();
		pos = 0;
		len = n;
		return true;
	}

	/**
	 * A line without its CRLF (a bare LF also ends it), or null at end of stream.
	 *
	 * @throws LineTooLongException for a line longer than {@code max} bytes; the rest of the line is discarded
	 */
	public byte[] readLine(int max) throws IOException {
		while (true) {
			if (!fill()) {
				if (lastLineWithoutEol && partial.size() > 0) {
					byte[] rest = partial.toByteArray();
					partial.reset();
					return rest;
				}
				return null;
			}
			int i = pos;
			while (i < len && buf[i] != '\n') {
				i++;
			}
			if (!discarding) {
				partial.write(buf, pos, i - pos);
			}
			boolean found = i < len;
			pos = found ? i + 1 : len;
			if (!discarding && partial.size() > max) {
				discarding = true;
				partial.reset();
			}
			if (found) {
				if (discarding) {
					discarding = false;
					throw new LineTooLongException(max);
				}
				byte[] line = partial.toByteArray();
				partial.reset();
				int n = line.length;
				if (n > 0 && line[n - 1] == '\r') {
					n--;
				}
				return n == line.length ? line : Arrays.copyOf(line, n);
			}
		}
	}

	/** Copy exactly {@code n} bytes to {@code out} (null discards them). */
	public void readFully(long n, OutputStream out) throws IOException {
		while (n > 0) {
			if (!fill()) {
				throw new EOFException(blockEofMessage);
			}
			int c = (int) Math.min(n, len - pos);
			if (out != null) {
				out.write(buf, pos, c);
			}
			pos += c;
			n -= c;
		}
	}

	/** True if bytes have been received but not read yet (for PIPELINING). */
	public boolean hasBuffered() {
		return pos < len || partial.size() > 0;
	}

	/** Forget anything received but not read (after STARTTLS, RFC 3207, RFC 9051 section 11.1). */
	public void discardBuffered() {
		pos = len = 0;
		partial.reset();
		discarding = false;
	}
}
