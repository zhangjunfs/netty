/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.base64;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * Encodes a {@link ChannelBuffer} into a Base64-encoded {@link ChannelBuffer}.
 * A typical setup for TCP/IP would be:
 * <pre>
 * {@link ChannelPipeline} pipeline = ...;
 *
 * // Decoders
 * pipeline.addLast("frameDecoder", new {@link DelimiterBasedFrameDecoder}(80, {@link Delimiters#nulDelimiter()}));
 * pipeline.addLast("base64Decoder", new {@link Base64Decoder}());
 *
 * // Encoder
 * pipeline.addLast("base64Encoder", new {@link Base64Encoder}());
 * </pre>
 * @apiviz.landmark
 * @apiviz.uses io.netty.handler.codec.base64.Base64
 */
@Sharable
public class Base64Encoder extends MessageToMessageEncoder<ChannelBuffer, ChannelBuffer> {

    private final boolean breakLines;
    private final Base64Dialect dialect;

    public Base64Encoder() {
        this(true);
    }

    public Base64Encoder(boolean breakLines) {
        this(breakLines, Base64Dialect.STANDARD);
    }

    public Base64Encoder(boolean breakLines, Base64Dialect dialect) {
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }

        this.breakLines = breakLines;
        this.dialect = dialect;
    }

    @Override
    public boolean isEncodable(Object msg) throws Exception {
        return msg instanceof ChannelBuffer;
    }

    @Override
    public ChannelBuffer encode(ChannelHandlerContext ctx,
            ChannelBuffer msg) throws Exception {
        return Base64.encode(msg, msg.readerIndex(), msg.readableBytes(), breakLines, dialect);
    }
}
