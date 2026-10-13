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
 * Data read from the wire is malformed: a read past the end of the data (or of a
 * limit, see {@link WireBuffer#pushLimit(int)}), or a length that can't be right.
 * Unchecked, so a parser built on {@link WireBuffer} can turn it into its own
 * exception in one place (or override {@link WireBuffer#malformed(String)}).
 */
public class WireFormatException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public WireFormatException(String message) {
		super(message);
	}
}
