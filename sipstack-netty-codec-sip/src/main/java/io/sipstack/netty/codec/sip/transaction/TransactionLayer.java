package io.sipstack.netty.codec.sip.transaction;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.pkts.packet.sip.SipMessage;
import io.sipstack.netty.codec.sip.Clock;
import io.sipstack.netty.codec.sip.InboundOutboundHandlerAdapter;
import io.sipstack.netty.codec.sip.actor.InternalScheduler;
import io.sipstack.netty.codec.sip.actor.SingleContext;
import io.sipstack.netty.codec.sip.config.TransactionLayerConfiguration;
import io.sipstack.netty.codec.sip.event.Event;
import io.sipstack.netty.codec.sip.event.SipMessageEvent;
import io.sipstack.netty.codec.sip.event.SipTimerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author jonas@jonasborjesson.com
 */
public class TransactionLayer extends InboundOutboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TransactionLayer.class);

    private final TransactionLayerConfiguration config;

    // TODO: This need to be configurable. Also, the JDK map implementation may
    // not be the fastest around either so do some performance tests regarding
    // that...
    private final Map<TransactionId, TransactionActor> transactions = new ConcurrentHashMap<>(500000, 0.75f);

    private final InternalScheduler scheduler;

    private final Clock clock;

    public TransactionLayer(final Clock clock, final InternalScheduler scheduler, final TransactionLayerConfiguration config) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.config = config;
    }

    /**
     * We only expect {@link SipMessageEvent}s here since there will always be a
     * decoder in-front of this one.
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        processEvent(ctx, msg);
    }

    /**
     * From ChannelOutboundHandler
     */
    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        processEvent(ctx, msg);
    }

    /**
     * Timers end up here and if we get one, process it and do not pass it up the pipeline. The reason is
     * that the timer that is fired belong the transaction layer otherwise it would have ended up
     * elsewhere.
     *
     * @param ctx
     * @param evt
     * @throws Exception
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        try {
            processSipTimerEvent(ctx, ((Event) msg).toSipTimerEvent());
        } catch(final ClassCastException e) {
            ctx.fireUserEventTriggered(msg);
        }
    }

    private void processSipTimerEvent(final ChannelHandlerContext ctx, final SipTimerEvent event) {
        try {
            final TransactionId id = (TransactionId) event.key();
            final TransactionActor transaction = transactions.get(id);
            if (transaction != null) {
                invoke(ctx, event, transaction);
                checkIfTerminated(transaction);
            }

        } catch (final ClassCastException e) {
            // TODO
            e.printStackTrace();
        }
    }

    public Optional<Transaction> getTransaction(final TransactionId id) {
        final TransactionActor actor = transactions.get(id);
        if (actor != null) {
            return Optional.of(actor.transaction());
        }

        return Optional.empty();
    }

    /**
     * If an actor has been terminated then we will clean it up.
     *
     * @param actor
     */
    private void checkIfTerminated(final TransactionActor actor) {
        if (actor != null && actor.isTerminated()) {
            transactions.remove(actor.id());
            // TODO: an actor can emit more events here.
            actor.stop();
            actor.postStop();
        }

    }

    private void processEvent(final ChannelHandlerContext ctx, final Object msg) {
        try {
            final Event event = (Event)msg;
            final SipMessage sipMsg = event.toSipMessageEvent().message();
            final TransactionActor transaction = ensureTransaction(sipMsg);
            invoke(ctx, event, transaction);
            checkIfTerminated(transaction);
        } catch (final ClassCastException e) {
            // strange...
            logger.warn("Got a unexpected message of type {}. Will ignore.", msg.getClass());
        }
    }

    private void invoke(final ChannelHandlerContext ctx, final Event msg, final TransactionActor transaction) {
        if (transaction == null) {
            return;
        }

        try {
            final SingleContext actorCtx = invokeTransaction(ctx, msg, transaction);
            actorCtx.downstream().ifPresent(e -> ctx.write(e));
            actorCtx.upstream().ifPresent(e -> ctx.fireChannelRead(e));
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private TransactionActor ensureTransaction(final SipMessage sipMsg) {
        final TransactionId id = TransactionId.create(sipMsg);
        return transactions.computeIfAbsent(id, obj -> {

            if (sipMsg.isResponse()) {
                // wtf. Stray response, deal with it
                throw new RuntimeException("Sorry, not dealing with stray responses right now");
            }

            if (sipMsg.isInvite()) {
                return new InviteServerTransactionActor(id, sipMsg.toRequest(), config);
            }

            // if ack doesn't match an existing transaction then this ack must have been to a 2xx and
            // therefore goes in its own transaction but then ACKs doesn't actually have a real
            // transaction so therefore, screw it...
            if (sipMsg.isAck()) {
                return null;
            }

            return new NonInviteServerTransactionActor(id, sipMsg.toRequest(), config);
        });
    }

    /**
     *
     * @param event
     * @param transaction
     * @return
     */
    private SingleContext invokeTransaction(final ChannelHandlerContext channelCtx,
                                            final Event event,
                                            final TransactionActor transaction) {

        final SingleContext ctx = new SingleContext(clock, scheduler, channelCtx, transaction != null ? transaction.id() : null, this);
        if (transaction != null) {
            // Note, the synchronization model for everything within the core
            // sip stack is that you can ONLY hold one lock at a time and
            // you will ALWAYS synchronize on the actor itself. As long as
            // an actor does not try and lock something else, this should be
            // safe. However, breaking those rules and there is a good chance
            // of deadlock so if there ever is a need to have a more complicated
            // synchronization approach, then we should use another form of lock
            // that can timeout if we are not able to aquire the lock within a certain
            // time...
            synchronized (transaction) {
                try {
                    transaction.onReceive(ctx, event);
                } catch (final Throwable t) {
                    // TODO: if the actor throws an exception we should
                    // do what?
                    t.printStackTrace();;
                }
            }
        } else {
            // if there were no transaction, such as for a stray response
            // or an ACK to a 2xx invite response, then it should be
            // forwarded upstream so simply pretend we invoked and
            // actor which asked to do just that.
            ctx.forwardUpstream(event);
        }
        return ctx;
    }

}
