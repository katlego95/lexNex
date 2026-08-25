package io.github.katlego95.lexpipeline.validation;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails fast once more than {@code limit} bytes have been read.
 *
 * <p>The cheap size check is {@code Resource.contentLength()}, done before a parser is even
 * created. This stream is the backstop for sources that cannot answer that question honestly — a
 * chunked HTTP body, a growing file, a stream wrapper that reports -1. Without it, an oversize
 * document would still be fully parsed and the memory guard would exist only on paper.
 */
final class SizeLimitedInputStream extends FilterInputStream {

    private final long limit;
    private long read;

    SizeLimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    private void count(long n) throws LimitExceededException {
        read += n;
        if (read > limit) {
            throw new LimitExceededException(limit);
        }
    }

    /**
     * An IOException subclass so it travels through {@code Validator.validate}, which is only
     * declared to throw IOException and SAXException, and can still be told apart from a genuine
     * I/O failure by the catch block that handles it.
     */
    static final class LimitExceededException extends IOException {

        private final long limit;

        LimitExceededException(long limit) {
            super("Document exceeds the configured limit of %d bytes".formatted(limit));
            this.limit = limit;
        }

        long limit() {
            return limit;
        }
    }
}
