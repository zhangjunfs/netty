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
package io.netty.handler.codec.frame;

import static org.junit.Assert.*;
import io.netty.buffer.ChannelBuffer;
import io.netty.buffer.ChannelBuffers;
import io.netty.channel.embedded.EmbeddedStreamChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.CharsetUtil;

import org.junit.Assert;
import org.junit.Test;

public class DelimiterBasedFrameDecoderTest {

    @Test
    public void testFailSlowTooLongFrameRecovery() throws Exception {
        EmbeddedStreamChannel ch = new EmbeddedStreamChannel(
                new DelimiterBasedFrameDecoder(1, true, false, Delimiters.nulDelimiter()));

        for (int i = 0; i < 2; i ++) {
            ch.writeInbound(ChannelBuffers.wrappedBuffer(new byte[] { 1, 2 }));
            try {
                assertTrue(ch.writeInbound(ChannelBuffers.wrappedBuffer(new byte[] { 0 })));
                Assert.fail(DecoderException.class.getSimpleName() + " must be raised.");
            } catch (TooLongFrameException e) {
                // Expected
            }

            ch.writeInbound(ChannelBuffers.wrappedBuffer(new byte[] { 'A', 0 }));
            ChannelBuffer buf = (ChannelBuffer) ch.readInbound();
            Assert.assertEquals("A", buf.toString(CharsetUtil.ISO_8859_1));
        }
    }

    @Test
    public void testFailFastTooLongFrameRecovery() throws Exception {
        EmbeddedStreamChannel ch = new EmbeddedStreamChannel(
                new DelimiterBasedFrameDecoder(1, Delimiters.nulDelimiter()));

        for (int i = 0; i < 2; i ++) {
            try {
                assertTrue(ch.writeInbound(ChannelBuffers.wrappedBuffer(new byte[] { 1, 2 })));
                Assert.fail(DecoderException.class.getSimpleName() + " must be raised.");
            } catch (TooLongFrameException e) {
                // Expected
            }

            ch.writeInbound(ChannelBuffers.wrappedBuffer(new byte[] { 0, 'A', 0 }));
            ChannelBuffer buf = (ChannelBuffer) ch.readInbound();
            Assert.assertEquals("A", buf.toString(CharsetUtil.ISO_8859_1));
        }
    }
}
