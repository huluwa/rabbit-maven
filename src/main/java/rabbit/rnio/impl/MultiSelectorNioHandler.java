package rabbit.rnio.impl;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import rabbit.rnio.AcceptHandler;
import rabbit.rnio.ConnectHandler;
import rabbit.rnio.NioHandler;
import rabbit.rnio.ReadHandler;
import rabbit.rnio.SelectorVisitor;
import rabbit.rnio.SocketChannelHandler;
import rabbit.rnio.StatisticsHolder;
import rabbit.rnio.TaskIdentifier;
import rabbit.rnio.WriteHandler;

/** An implementation of NioHandler that runs several selector threads.
 *
 * <p>Any tasks that should run on a background thread are passed to the
 * {@link ExecutorService} that was given in the constructor.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class MultiSelectorNioHandler implements NioHandler {
    /** The executor service. */
    private final ExecutorService executorService;
    private final List<SingleSelectorRunner> selectorRunners;
    private final StatisticsHolder stats;
    private final Long defaultTimeout;
    private int nextIndex = 0;

    /** Create a new MultiSelectorNioHandler that runs background tasks on
     *  the given executor and has a specified number of selectors.
     *
     * @param executorService the ExecutorService to use for this NioHandler
     * @param stats the StatisticsHolder to use for this NioHandler
     * @param numSelectors the number of threads that this NioHandler will use
     * @param defaultTimeout the default timeout value for this NioHandler
     * @throws IOException if the selectors can not be started
     */
    public MultiSelectorNioHandler(final ExecutorService executorService,
                                   final StatisticsHolder stats,
                                   final int numSelectors,
                                   final Long defaultTimeout)
            throws IOException {
        this.executorService = executorService;
        this.stats = stats;

        if (numSelectors < 1) {
            final String err = "Must have at least one selector: " + numSelectors;
            throw new IllegalArgumentException(err);
        }
        selectorRunners = new ArrayList<>(numSelectors);
        for (int i = 0; i < numSelectors; i++) {
            selectorRunners.add(new SingleSelectorRunner(executorService));
        }
        if (defaultTimeout != null && defaultTimeout <= 0) {
            final String err = "Default timeout may not be zero or negative";
            throw new IllegalArgumentException(err);
        }
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public void start(final ThreadFactory tf) {
        for (SingleSelectorRunner ssr : selectorRunners) {
            ssr.start(tf);
        }
    }

    @Override
    public void shutdown() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                executorService.shutdown();
                for (SingleSelectorRunner ssr : selectorRunners) {
                    ssr.shutdown();
                }
            }
        });
        t.start();
    }

    @Override
    public Long getDefaultTimeout() {
        if (defaultTimeout == null) {
            return null;
        }
        return System.currentTimeMillis() +
               defaultTimeout;
    }

    @Override
    public void runThreadTask(final Runnable r, final TaskIdentifier ti) {
        stats.addPendingTask(ti);
        try {
            executorService.execute(new StatisticsCollector(stats, r, ti));
        } catch (RejectedExecutionException e) {
            log.warn("Could not launch exeuctor", e);
        }
    }

    private SingleSelectorRunner getSelectorRunner() {
        int index;
        synchronized (this) {
            index = nextIndex++;
            nextIndex %= selectorRunners.size();
        }
        return selectorRunners.get(index);
    }

    /** Run a task on one of the selector threads.
     *  The task will be run sometime in the future.
     * @param channel the channel to run the task on
     * @param sr the task to run on the main thread.
     */
    private void runSelectorTask(final SelectableChannel channel,
                                 final SelectorRunnable sr) {
        // If the channel is already being served by someone, favor that one.
        for (SingleSelectorRunner ssr : selectorRunners) {
            if (ssr.handlesChannel(channel)) {
                ssr.runSelectorTask(sr);
                return;
            }
        }
        // Put it on any selector
        final SingleSelectorRunner ssr = getSelectorRunner();
        ssr.runSelectorTask(sr);
    }

    @Override
    public void waitForRead(final SelectableChannel channel,
                            final ReadHandler handler) {
        log.trace("Waiting for read for: channel: {}, handler: {}", channel, handler);
        runSelectorTask(channel, new SelectorRunnable() {
            @Override
            public void run(final SingleSelectorRunner ssr) throws IOException {
                ssr.waitForRead(channel, handler);
            }
        });
    }

    @Override
    public void waitForWrite(final SelectableChannel channel,
                             final WriteHandler handler) {
        log.trace("Waiting for write for: channel: {}, handler: {}", channel, handler);
        runSelectorTask(channel, new SelectorRunnable() {
            @Override
            public void run(final SingleSelectorRunner ssr) throws IOException {
                ssr.waitForWrite(channel, handler);
            }
        });
    }

    @Override
    public void waitForAccept(final SelectableChannel channel,
                              final AcceptHandler handler) {
        log.trace("Waiting for accept for: channel: {}, handler{}", channel, handler);
        runSelectorTask(channel, new SelectorRunnable() {
            @Override
            public void run(final SingleSelectorRunner ssr) throws IOException {
                ssr.waitForAccept(channel, handler);
            }
        });
    }

    @Override
    public void waitForConnect(final SelectableChannel channel,
                               final ConnectHandler handler) {
        runSelectorTask(channel, new SelectorRunnable() {
            @Override
            public void run(final SingleSelectorRunner ssr) throws IOException {
                ssr.waitForConnect(channel, handler);
            }
        });
    }

    @Override
    public void cancel(final SelectableChannel channel,
                       final SocketChannelHandler handler) {
        for (SingleSelectorRunner sr : selectorRunners) {
            sr.runSelectorTask(new SelectorRunnable() {
                @Override
                public void run(final SingleSelectorRunner ssr) {
                    ssr.cancel(channel, handler);
                }
            });
        }
    }

    @Override
    public void close(final SelectableChannel channel) {
        for (SingleSelectorRunner sr : selectorRunners) {
            sr.runSelectorTask(new SelectorRunnable() {
                @Override
                public void run(final SingleSelectorRunner ssr) {
                    ssr.close(channel);
                }
            });
        }
    }

    @Override
    public void visitSelectors(final SelectorVisitor visitor) {
        // TODO: do we need to run on the respective threads?
        for (SingleSelectorRunner sr : selectorRunners) {
            sr.visit(visitor);
        }
        visitor.end();
    }

    // TODO: where does this belong?
    public StatisticsHolder getTimingStatistics() {
        return stats;
    }
}
