package us.bringardner.parley.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes content as a dot terminated, dot stuffed body, the way SMTP DATA (RFC 5321 section 4.5.2),
 * POP3 RETR and TOP (RFC 1939 section 3), NNTP and other line protocols send a block of text of
 * any length:
 * <ul>
 * <li>line ends become CRLF (a bare LF always; a bare CR too if {@code normalizeCr} is set);</li>
 * <li>a "." that starts a line is doubled, so the content can't be taken for the end;</li>
 * <li>{@link #finish()} ends the last line if needed and writes the terminating line ".".</li>
 * </ul>
 * {@link #close()} finishes (once) and closes the underlying stream; call {@link #finish()}
 * instead to keep the underlying stream (a socket) open.
 * <p>
 * The reverse is reading up to the "." line and removing one leading "." from each line.
 */
public class DotStuffingOutputStream extends FilterOutputStream {

	private final boolean normalizeCr;
	/** The next byte starts a line. */
	private boolean lineStart = true;
	/** Normalizing: a CR was seen and is waiting to see if an LF follows. */
	private boolean pendingCr;
	/** Not normalizing: the last byte written, to tell a bare LF from CRLF. */
	private int prev = '\n';
	private boolean finished;

	/** Stuff the content; a CR is kept as it is unless it ends a line with an LF. */
	public DotStuffingOutputStream(OutputStream out) {
		this(out, false);
	}

	/**
	 * @param normalizeCr true to turn a bare CR into CRLF as well (SMTP, RFC 5321 section 2.3.8
	 *                    forbids a bare CR); false to leave it alone (POP3)
	 */
	public DotStuffingOutputStream(OutputStream out, boolean normalizeCr) {
		super(out);
		this.normalizeCr = normalizeCr;
	}

	@Override
	public void write(int b) throws IOException {
		if (finished) {
			throw new IOException("The content has been finished");
		}
		b &= 0xff;
		if (normalizeCr) {
			writeNormalizing(b);
		} else {
			writeKeepingCr(b);
		}
	}

	private void writeNormalizing(int b) throws IOException {
		if (pendingCr) {
			pendingCr = false;
			newline();
			if (b == '\n') {
				return;
			}
		}
		if (b == '\r') {
			pendingCr = true;
			return;
		}
		if (b == '\n') {
			newline();
			return;
		}
		if (lineStart && b == '.') {
			out.write('.');
		}
		out.write(b);
		lineStart = false;
	}

	private void newline() throws IOException {
		out.write('\r');
		out.write('\n');
		lineStart = true;
	}

	private void writeKeepingCr(int b) throws IOException {
		if (prev == '\n' && b == '.') {
			out.write('.');
		}
		if (b == '\n' && prev != '\r') {
			out.write('\r');
		}
		out.write(b);
		prev = b;
		lineStart = b == '\n';
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		for (int i = off; i < off + len; i++) {
			write(b[i]);
		}
	}

	/**
	 * End the content: a last line without an end gets CRLF, then "." CRLF is written and the
	 * stream is flushed (not closed). Further calls do nothing; writing after it is an error.
	 */
	public void finish() throws IOException {
		if (finished) {
			return;
		}
		if (pendingCr) {
			pendingCr = false;
			newline();
		}
		if (!lineStart) {
			out.write('\r');
			out.write('\n');
		}
		out.write('.');
		out.write('\r');
		out.write('\n');
		out.flush();
		finished = true;
	}

	/** {@link #finish()} (if it wasn't already), then close the underlying stream. */
	@Override
	public void close() throws IOException {
		finish();
		super.close();
	}
}
