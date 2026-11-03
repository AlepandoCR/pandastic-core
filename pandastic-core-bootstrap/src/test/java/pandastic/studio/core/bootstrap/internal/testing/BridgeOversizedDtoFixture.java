package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * A non-record {@link Dto} whose {@code writeObject} writes far more than the bridge's byte limit.
 * <p>
 * The serialization fallback has to <em>write</em> the sending side's object before it can read it
 * back, and {@code writeObject} is the sending side's own code. A limit that is only checked on
 * the finished byte array therefore arrives too late: by then the whole thing has been
 * materialised on the heap, twice. {@link #bytesWritten} records how far the write got, so a test
 * can assert the write was cut off rather than merely reported after the fact.
 */
public final class BridgeOversizedDtoFixture implements Dto {

    private static final long serialVersionUID = 1L;

    /**
     * Size of one block handed to the stream.
     */
    private static final int BLOCK = 1024;

    /**
     * How much {@link #writeObject} tries to write in total: comfortably past any sane limit, and
     * small enough that a test can afford it if nothing stops it.
     */
    private static final int TOTAL = 8 * 1024 * 1024;

    /**
     * Bytes {@link #writeObject} managed to hand over before it was stopped, if it was.
     */
    public static volatile int bytesWritten;

    public BridgeOversizedDtoFixture() {
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        byte[] block = new byte[BLOCK];
        for (int written = 0; written < TOTAL; written += BLOCK) {
            out.write(block);
            bytesWritten = written + BLOCK;
        }
    }
}
