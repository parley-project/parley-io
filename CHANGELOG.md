# Changelog

## parley-io 1.0.0 (unreleased)

BjlIo is now **parley-io**, part of the Parley library family. It continues from BjlIo 1.1.0
(see [BjlIo history](#bjlio-history)); the changes since then are below, with the renames last.

### Added

- `WireBuffer` and `WireFormatException`: a byte array with read and write positions for binary
  protocols (big endian u8/u16/u32/u64, 1/2/4-byte length-prefixed bytes). Every read is bounds
  checked before anything is read or allocated; `pushLimit`/`popLimit` confine reading to a record
  and check it was all used; `setU16`/`setU32` fill in a length afterwards. DNS messages and SSH
  packets are built on it (parley-dns `ByteBuffer`, parley-ssh `SshBuffer`).
- `BufferedLineInput`: reads a connection as bytes, as lines (CRLF or bare LF) and as exact-length
  blocks (an IMAP literal, an SMTP BDAT chunk). A line interrupted by a read timeout is kept, so reading
  can resume. A subclass can add its own framing on top of the buffer.
- `IoUtils.buffered(...)`, `copy(in, out)`, `noClose(out)` and `BUFFER_SIZE` (64 KB): buffered streams,
  copying, and an output stream whose `close()` only flushes.
- `DotStuffingOutputStream`: writes content as a dot terminated, dot stuffed body (SMTP DATA, POP3
  RETR / TOP, NNTP), replacing the copies in parley-smtp and parley-pop3. Bare LF always becomes
  CRLF; bare CR becomes CRLF too with `normalizeCr` (SMTP). `finish()` terminates without closing.
- `IoUtils.closeQuietly(AutoCloseable...)`: close and ignore failures (null safe), replacing the private
  `closeQuietly` helpers and inline `try { x.close(); } catch {}` blocks in the Parley libraries.

### Removed

- `IoConstants` (`CR`, `NL`, `DEFAULT_BUFFER_SIZE`, `CRNL` and `crlf()`). Nothing in the Parley libraries
  used it; the readers and writers keep their own private copies.
- `TelnetInputStream` and `TelnetOutputStream`. Despite the names they didn't implement the Telnet
  protocol: they echoed what was read to another stream and limited output to 7-bit characters. Nothing
  in the Parley libraries used them. Use `TeeOutputStream` for echoing.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner:bjl_io` is now `us.bringardner.parley:parley-io`.
- Packages: `us.bringardner.io` is now `us.bringardner.parley.io`.
- Module name (`Automatic-Module-Name`): `us.bringardner.io` is now `us.bringardner.parley.io`.
- The tests now use `us.bringardner.parley:parley-core` instead of `us.bringardner:bjl_core`.

## BjlIo history

Releases made before the library became parley-io, as `us.bringardner:bjl_io` (BjlIo).

## 1.1.0

### Fixed

- A line interrupted by a read error was lost. If reading failed part way through a line, most often
  a `SocketTimeoutException` from a socket with a read timeout, the next `readLine()` started a new
  line and the part already read was dropped. A server that retries after a timeout (as
  bjl_net_framework does) then saw a client that paused in the middle of a line, longer than the
  timeout, send `ice` instead of `USER alice`. The part read is now kept and the next `readLine()`
  carries on with the same line; at the end of the stream it is returned as the last line.
- `getLastReadTime()` was only updated by `readLine()`, so a connection read with `read()`,
  `read(byte[])` or `skip()` looked idle to an idle check (and could be closed while it was busy).
  It is now updated whenever data is read, by any method.
- `ContinuousInputStream` (`tail -f`) kept reading a log file after it was moved (or deleted) and a new
  one created under the same name, logrotate's default, so it silently stopped seeing new lines. It now
  reads the rest of the old file and then the new one from its beginning, like `tail -F`. A moved file
  that isn't replaced is still read (whatever has it open may still write to it). This needs the file's
  name, so it isn't done for a stream made from a `RandomAccessFile`; on Windows an open file can't be
  moved anyway.
- `ContinuousInputStream.readLine()` dropped the part of a line it had read when it was interrupted
  (`InterruptedIOException`); the next call carries on with the same line now.

### Added

- `ContinuousInputStream.setMaxLineLength(int)` / `getMaxLineLength()`: `readLine()` throws
  `LineTooLongException` for a longer line, so a file without line terminators (a binary file, say)
  can't make it run out of memory. Off by default.

### Changed

- The line readers use a `ReentrantLock` instead of `synchronized` methods (BJL-55). A reader is
  usually blocked on a socket while it holds the lock, and on Java 21-23 a virtual thread blocked
  inside a `synchronized` method holds on to its carrier thread, so a server with a virtual thread
  per connection stopped responding once there were as many idle connections as CPUs.
  `ContinuousInputStream` does the same: it waits (sleeps) for data while holding its lock.

## 1.0.0

First stable release. It contains breaking changes from 0.1.x; see
[Upgrading from 0.1.x](#upgrading-from-01x) below.

### Breaking changes

- **`ContinuosInputStream` is renamed `ContinuousInputStream`** (spelling fix).
- **`ContinuousInputStream.readLine()` returns `null` at the end of the stream** instead of
  throwing `EOFException`, like the other line readers and `BufferedReader`.
- **`IoConstants` is now a `final class`** instead of an interface. `ILineReader`, `ILineWriter`
  and the reader/writer classes no longer inherit its constants, so write `IoConstants.CR`
  (or use `import static us.bringardner.io.IoConstants.*`) instead of `CRLFLineReader.CR`, and
  classes can no longer `implements IoConstants`.
- **Text is encoded and decoded as UTF-8** unless you pass another `Charset`. 0.1.x writers used
  the platform default encoding, and readers turned each byte into one `char`.
- **`getBytesIn()` counts every byte consumed**, including line terminators and bytes read with
  `read()` or `skip()`. It now matches the writer's `getBytesOut()` for the same data.
- **`TelnetInputStream`** requires a non-null input and echo stream, and `close()` now flushes the
  echo stream but no longer closes it (so `System.out` stays open).
- **`TelnetOutputStream`** transmits all printable ASCII: `MAX_CHAR` is now `'~'`, so `{ | } ~`
  are no longer dropped.
- **`MonitoredInputStream` / `MonitoredOutputStream`** no longer override `equals`/`hashCode`; like
  other streams, each object is only equal to itself.
- **The `main()` test drivers were removed** from `TeeOutputStream` and `ContinuousInputStream`.

### Deprecated

- `IoConstants.CRNL`: any code can change the array. Use `IoConstants.crlf()`, which returns a new copy.

### Added

- `AbstractLineReader`, a shared base class for `CRLFLineReader` and `LFLineReader`.
- `Charset` constructors on every line reader and writer.
- `setMaxLineLength(int)` and `LineTooLongException`, to protect against a peer that never sends
  a line terminator.
- `ContinuousInputStream`: `unreadLines(int)` (like `tail -n`), `setCharset`, bulk `read(byte[], int, int)`,
  and restarting from the beginning when the file is truncated (log rotation).
- Efficient bulk reads and writes in the Telnet streams, and in the monitored and line streams.
- The jar declares the module name `us.bringardner.io` (`Automatic-Module-Name`).
- JaCoCo coverage report and a 90% line/branch coverage check in `mvn verify`.
- `LICENSE` file (Apache 2.0) and license, SCM and URL information in the POM.

### Fixed

- The line readers returned `null` (end of stream) for an empty line; they now return `""`.
- `TeeOutputStream` writes to every stream even if one fails, and reports all errors.
- `getBytesOut()` no longer loses counts when several threads write at the same time.
- Many smaller fixes; see the git history.

### Upgrading from 0.1.x

1. Rename `ContinuosInputStream` to `ContinuousInputStream`.
2. Replace `catch (EOFException e)` around `ContinuousInputStream.readLine()` with a `null` check.
3. Replace constants reached through a reader or writer (`CRLFLineReader.NL`) with `IoConstants.NL`.
4. If you relied on the platform encoding, pass that `Charset` to the reader or writer constructor.
5. Don't pass `null` as the echo stream of a `TelnetInputStream`. If you relied on `close()` closing
   the echo stream, close it yourself.
