package org.unicitylabs.faucet;

import java.io.OutputStream;
import java.io.PrintStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tees System.out and System.err to dedicated SLF4J loggers so every
 * println call reaches the rotating file appender in addition to the
 * original console stream.
 */
public final class StdioRedirect {

    private static boolean installed = false;

    private StdioRedirect() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        Logger outLogger = LoggerFactory.getLogger("faucet.stdout");
        Logger errLogger = LoggerFactory.getLogger("faucet.stderr");

        System.setOut(new PrintStream(new TeeStream(originalOut, outLogger, false), true));
        System.setErr(new PrintStream(new TeeStream(originalErr, errLogger, true), true));
    }

    private static final class TeeStream extends OutputStream {
        private final PrintStream console;
        private final Logger logger;
        private final boolean asError;
        private final StringBuilder buffer = new StringBuilder();

        TeeStream(PrintStream console, Logger logger, boolean asError) {
            this.console = console;
            this.logger = logger;
            this.asError = asError;
        }

        @Override
        public synchronized void write(int b) {
            console.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                buffer.append((char) b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            console.write(b, off, len);
            for (int i = 0; i < len; i++) {
                byte c = b[off + i];
                if (c == '\n') {
                    flushLine();
                } else if (c != '\r') {
                    buffer.append((char) (c & 0xFF));
                }
            }
        }

        @Override
        public synchronized void flush() {
            console.flush();
        }

        private void flushLine() {
            if (buffer.length() == 0) return;
            String line = buffer.toString();
            buffer.setLength(0);
            if (asError) {
                logger.error(line);
            } else {
                logger.info(line);
            }
        }
    }
}
