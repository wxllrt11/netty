/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.local;

import static io.netty.channel.Channels.*;

import java.io.IOException;
import java.net.ConnectException;

import io.netty.channel.AbstractChannelSink;
import io.netty.channel.Channel;
import io.netty.channel.ChannelEvent;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelState;
import io.netty.channel.ChannelStateEvent;
import io.netty.channel.MessageEvent;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

/**
 */
final class LocalClientChannelSink extends AbstractChannelSink {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(LocalClientChannelSink.class);

    LocalClientChannelSink() {
    }

    @Override
    public void eventSunk(ChannelPipeline pipeline, ChannelEvent e) throws Exception {
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent event = (ChannelStateEvent) e;

            DefaultLocalChannel channel =
                  (DefaultLocalChannel) event.getChannel();
            ChannelFuture future = event.getFuture();
            ChannelState state = event.getState();
            Object value = event.getValue();
            switch (state) {
            case OPEN:
                if (Boolean.FALSE.equals(value)) {
                    channel.closeNow(future);
                }
                break;
            case BOUND:
                if (value != null) {
                    bind(channel, future, (LocalAddress) value);
                } else {
                    channel.closeNow(future);
                }
                break;
            case CONNECTED:
                if (value != null) {
                    connect(channel, future, (LocalAddress) value);
                } else {
                    channel.closeNow(future);
                }
                break;
            case INTEREST_OPS:
                // Unsupported - discard silently.
                future.setSuccess();
                break;
            }
        } else if (e instanceof MessageEvent) {
            MessageEvent event = (MessageEvent) e;
            DefaultLocalChannel channel = (DefaultLocalChannel) event.getChannel();
            boolean offered = channel.writeBuffer.offer(event);
            assert offered;
            channel.flushWriteBuffer();
        }
    }

    private void bind(DefaultLocalChannel channel, ChannelFuture future, LocalAddress localAddress) {
        try {
            if (!LocalChannelRegistry.register(localAddress, channel)) {
                throw new ChannelException("address already in use: " + localAddress);
            }

            channel.setBound();
            channel.localAddress = localAddress;
            future.setSuccess();
            fireChannelBound(channel, localAddress);
        } catch (Throwable t) {
            LocalChannelRegistry.unregister(localAddress);
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        }
    }

    private void connect(DefaultLocalChannel channel, ChannelFuture future, LocalAddress remoteAddress) {
        Channel remoteChannel = LocalChannelRegistry.getChannel(remoteAddress);
        if (!(remoteChannel instanceof DefaultLocalServerChannel)) {
            future.setFailure(new ConnectException("connection refused"));
            return;
        }

        DefaultLocalServerChannel serverChannel = (DefaultLocalServerChannel) remoteChannel;
        ChannelPipeline pipeline;
        try {
            pipeline = serverChannel.getConfig().getPipelineFactory().getPipeline();
        } catch (Exception e) {
            future.setFailure(e);
            fireExceptionCaught(channel, e);
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "Failed to initialize an accepted socket.", e);
            }
            return;
        }

        future.setSuccess();
        DefaultLocalChannel acceptedChannel = DefaultLocalChannel.create(serverChannel, serverChannel.getFactory(), pipeline, this, channel);
        channel.pairedChannel = acceptedChannel;

        bind(channel, succeededFuture(channel), new LocalAddress(LocalAddress.EPHEMERAL));
        channel.remoteAddress = serverChannel.getLocalAddress();
        channel.setConnected();
        fireChannelConnected(channel, serverChannel.getLocalAddress());

        acceptedChannel.localAddress = serverChannel.getLocalAddress();
        try {
            acceptedChannel.setBound();
        } catch (IOException e) {
            throw new Error(e);
        }
        fireChannelBound(acceptedChannel, channel.getRemoteAddress());
        acceptedChannel.remoteAddress = channel.getLocalAddress();
        acceptedChannel.setConnected();
        fireChannelConnected(acceptedChannel, channel.getLocalAddress());

        // Flush something that was written in channelBound / channelConnected
        channel.flushWriteBuffer();
        acceptedChannel.flushWriteBuffer();
    }
}
