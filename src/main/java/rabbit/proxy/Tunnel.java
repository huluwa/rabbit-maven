package rabbit.proxy;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import rabbit.rnio.NioHandler;
import rabbit.rnio.ReadHandler;
import rabbit.rnio.WriteHandler;
import rabbit.rnio.impl.Closer;
import rabbit.io.BufferHandle;
import rabbit.util.TrafficLogger;

/** A handler that just tunnels data.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class Tunnel {
    private final NioHandler nioHandler;
    private final OneWayTunnel fromToTo;
    private final OneWayTunnel toToFrom;
    private final TunnelDoneListener listener;

    /** Create a tunnel that transfers data as fast as possible in full
     *  duplex.
     * @param nioHandler the NioHandler to use for waiting on data to read
     *        as well as waiting for write ready
     * @param from one end of the tunnel
     * @param fromHandle the ByteBuffer holder for the data from "from"
     * @param fromLogger the traffic statistics gatherer for "from"
     * @param to the other end of the tunnel
     * @param toHandle the ByteBuffer holder for the data from "from"
     * @param toLogger the traffic statistics gatherer for "from"
     * @param listener the listener that will be notified when the tunnel
     *        is closed
     */
    public Tunnel(final NioHandler nioHandler, final SocketChannel from,
                  final BufferHandle fromHandle,
                  final TrafficLogger fromLogger,
                  final SocketChannel to, final BufferHandle toHandle,
                  final TrafficLogger toLogger,
                  final TunnelDoneListener listener) {
        log.trace("Tunnel created from: {} to: {}", from, to);
        this.nioHandler = nioHandler;
        fromToTo = new OneWayTunnel(from, to, fromHandle, fromLogger);
        toToFrom = new OneWayTunnel(to, from, toHandle, toLogger);
        this.listener = listener;
    }

    /** Start tunneling data in both directions.
     */
    public void start() {
        log.trace("Tunnel started");
        fromToTo.start();
        toToFrom.start();
    }

    private class OneWayTunnel implements ReadHandler, WriteHandler {
        private final SocketChannel from;
        private final SocketChannel to;
        private final BufferHandle bh;
        private final TrafficLogger tl;

        public OneWayTunnel(final SocketChannel from, final SocketChannel to,
                            final BufferHandle bh, final TrafficLogger tl) {
            this.from = from;
            this.to = to;
            this.bh = bh;
            this.tl = tl;
        }

        public void start() {
            log.trace("OneWayTunnel started: bh.isEmpty: {}", bh.isEmpty());
            if (bh.isEmpty()) {
                waitForRead();
            } else {
                writeData();
            }
        }

        private void waitForRead() {
            bh.possiblyFlush();
            nioHandler.waitForRead(from, this);
        }

        private void waitForWrite() {
            bh.possiblyFlush();
            nioHandler.waitForWrite(to, this);
        }

        public void unregister() {
            nioHandler.cancel(from, this);
            nioHandler.cancel(to, this);

            // clear buffer and return it.
            final ByteBuffer buf = bh.getBuffer();
            buf.position(buf.limit());
            bh.possiblyFlush();
        }

        private void writeData() {
            try {
                if (!to.isOpen()) {
                    log.warn("Tunnel to is closed, not writing data");
                    closeDown();
                    return;
                }
                final ByteBuffer buf = bh.getBuffer();
                if (buf.hasRemaining()) {
                    int written;
                    do {
                        written = to.write(buf);
                        log.trace("OneWayTunnel wrote: {}", written);
                        tl.write(written);
                    } while (written > 0 && buf.hasRemaining());
                }

                if (buf.hasRemaining()) {
                    waitForWrite();
                } else {
                    waitForRead();
                }
            } catch (IOException e) {
                log.warn("Got exception writing to tunnel: {}", e);
                closeDown();
            }
        }

        @Override
        public void closed() {
            log.info("Tunnel closed");
            closeDown();
        }

        @Override
        public void timeout() {
            log.warn("Tunnel got timeout");
            closeDown();
        }

        @Override
        public boolean useSeparateThread() {
            return false;
        }

        @Override
        public String getDescription() {
            return "Tunnel part from: " + from + " to: " + to;
        }

        @Override
        public Long getTimeout() {
            return null;
        }

        @Override
        public void read() {
            try {
                if (!from.isOpen()) {
                    log.warn("Tunnel to is closed, not reading data");
                    return;
                }
                final ByteBuffer buffer = bh.getBuffer();
                buffer.clear();
                final int read = from.read(buffer);
                log.trace("OneWayTunnel read: {}", read);
                if (read == -1) {
                    buffer.position(buffer.limit());
                    closeDown();
                } else {
                    buffer.flip();
                    tl.read(read);
                    writeData();
                }
            } catch (IOException e) {
                log.warn("Got exception reading from tunnel: {}", e);
                closeDown();
            }
        }

        @Override
        public void write() {
            writeData();
        }
    }

    private void closeDown() {
        fromToTo.unregister();
        toToFrom.unregister();
        // we do not want to close the channels,
        // it is up to the listener to do that.
        if (listener != null) {
            listener.tunnelClosed();
        } else {
            // hmm? no listeners, then close down
            Closer.close(fromToTo.from);
            Closer.close(toToFrom.from);
        }
    }
}
