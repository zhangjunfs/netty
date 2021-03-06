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
package io.netty.handler.codec.bytes;

import static io.netty.buffer.ChannelBuffers.*;
import static org.hamcrest.core.Is.*;
import static org.hamcrest.core.IsNull.*;
import static org.junit.Assert.*;
import io.netty.buffer.ChannelBuffer;
import io.netty.channel.embedded.EmbeddedMessageChannel;

import java.util.Random;

import org.junit.Before;
import org.junit.Test;

/**
 */
public class ByteArrayEncoderTest {

    private EmbeddedMessageChannel ch;

    @Before
    public void setUp() {
        ch = new EmbeddedMessageChannel(new ByteArrayEncoder());
    }

    @Test
    public void testEncode() {
        byte[] b = new byte[2048];
        new Random().nextBytes(b);
        ch.writeOutbound(b);
        assertThat((ChannelBuffer) ch.readOutbound(), is(wrappedBuffer(b)));
    }

    @Test
    public void testEncodeEmpty() {
        byte[] b = new byte[0];
        ch.writeOutbound(b);
        assertThat(ch.readOutbound(), nullValue());
    }

    @Test
    public void testEncodeOtherType() {
        String str = "Meep!";
        ch.writeOutbound(str);
        assertThat(ch.readOutbound(), is((Object) str));
    }
}
