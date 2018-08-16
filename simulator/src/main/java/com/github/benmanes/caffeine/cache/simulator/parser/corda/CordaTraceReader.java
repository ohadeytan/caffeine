package com.github.benmanes.caffeine.cache.simulator.parser.corda;

import com.github.benmanes.caffeine.cache.simulator.parser.BinaryTraceReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

/**
 * A reader for the trace files provided by
 * <a href="http://www.r3.com/">R3</a>.
 *
 * @author christian.sailer@r3.com
 */

public final class CordaTraceReader extends BinaryTraceReader {
    public CordaTraceReader(List<String> filePaths) {
        super(filePaths);
    }

    @Override
    protected long readLong(DataInputStream input) throws IOException {
        return input.readLong();
    }

}
