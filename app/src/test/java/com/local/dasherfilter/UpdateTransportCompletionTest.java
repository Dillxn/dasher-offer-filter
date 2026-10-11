package com.local.dasherfilter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.After;
import org.junit.Test;

/** Pure transport completion checks with synthetic streams; no network, Android runtime or timed waits. */
public final class UpdateTransportCompletionTest {
    @After public void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test public void completeBodyBeforeDeadlineSucceedsAndClosesInput() throws Exception {
        SyntheticInput input = new SyntheticInput(false, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(3, copy(input, output, 8, future()));
        assertArrayEquals(new byte[] {1, 2, 3}, output.toByteArray());
        assertTrue("input closed", input.closed);
    }

    @Test public void cancellationArrivingAtEofIsRejectedAndPreserved() throws Exception {
        SyntheticInput input = new SyntheticInput(true, false);
        rejects("Update check cancelled", input, 8, future());
        assertTrue("interrupt flag preserved for the updater's catch path", Thread.currentThread().isInterrupted());
        assertTrue("input closed", input.closed);
    }

    @Test public void cancellationArrivingDuringCloseIsRejectedAndPreserved() throws Exception {
        SyntheticInput input = new SyntheticInput(false, true);
        rejects("Update check cancelled", input, 8, future());
        assertTrue("interrupt flag preserved for the updater's catch path", Thread.currentThread().isInterrupted());
        assertTrue("input closed", input.closed);
    }

    @Test public void expiredDeadlineWithEmptyBodyIsRejected() throws Exception {
        // The response has used up the request's budget and its body immediately reaches EOF.
        SyntheticInput input = new SyntheticInput(false, false);
        input.sent = true;
        rejects("Update download timed out", input, 8, System.nanoTime() - 1);
        assertTrue("input closed", input.closed);
    }

    @Test public void existingByteLimitStillRejectsExcessBody() throws Exception {
        SyntheticInput input = new SyntheticInput(false, false);
        rejects("Update download limit reached", input, 2, future());
        assertTrue("input closed after failure", input.closed);
    }

    @Test public void existingCancellationDuringBodyStillRejects() throws Exception {
        SyntheticInput input = new SyntheticInput(false, false);
        Thread.currentThread().interrupt();
        rejects("Update check cancelled", input, 8, future());
        assertTrue("input closed after failure", input.closed);
    }

    @Test public void existingCloseFailureRemainsAnIOException() throws Exception {
        SyntheticInput input = new SyntheticInput(false, false);
        input.failClose = true;
        rejects("synthetic close failure", input, 8, future());
        assertTrue("input close attempted", input.closed);
    }

    private static long future() {
        return System.nanoTime() + 5_000_000_000L;
    }

    private static void rejects(String expected, SyntheticInput input, long limit, long deadline) {
        IOException error = assertThrows(IOException.class,
                () -> copy(input, new ByteArrayOutputStream(), limit, deadline));
        assertEquals(expected, error.getMessage());
    }

    private static long copy(InputStream input, OutputStream output, long limit, long deadline) throws Exception {
        HttpURLConnection connection = new HttpURLConnection(new URL(UpdatePolicy.FEED)) {
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public InputStream getInputStream() { return input; }
        };
        // Keep production visibility unchanged; supply an expired deadline without changing the clock or waiting.
        Method copy = UpdateTransport.class.getDeclaredMethod("copy", HttpURLConnection.class,
                OutputStream.class, long.class, long.class);
        copy.setAccessible(true);
        try {
            return (Long) copy.invoke(null, connection, output, limit, deadline);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static final class SyntheticInput extends InputStream {
        private final boolean cancelAtEof;
        private final boolean cancelOnClose;
        boolean sent;
        boolean closed;
        boolean failClose;

        SyntheticInput(boolean cancelAtEof, boolean cancelOnClose) {
            this.cancelAtEof = cancelAtEof;
            this.cancelOnClose = cancelOnClose;
        }

        @Override public int read() {
            throw new AssertionError("bulk read expected");
        }

        @Override public int read(byte[] bytes, int offset, int length) {
            if (!sent) {
                sent = true;
                bytes[offset] = 1;
                bytes[offset + 1] = 2;
                bytes[offset + 2] = 3;
                return 3;
            }
            if (cancelAtEof) Thread.currentThread().interrupt();
            return -1;
        }

        @Override public void close() throws IOException {
            closed = true;
            if (cancelOnClose) Thread.currentThread().interrupt();
            if (failClose) throw new IOException("synthetic close failure");
        }
    }
}
