package us.bringardner.parley.io.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.io.DotStuffingOutputStream;

/**
 * Dot stuffing and termination, in both bare CR modes.
 */
public class TestDotStuffingOutputStream {

	private static String run(boolean normalizeCr, String content) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DotStuffingOutputStream d = new DotStuffingOutputStream(bytes, normalizeCr);
		d.write(content.getBytes(StandardCharsets.ISO_8859_1));
		d.finish();
		return bytes.toString("ISO-8859-1");
	}

	@Test
	public void doublesALeadingDotAndTerminates() throws IOException {
		for (boolean n : new boolean[] {false, true}) {
			assertEquals("..start\r\n..\r\nend\r\n.\r\n", run(n, ".start\n.\nend"));
			assertEquals("a.b\r\n.\r\n", run(n, "a.b\r\n"), "a dot inside a line stays");
		}
	}

	@Test
	public void emptyContentIsJustTheTerminator() throws IOException {
		assertEquals(".\r\n", run(false, ""));
		assertEquals(".\r\n", run(true, ""));
	}

	@Test
	public void crlfIsNotDoubled() throws IOException {
		assertEquals("one\r\ntwo\r\n.\r\n", run(false, "one\r\ntwo\r\n"));
		assertEquals("one\r\ntwo\r\n.\r\n", run(true, "one\r\ntwo\r\n"));
	}

	@Test
	public void aBareCrIsKeptOrNormalized() throws IOException {
		// kept: the CR doesn't end a line, so the "." after it isn't at a line start (POP3)
		assertEquals("a\rb\r\n.\r\n", run(false, "a\rb"));
		assertEquals("a\r.\r\n.\r\n", run(false, "a\r."));
		// normalized: the CR ends a line, and the "." after it is stuffed (SMTP)
		assertEquals("a\r\nb\r\n.\r\n", run(true, "a\rb"));
		assertEquals("a\r\n..\r\n.\r\n", run(true, "a\r."));
		assertEquals("a\r\n.\r\n", run(true, "a\r"), "a final bare CR becomes CRLF");
	}

	@Test
	public void aCrLfSplitAcrossWritesIsOneLineEnd() throws IOException {
		for (boolean n : new boolean[] {false, true}) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DotStuffingOutputStream d = new DotStuffingOutputStream(bytes, n);
			d.write("a\r".getBytes(StandardCharsets.US_ASCII));
			d.write("\nb".getBytes(StandardCharsets.US_ASCII));
			d.finish();
			assertEquals("a\r\nb\r\n.\r\n", bytes.toString("US-ASCII"));
		}
	}

	@Test
	public void finishIsIdempotentAndNothingFollowsIt() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DotStuffingOutputStream d = new DotStuffingOutputStream(bytes);
		d.write('x');
		d.finish();
		d.finish();
		assertEquals("x\r\n.\r\n", bytes.toString("US-ASCII"));
		assertThrows(IOException.class, () -> d.write('y'));
	}

	@Test
	public void closeFinishesOnceAndClosesTheUnderlyingStream() throws IOException {
		boolean[] closed = {false};
		ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
			@Override
			public void close() throws IOException {
				closed[0] = true;
				super.close();
			}
		};
		DotStuffingOutputStream d = new DotStuffingOutputStream(bytes);
		d.write("hi".getBytes(StandardCharsets.US_ASCII));
		d.finish();
		d.close();
		assertEquals("hi\r\n.\r\n", bytes.toString("US-ASCII"), "close after finish adds nothing");
		assertTrue(closed[0]);
	}

	@Test
	public void largeContentRoundTrips() throws IOException {
		StringBuilder in = new StringBuilder();
		StringBuilder expected = new StringBuilder();
		for (int i = 0; i < 2000; i++) {
			String line = (i % 7 == 0 ? "." : "") + "line " + i;
			in.append(line).append("\r\n");
			expected.append(i % 7 == 0 ? "." : "").append(line).append("\r\n");
		}
		String out = run(true, in.toString());
		assertTrue(out.endsWith("\r\n.\r\n"));
		// undo the stuffing: drop the terminator and one leading dot per line
		String body = out.substring(0, out.length() - 3);
		StringBuilder back = new StringBuilder();
		for (String line : body.split("\r\n", -1)) {
			if (line.isEmpty()) {
				continue;
			}
			back.append(line.startsWith(".") ? line.substring(1) : line).append("\r\n");
		}
		assertEquals(in.toString(), back.toString());
	}
}
