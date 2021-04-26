/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.parser.adapt_size;

import java.math.RoundingMode;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.math.IntMath;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by the authors of the AdaptSize algorithm. See
 * <a href="https://github.com/dasebe/webcachesim#how-to-get-traces">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AdaptSizeChunked extends TextTraceReader implements KeyOnlyTraceReader {
  static final int CHUNK_SIZE = 4096;

  public AdaptSizeChunked(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines().flatMapToLong( line -> {
        String[] array = line.split(" ", 4);
        long key = Long.parseLong(array[1]);
        int weight = Integer.parseInt(array[2]);
        int chunks = IntMath.divide(weight, CHUNK_SIZE, RoundingMode.UP);
        return LongStream.range(0, chunks).map(chunk -> Hashing.murmur3_128().newHasher()
              .putLong(key).putInt(weight).putLong(chunk).hash().asLong());
    });
  }
}
