/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.bkd;

import java.io.EOFException;
import java.io.IOException;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.LongBitSet;

/** Reads points from disk in a fixed-with format, previously written with {@link OfflinePointWriter}. */
final class OfflinePointReader extends PointReader {
  long countLeft;
  private final IndexInput in;
  private final byte[] packedValue;
  private long ord;
  private int docID;
  // true if ords are written as long (8 bytes), else 4 bytes
  private boolean longOrds;

  OfflinePointReader(Directory tempDir, String tempFileName, int packedBytesLength, long start, long length, boolean longOrds) throws IOException {
    in = tempDir.openInput(tempFileName, IOContext.READONCE);
    int bytesPerDoc = packedBytesLength + Integer.BYTES;
    if (longOrds) {
      bytesPerDoc += Long.BYTES;
    } else {
      bytesPerDoc += Integer.BYTES;
    }
    long seekFP = start * bytesPerDoc;
    in.seek(seekFP);
    this.countLeft = length;
    packedValue = new byte[packedBytesLength];
    this.longOrds = longOrds;
  }

  @Override
  public boolean next() throws IOException {
    if (countLeft >= 0) {
      if (countLeft == 0) {
        return false;
      }
      countLeft--;
    }
    try {
      in.readBytes(packedValue, 0, packedValue.length);
    } catch (EOFException eofe) {
      assert countLeft == -1;
      return false;
    }
    if (longOrds) {
      ord = in.readLong();
    } else {
      ord = in.readInt();
    }
    docID = in.readInt();
    return true;
  }

  @Override
  public byte[] packedValue() {
    return packedValue;
  }

  @Override
  public long ord() {
    return ord;
  }

  @Override
  public int docID() {
    return docID;
  }

  @Override
  public void close() throws IOException {
    in.close();
  }

  @Override
  public long split(long count, LongBitSet rightTree, PointWriter left, PointWriter right, boolean doClearBits) throws IOException {

    if (left instanceof OfflinePointWriter == false ||
        right instanceof OfflinePointWriter == false) {
      return super.split(count, rightTree, left, right, doClearBits);
    }

    // We specialize the offline -> offline split since the default impl
    // is somewhat wasteful otherwise (e.g. decoding docID when we don't
    // need to)

    int packedBytesLength = packedValue.length;

    int bytesPerDoc = packedBytesLength + Integer.BYTES;
    if (longOrds) {
      bytesPerDoc += Long.BYTES;
    } else {
      bytesPerDoc += Integer.BYTES;
    }

    long rightCount = 0;

    IndexOutput rightOut = ((OfflinePointWriter) right).out;
    IndexOutput leftOut = ((OfflinePointWriter) left).out;

    ((OfflinePointWriter) right).count = count;
    ((OfflinePointWriter) left).count = count;

    assert count <= countLeft: "count=" + count + " countLeft=" + countLeft;

    countLeft -= count;

    byte[] buffer = new byte[bytesPerDoc];
    while (count > 0) {
      in.readBytes(buffer, 0, buffer.length);
      long ord;
      if (longOrds) {
        ord = readLong(buffer, packedBytesLength);
      } else {
        ord = readInt(buffer, packedBytesLength);
      }
      if (rightTree.get(ord)) {
        rightOut.writeBytes(buffer, 0, bytesPerDoc);
        if (doClearBits) {
          rightTree.clear(ord);
        }
        rightCount++;
      } else {
        leftOut.writeBytes(buffer, 0, bytesPerDoc);
      }

      count--;
    }

    return rightCount;
  }

  // Poached from ByteArrayDataInput:
  private static long readLong(byte[] bytes, int pos) {
    final int i1 = ((bytes[pos++] & 0xff) << 24) | ((bytes[pos++] & 0xff) << 16) |
      ((bytes[pos++] & 0xff) << 8) | (bytes[pos++] & 0xff);
    final int i2 = ((bytes[pos++] & 0xff) << 24) | ((bytes[pos++] & 0xff) << 16) |
      ((bytes[pos++] & 0xff) << 8) | (bytes[pos++] & 0xff);
    return (((long)i1) << 32) | (i2 & 0xFFFFFFFFL);
  }

  // Poached from ByteArrayDataInput:
  private static int readInt(byte[] bytes, int pos) {
    return ((bytes[pos++] & 0xFF) << 24) | ((bytes[pos++] & 0xFF) << 16)
      | ((bytes[pos++] & 0xFF) <<  8) |  (bytes[pos++] & 0xFF);
  }
}

