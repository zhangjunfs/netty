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
package io.netty.example.echo;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundStreamHandlerAdapter;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler implementation for the echo server.
 */
public class EchoServerHandler extends ChannelInboundStreamHandlerAdapter {

    private static final Logger logger = Logger.getLogger(
            EchoServerHandler.class.getName());

    @Override
    public void inboundBufferUpdated(ChannelHandlerContext ctx, ChannelBuffer in) {
        ChannelBuffer out = ctx.nextOutboundByteBuffer();
        out.discardReadBytes();
        out.writeBytes(in);
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        logger.log(Level.WARNING, "Unexpected exception from downstream.", cause);
        ctx.close();
    }
}
