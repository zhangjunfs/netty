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
package io.netty.channel;

import static io.netty.channel.DefaultChannelPipeline.*;
import io.netty.buffer.ChannelBuffer;
import io.netty.buffer.ChannelBuffers;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.internal.QueueFactory;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

final class DefaultChannelHandlerContext extends DefaultAttributeMap implements ChannelHandlerContext {

    private static final EnumSet<ChannelHandlerType> EMPTY_TYPE = EnumSet.noneOf(ChannelHandlerType.class);

    volatile DefaultChannelHandlerContext next;
    volatile DefaultChannelHandlerContext prev;
    private final Channel channel;
    private final DefaultChannelPipeline pipeline;
    EventExecutor executor; // not thread-safe but OK because it never changes once set.
    private final String name;
    final Set<ChannelHandlerType> type;
    private final ChannelHandler handler;

    final Queue<Object> inMsgBuf;
    final ChannelBuffer inByteBuf;
    final Queue<Object> outMsgBuf;
    final ChannelBuffer outByteBuf;

    // When the two handlers run in a different thread and they are next to each other,
    // each other's buffers can be accessed at the same time resulting in a race condition.
    // To avoid such situation, we lazily creates an additional thread-safe buffer called
    // 'bridge' so that the two handlers access each other's buffer only via the bridges.
    // The content written into a bridge is flushed into the actual buffer by flushBridge().
    final AtomicReference<MessageBridge> inMsgBridge;
    final AtomicReference<MessageBridge> outMsgBridge;
    final AtomicReference<StreamBridge> inByteBridge;
    final AtomicReference<StreamBridge> outByteBridge;

    // Runnables that calls handlers
    final Runnable fireChannelRegisteredTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext ctx = DefaultChannelHandlerContext.this;
            try {
                ((ChannelStateHandler) ctx.handler).channelRegistered(ctx);
            } catch (Throwable t) {
                pipeline.notifyHandlerException(t);
            }
        }
    };
    final Runnable fireChannelUnregisteredTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext ctx = DefaultChannelHandlerContext.this;
            try {
                ((ChannelStateHandler) ctx.handler).channelUnregistered(ctx);
            } catch (Throwable t) {
                pipeline.notifyHandlerException(t);
            }
        }
    };
    final Runnable fireChannelActiveTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext ctx = DefaultChannelHandlerContext.this;
            try {
                ((ChannelStateHandler) ctx.handler).channelActive(ctx);
            } catch (Throwable t) {
                pipeline.notifyHandlerException(t);
            }
        }
    };
    final Runnable fireChannelInactiveTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext ctx = DefaultChannelHandlerContext.this;
            try {
                ((ChannelStateHandler) ctx.handler).channelInactive(ctx);
            } catch (Throwable t) {
                pipeline.notifyHandlerException(t);
            }
        }
    };
    final Runnable curCtxFireInboundBufferUpdatedTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext ctx = DefaultChannelHandlerContext.this;
            flushBridge();
            try {
                ((ChannelStateHandler) ctx.handler).inboundBufferUpdated(ctx);
            } catch (Throwable t) {
                pipeline.notifyHandlerException(t);
            } finally {
                ChannelBuffer buf = inByteBuf;
                if (buf != null) {
                    if (!buf.readable()) {
                        buf.discardReadBytes();
                    }
                }
            }
        }
    };
    private final Runnable nextCtxFireInboundBufferUpdatedTask = new Runnable() {
        @Override
        public void run() {
            DefaultChannelHandlerContext next = nextContext(
                    DefaultChannelHandlerContext.this.next, ChannelHandlerType.STATE);
            if (next != null) {
                next.fillBridge();
                DefaultChannelPipeline.fireInboundBufferUpdated(next);
            }
        }
    };

    @SuppressWarnings("unchecked")
    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, EventExecutor executor,
            DefaultChannelHandlerContext prev, DefaultChannelHandlerContext next,
            String name, ChannelHandler handler) {

        if (name == null) {
            throw new NullPointerException("name");
        }
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        // Determine the type of the specified handler.
        EnumSet<ChannelHandlerType> type = EMPTY_TYPE.clone();
        if (handler instanceof ChannelStateHandler) {
            type.add(ChannelHandlerType.STATE);
        }
        if (handler instanceof ChannelInboundHandler) {
            type.add(ChannelHandlerType.INBOUND);
        }
        if (handler instanceof ChannelOutboundHandler) {
            type.add(ChannelHandlerType.OUTBOUND);
        }
        if (handler instanceof ChannelOperationHandler) {
            type.add(ChannelHandlerType.OPERATION);
        }
        this.type = Collections.unmodifiableSet(type);

        this.prev = prev;
        this.next = next;

        channel = pipeline.channel;
        this.pipeline = pipeline;
        this.name = name;
        this.handler = handler;

        if (executor != null) {
            // Pin one of the child executors once and remember it so that the same child executor
            // is used to fire events for the same channel.
            EventExecutor childExecutor = pipeline.childExecutors.get(executor);
            if (childExecutor == null) {
                childExecutor = executor.unsafe().nextChild();
                pipeline.childExecutors.put(executor, childExecutor);
            }
            this.executor = childExecutor;
        } else if (channel.isRegistered()) {
            this.executor = channel.eventLoop();
        } else {
            this.executor = null;
        }

        if (type.contains(ChannelHandlerType.INBOUND)) {
            ChannelBufferHolder<Object> holder;
            try {
                holder = ((ChannelInboundHandler<Object>) handler).newInboundBuffer(this);
            } catch (Exception e) {
                throw new ChannelPipelineException("A user handler failed to create a new inbound buffer.", e);
            }

            if (holder.hasByteBuffer()) {
                inByteBuf = holder.byteBuffer();
                inByteBridge = new AtomicReference<StreamBridge>();
                inMsgBuf = null;
                inMsgBridge = null;
            } else {
                inByteBuf = null;
                inByteBridge = null;
                inMsgBuf = holder.messageBuffer();
                inMsgBridge = new AtomicReference<MessageBridge>();
            }
        } else {
            inByteBuf = null;
            inByteBridge = null;
            inMsgBuf = null;
            inMsgBridge = null;
        }

        if (type.contains(ChannelHandlerType.OUTBOUND)) {
            ChannelBufferHolder<Object> holder;
            try {
                holder = ((ChannelOutboundHandler<Object>) handler).newOutboundBuffer(this);
            } catch (Exception e) {
                throw new ChannelPipelineException("A user handler failed to create a new outbound buffer.", e);
            }

            if (holder.hasByteBuffer()) {
                outByteBuf = holder.byteBuffer();
                outByteBridge = new AtomicReference<StreamBridge>();
                outMsgBuf = null;
                outMsgBridge = null;
            } else {
                outByteBuf = null;
                outByteBridge = null;
                outMsgBuf = holder.messageBuffer();
                outMsgBridge = new AtomicReference<MessageBridge>();
            }
        } else {
            outByteBuf = null;
            outByteBridge = null;
            outMsgBuf = null;
            outMsgBridge = null;
        }
    }

    void fillBridge() {
        if (inMsgBridge != null) {
            MessageBridge bridge = inMsgBridge.get();
            if (bridge != null) {
                bridge.fill();
            }
        } else if (inByteBridge != null) {
            StreamBridge bridge = inByteBridge.get();
            if (bridge != null) {
                bridge.fill();
            }
        }

        if (outMsgBridge != null) {
            MessageBridge bridge = outMsgBridge.get();
            if (bridge != null) {
                bridge.fill();
            }
        } else if (outByteBridge != null) {
            StreamBridge bridge = outByteBridge.get();
            if (bridge != null) {
                bridge.fill();
            }
        }
    }

    void flushBridge() {
        if (inMsgBridge != null) {
            MessageBridge bridge = inMsgBridge.get();
            if (bridge != null) {
                bridge.flush(inMsgBuf);
            }
        } else if (inByteBridge != null) {
            StreamBridge bridge = inByteBridge.get();
            if (bridge != null) {
                bridge.flush(inByteBuf);
            }
        }

        if (outMsgBridge != null) {
            MessageBridge bridge = outMsgBridge.get();
            if (bridge != null) {
                bridge.flush(outMsgBuf);
            }
        } else if (outByteBridge != null) {
            StreamBridge bridge = outByteBridge.get();
            if (bridge != null) {
                bridge.flush(outByteBuf);
            }
        }
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public EventExecutor executor() {
        if (executor == null) {
            return executor = channel.eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public ChannelHandler handler() {
        return handler;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<ChannelHandlerType> type() {
        return type;
    }

    @Override
    public boolean hasInboundByteBuffer() {
        return inByteBuf != null;
    }

    @Override
    public boolean hasInboundMessageBuffer() {
        return inMsgBuf != null;
    }

    @Override
    public ChannelBuffer inboundByteBuffer() {
        if (inByteBuf == null) {
            throw new NoSuchBufferException();
        }
        return inByteBuf;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Queue<T> inboundMessageBuffer() {
        if (inMsgBuf == null) {
            throw new NoSuchBufferException();
        }
        return (Queue<T>) inMsgBuf;
    }

    @Override
    public boolean hasOutboundByteBuffer() {
        return outByteBuf != null;
    }

    @Override
    public boolean hasOutboundMessageBuffer() {
        return outMsgBuf != null;
    }

    @Override
    public ChannelBuffer outboundByteBuffer() {
        if (outByteBuf == null) {
            throw new NoSuchBufferException();
        }
        return outByteBuf;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Queue<T> outboundMessageBuffer() {
        if (outMsgBuf == null) {
            throw new NoSuchBufferException();
        }
        return (Queue<T>) outMsgBuf;
    }

    @Override
    public boolean hasNextInboundByteBuffer() {
        return DefaultChannelPipeline.hasNextInboundByteBuffer(next);
    }

    @Override
    public boolean hasNextInboundMessageBuffer() {
        return DefaultChannelPipeline.hasNextInboundMessageBuffer(next);
    }

    @Override
    public boolean hasNextOutboundByteBuffer() {
        return pipeline.hasNextOutboundByteBuffer(prev);
    }

    @Override
    public boolean hasNextOutboundMessageBuffer() {
        return pipeline.hasNextOutboundMessageBuffer(prev);
    }

    @Override
    public ChannelBuffer nextInboundByteBuffer() {
        return DefaultChannelPipeline.nextInboundByteBuffer(next);
    }

    @Override
    public Queue<Object> nextInboundMessageBuffer() {
        return DefaultChannelPipeline.nextInboundMessageBuffer(next);
    }

    @Override
    public ChannelBuffer nextOutboundByteBuffer() {
        return pipeline.nextOutboundByteBuffer(prev);
    }

    @Override
    public Queue<Object> nextOutboundMessageBuffer() {
        return pipeline.nextOutboundMessageBuffer(prev);
    }

    @Override
    public void fireChannelRegistered() {
        DefaultChannelHandlerContext next = nextContext(this.next, ChannelHandlerType.STATE);
        if (next != null) {
            DefaultChannelPipeline.fireChannelRegistered(next);
        }
    }

    @Override
    public void fireChannelUnregistered() {
        DefaultChannelHandlerContext next = nextContext(this.next, ChannelHandlerType.STATE);
        if (next != null) {
            DefaultChannelPipeline.fireChannelUnregistered(next);
        }
    }

    @Override
    public void fireChannelActive() {
        DefaultChannelHandlerContext next = nextContext(this.next, ChannelHandlerType.STATE);
        if (next != null) {
            DefaultChannelPipeline.fireChannelActive(next);
        }
    }

    @Override
    public void fireChannelInactive() {
        DefaultChannelHandlerContext next = nextContext(this.next, ChannelHandlerType.STATE);
        if (next != null) {
            DefaultChannelPipeline.fireChannelInactive(next);
        }
    }

    @Override
    public void fireExceptionCaught(Throwable cause) {
        DefaultChannelHandlerContext next = this.next;
        if (next != null) {
            pipeline.fireExceptionCaught(next, cause);
        } else {
            DefaultChannelPipeline.logTerminalException(cause);
        }
    }

    @Override
    public void fireUserEventTriggered(Object event) {
        DefaultChannelHandlerContext next = this.next;
        if (next != null) {
            pipeline.fireUserEventTriggered(next, event);
        }
    }

    @Override
    public void fireInboundBufferUpdated() {
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            nextCtxFireInboundBufferUpdatedTask.run();
        } else {
            executor.execute(nextCtxFireInboundBufferUpdatedTask);
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newFuture());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newFuture());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newFuture());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newFuture());
    }

    @Override
    public ChannelFuture close() {
        return close(newFuture());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newFuture());
    }

    @Override
    public ChannelFuture flush() {
        return flush(newFuture());
    }

    @Override
    public ChannelFuture write(Object message) {
        return write(message, newFuture());
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelFuture future) {
        return pipeline.bind(nextContext(prev, ChannelHandlerType.OPERATION), localAddress, future);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelFuture future) {
        return connect(remoteAddress, null, future);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelFuture future) {
        return pipeline.connect(nextContext(prev, ChannelHandlerType.OPERATION), remoteAddress, localAddress, future);
    }

    @Override
    public ChannelFuture disconnect(ChannelFuture future) {
        return pipeline.disconnect(nextContext(prev, ChannelHandlerType.OPERATION), future);
    }

    @Override
    public ChannelFuture close(ChannelFuture future) {
        return pipeline.close(nextContext(prev, ChannelHandlerType.OPERATION), future);
    }

    @Override
    public ChannelFuture deregister(ChannelFuture future) {
        return pipeline.deregister(nextContext(prev, ChannelHandlerType.OPERATION), future);
    }

    @Override
    public ChannelFuture flush(final ChannelFuture future) {
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            DefaultChannelHandlerContext prev = nextContext(this.prev, ChannelHandlerType.OPERATION);
            prev.fillBridge();
            pipeline.flush(prev, future);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    flush(future);
                }
            });
        }

        return future;
    }

    @Override
    public ChannelFuture write(Object message, ChannelFuture future) {
        return pipeline.write(prev, message, future);
    }

    @Override
    public ChannelFuture newFuture() {
        return channel.newFuture();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return channel.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return channel.newFailedFuture(cause);
    }

    static final class MessageBridge {
        final Queue<Object> msgBuf = new ArrayDeque<Object>();
        final BlockingQueue<Object[]> exchangeBuf = QueueFactory.createQueue();

        void fill() {
            if (msgBuf.isEmpty()) {
                return;
            }
            Object[] data = msgBuf.toArray();
            msgBuf.clear();
            exchangeBuf.add(data);
        }

        void flush(Queue<Object> out) {
            for (;;) {
                Object[] data = exchangeBuf.poll();
                if (data == null) {
                    break;
                }

                for (Object d: data) {
                    out.add(d);
                }
            }
        }
    }

    static final class StreamBridge {
        final ChannelBuffer byteBuf = ChannelBuffers.dynamicBuffer();
        final BlockingQueue<ChannelBuffer> exchangeBuf = QueueFactory.createQueue();

        void fill() {
            if (!byteBuf.readable()) {
                return;
            }
            ChannelBuffer data = byteBuf.readBytes(byteBuf.readableBytes());
            byteBuf.discardReadBytes();
            exchangeBuf.add(data);
        }

        void flush(ChannelBuffer out) {
            for (;;) {
                ChannelBuffer data = exchangeBuf.poll();
                if (data == null) {
                    break;
                }

                out.writeBytes(data);
            }
        }
    }
}
