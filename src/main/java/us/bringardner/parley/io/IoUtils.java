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

/**
 * Small I/O helpers shared by the Parley libraries.
 */
public final class IoUtils {

	private IoUtils() {
		//  Static methods only, never instantiated.
	}

	/**
	 * Close something when a failure to close doesn't matter: cleaning up after an error that
	 * is already being reported, or a socket whose session is over anyway. Never use it where a
	 * failed close means lost data (an output stream that still has to be flushed, say).
	 * 
	 * @param c the thing to close; null is ignored
	 */
	public static void closeQuietly(AutoCloseable c) {
		if( c != null ) {
			try {
				c.close();
			} catch (Exception e) {
				//  Ignored, see the method comment
			}
		}
	}

	/**
	 * Close each of them, ignoring nulls and failures, see {@link #closeQuietly(AutoCloseable)}.
	 * A failure closing one doesn't stop the others being closed.
	 * 
	 * @param all the things to close
	 */
	public static void closeQuietly(AutoCloseable... all) {
		if( all != null ) {
			for(AutoCloseable c : all) {
				closeQuietly(c);
			}
		}
	}

	/** The buffer size for copying and for buffered file and socket streams. */
	public static final int BUFFER_SIZE = 64 * 1024;

	/** {@code in} with a {@link #BUFFER_SIZE} buffer (as is if it is already buffered). */
	public static java.io.InputStream buffered(java.io.InputStream in) {
		return in instanceof java.io.BufferedInputStream ? in : new java.io.BufferedInputStream(in, BUFFER_SIZE);
	}

	/** {@code out} with a {@link #BUFFER_SIZE} buffer. */
	public static java.io.OutputStream buffered(java.io.OutputStream out) {
		return new java.io.BufferedOutputStream(out, BUFFER_SIZE);
	}

	/** Copy everything from {@code in} to {@code out}; neither is closed. */
	public static void copy(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
		byte[] buf = new byte[BUFFER_SIZE];
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
	}

	/** An output stream whose close() flushes but doesn't close the stream it writes to. */
	public static java.io.OutputStream noClose(java.io.OutputStream out) {
		return new java.io.FilterOutputStream(out) {
			@Override
			public void write(byte[] b, int off, int len) throws java.io.IOException {
				out.write(b, off, len);
			}

			@Override
			public void close() throws java.io.IOException {
				flush();
			}
		};
	}
}
