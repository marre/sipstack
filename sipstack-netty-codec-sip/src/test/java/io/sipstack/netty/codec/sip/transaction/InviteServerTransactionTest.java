/**
 * 
 */
package io.sipstack.netty.codec.sip.transaction;

import io.pkts.packet.sip.SipMessage;
import io.sipstack.netty.codec.sip.SipStackTestBase;
import io.sipstack.netty.codec.sip.SipTimer;
import io.sipstack.netty.codec.sip.config.TransactionLayerConfiguration;
import io.sipstack.netty.codec.sip.event.Event;
import io.sipstack.netty.codec.sip.event.SipMessageEvent;
import io.sipstack.netty.codec.sip.event.SipTimerEvent;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author jonas@jonasborjesson.com
 * 
 */
public class InviteServerTransactionTest extends SipStackTestBase {

    private TransactionLayer transactionLayer;
    private TransactionLayerConfiguration config;

    /**
     * @throws Exception
     */
    @Before
    public void setUp() throws Exception {
        super.setUp();
        config = new TransactionLayerConfiguration();
        transactionLayer = new TransactionLayer(scheduler, config);
    }

    /**
     * Test the basic transitions of the invite server transaction. I.e.,
     * INVITE followed by a 200.
     *
     * @throws Exception
     */
    @Test
    public void testBasicTransition() throws Exception {
        final Event invite = createEvent(defaultInviteRequest);
        transactionLayer.channelRead(defaultChannelCtx, invite);

        // the invite transaction should have been forwarded to the
        // next handler in the pipe
        verify(defaultChannelCtx).fireChannelRead(invite);

        // and no downstream events at all...
        verify(defaultChannelCtx, never()).write(anyObject());

        // send a 200 OK...
        final Event twoHundred = createEvent(defaultInvite200Response);
        transactionLayer.write(defaultChannelCtx, twoHundred, null);

        verify(defaultChannelCtx).write(twoHundred);

        // Timer L should have been scheduled.
        verify(scheduler).schedule(SipTimer.L, Duration.ofSeconds(32));

        // "fire" timer L
        final TransactionId key = TransactionId.create(defaultInvite200Response);
        transactionLayer.userEventTriggered(defaultChannelCtx, SipTimerEvent.withTimer(SipTimer.L).withKey(key).build());
    }

    public SipMessageEvent createEvent(final SipMessage msg) {
        return new SipMessageEvent(defaultConnection, msg, 0);
    }

}