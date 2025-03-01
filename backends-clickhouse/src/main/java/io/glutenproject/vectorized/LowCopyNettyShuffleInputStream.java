/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.vectorized;

import io.glutenproject.exception.GlutenException;

import io.netty.buffer.ByteBuf;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class LowCopyNettyShuffleInputStream implements ShuffleInputStream {

  private final InputStream in;
  private final int bufferSize;
  private final boolean isCompressed;

  private ByteBuf byteBuf;
  private int readBytesCount = 0;

  public LowCopyNettyShuffleInputStream(
      InputStream in, ByteBuf byteBuf, int bufferSize, boolean isCompressed) {
    // to prevent underlying netty buffer from being collected by GC
    this.in = in;
    this.byteBuf = byteBuf;
    this.bufferSize = bufferSize;
    this.isCompressed = isCompressed;
  }

  @Override
  public long read(long destAddress, long maxReadSize) {
    long bytesToRead = Math.min(maxReadSize, this.byteBuf.readableBytes());
    int bytesToRead32 = Math.toIntExact(bytesToRead);
    if (bytesToRead32 == 0) {
      return 0;
    }
    ByteBuffer direct = PlatformDependent.directBuffer(destAddress, bytesToRead32);
    // read data directly from ByteBuf to native address
    this.byteBuf.readBytes(direct);
    readBytesCount += direct.position();
    return direct.position();
  }

  @Override
  public long pos() {
    return readBytesCount;
  }

  @Override
  public boolean isCompressed() {
    return this.isCompressed;
  }

  @Override
  public void close() {
    try {
      in.close();
    } catch (IOException e) {
      throw new GlutenException(e);
    }
  }
}
