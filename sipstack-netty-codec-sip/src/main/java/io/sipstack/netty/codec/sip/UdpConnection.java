package io.sipstack.netty.codec.sip;

import io.netty.channel.Channel;
import io.pkts.packet.sip.SipMessage;
import io.sipstack.netty.codec.sip.event.SipMessageEvent;

import java.net.InetSocketAddress;

/**
 * Encapsulates a
 * 
 * @author jonas@jonasborjesson.com
 */
public final class UdpConnection extends AbstractConnection {

    // public UdpConnection(final ChannelHandlerContext ctx, final InetSocketAddress remoteAddress)
    // {
    // super(ctx, remoteAddress);
    // }

    public UdpConnection(final Channel channel, final InetSocketAddress remoteAddress) {
        super(Transport.udp, channel, remoteAddress);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUDP() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(final SipMessage msg) {
        // final DatagramPacket pkt = new DatagramPacket(toByteBuf(msg), getRemoteAddress());
        // channel().writeAndFlush(pkt);
        // System.err.println("UDPConnection: sending");
        final SipMessageEvent event = new SipMessageEvent(this, msg, System.currentTimeMillis());
        channel().writeAndFlush(event);
    }

    @Override
    public boolean connect() {
        return true;
    }

}
