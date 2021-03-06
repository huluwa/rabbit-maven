package rabbit.rnio.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import rabbit.rnio.NioHandler;
import rabbit.rnio.StatisticsHolder;

/** A basic server for rnio.
 *
 * <p>This server will create a {@link MultiSelectorNioHandler} using a 
 * {@link BasicStatisticsHolder} and the ExecutorService you pass.
 * <br>When you start this server it will begin to listen for socket connections
 * on the specified InetAddress and port and hand off new socket connections
 * to the {@link AcceptorListener}
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
class AcceptingServer {
    private final ServerSocketChannel ssc;
    private final AcceptorListener listener;
    private final NioHandler nioHandler;

    /** Create a new server using the parameters given.
     * @param addr the InetAddress to bind to, may be null for wildcard address
     * @param port the port number to bind to
     * @param listener the client that will handle the accepted sockets
     * @param es the ExecutorService to use for the NioHandler
     * @param selectorThreads the number of threads that the NioHandler will use
     * @param defaultTimeout the default timeout value for the NioHandler
     * @throws IOException if network setup fails
     */
    public AcceptingServer(final InetAddress addr,
                           final int port,
                           final AcceptorListener listener,
                           final ExecutorService es,
                           final int selectorThreads,
                           final Long defaultTimeout)
            throws IOException {
        ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        final ServerSocket ss = ssc.socket();
        ss.bind(new InetSocketAddress(addr, port));
        this.listener = listener;
        final StatisticsHolder stats = new BasicStatisticsHolder();
        nioHandler =
                new MultiSelectorNioHandler(es, stats, selectorThreads,
                                            defaultTimeout);
    }

    /** Start the NioHandler and register to accept new socket connections.
     */
    public void start() {
        nioHandler.start(new SimpleThreadFactory());
        final Acceptor acceptor = new Acceptor(ssc, nioHandler, listener);
        acceptor.register();
    }

    /** Shutdown the NioHandler.
     */
    public void shutdown() {
        nioHandler.shutdown();
    }

    /** Get the NioHandler in use by this server.
     * @return the NioHandler used by this server
     */
    public NioHandler getNioHandler() {
        return nioHandler;
    }
}
